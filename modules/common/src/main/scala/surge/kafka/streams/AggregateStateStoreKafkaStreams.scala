// Copyright © 2017-2021 UKG Inc. <https://www.ukg.com>

package surge.kafka.streams

import akka.actor.{ ActorRef, ActorSystem }
import akka.pattern.{ ask, BackoffOpts, BackoffSupervisor }
import akka.util.Timeout
import org.apache.kafka.streams.{ LagInfo, Topology }
import surge.internal.config.{ BackoffConfig, TimeoutConfig }
import surge.internal.utils.{ BackoffChildActorTerminationWatcher, Logging }
import surge.kafka.KafkaTopic
import surge.kafka.streams.AggregateStateStoreKafkaStreamsImpl._
import surge.kafka.streams.KafkaStreamLifeCycleManagement.{ Restart, Start, Stop }
import surge.metrics.Metrics

import scala.concurrent.{ ExecutionContext, Future }

object AggregateStreamsWriteBufferSettings extends WriteBufferSettings {
  override def maxWriteBufferNumber: Int = 2
  override def writeBufferSizeMb: Int = 32
}

object AggregateStreamsBlockCacheSettings extends BlockCacheSettings {
  override def blockSizeKb: Int = 16
  override def blockCacheSizeMb: Int = 16
  override def cacheIndexAndFilterBlocks: Boolean = true
}

class AggregateStreamsRocksDBConfig
    extends CustomRocksDBConfigSetter(AggregateStreamsBlockCacheSettings, AggregateStreamsWriteBufferSettings)

    /**
     * Creates a state store exposed as a Kafka Streams KTable for a particular Kafka Topic. The state in the KTable are key value pairs based on the Kafka Key
     * and Kafka Value for each record read from the given topic.
     *
     * @param aggregateName
     *   Name of the aggregate being consumed. Used to define consumer group and the name of the state store backing the KTable.
     * @param stateTopic
     *   The topic of state key/value pairs to consume and expose via a KTable. This topic should be compacted.
     * @param partitionTrackerProvider
     *   Registered within a Kafka Streams state change listener to track updates to the Kafka Streams consumer group. When the consumer group transitions from
     *   rebalancing to running, the partition tracker provided will be notified automatically. This can be used for notifying other processes/interested
     *   parties that a consumer group change has occurred.
     * @param aggregateValidator
     *   Validation function used for each consumed message from Kafka to check if a change from previous aggregate state to new aggregate state is valid or
     *   not. Just emits a warning if the change is not valid.
     * @param applicationHostPort
     *   Optional string to use for a host/port exposed by this application. This information is exposed to the partition tracker provider as mappings from
     *   application host/port to assigned partitions.
     * @tparam Agg
     *   Aggregate type being read from Kafka - business logic type of the Kafka values in the state topic
     */
class AggregateStateStoreKafkaStreams[Agg >: Null](
    aggregateName: String,
    stateTopic: KafkaTopic,
    partitionTrackerProvider: KafkaStreamsPartitionTrackerProvider,
    aggregateValidator: (String, Array[Byte], Option[Array[Byte]]) => Boolean,
    applicationHostPort: Option[String],
    applicationId: String,
    clientId: String,
    system: ActorSystem,
    metrics: Metrics)
    extends HealthyComponent
    with Logging {

  private[streams] lazy val settings = AggregateStateStoreKafkaStreamsImplSettings(applicationId, aggregateName, clientId)

  private[streams] val underlyingActor = createUnderlyingActorWithBackOff()

  private implicit val askTimeoutDuration: Timeout = TimeoutConfig.StateStoreKafkaStreamActor.askTimeout

  /**
   * Used to actually start the Kafka Streams process. Optionally cleans up persistent state directories left behind by previous runs if
   * `kafka.streams.wipe-state-on-start` config setting is set to true.
   */
  def start(): Unit = {
    underlyingActor ! Start
  }

  def stop(): Unit = {
    underlyingActor ! Stop
  }

  def restart(): Unit = {
    underlyingActor ! Restart
  }

  def partitionLags()(implicit ec: ExecutionContext): Future[Map[String, Map[java.lang.Integer, LagInfo]]] = {
    underlyingActor.ask(GetLocalStorePartitionLags).mapTo[LocalStorePartitionLags].map(_.lags)
  }

  def substatesForAggregate(aggregateId: String)(implicit ec: ExecutionContext): Future[List[(String, Array[Byte])]] = {
    underlyingActor.ask(GetSubstatesForAggregate(aggregateId)).mapTo[List[(String, Array[Byte])]]
  }

  def getAggregateBytes(aggregateId: String): Future[Option[Array[Byte]]] = {
    underlyingActor.ask(GetAggregateBytes(aggregateId)).mapTo[Option[Array[Byte]]]
  }

  override def healthCheck(): Future[HealthCheck] = {
    underlyingActor.ask(HealthyActor.GetHealth).mapTo[HealthCheck]
  }

  private def createUnderlyingActorWithBackOff(): ActorRef = {
    def onMaxRetries(): Unit = {
      log.error(s"Kafka stream ${settings.storeName} failed more than the max number of retries, Surge is killing the JVM")
      System.exit(1)
    }

    val aggregateStateStoreKafkaStreamsImplProps =
      AggregateStateStoreKafkaStreamsImpl.props(aggregateName, stateTopic, partitionTrackerProvider, aggregateValidator, applicationHostPort, settings, metrics)

    val underlyingActorProps = BackoffSupervisor.props(
      BackoffOpts
        .onStop(
          aggregateStateStoreKafkaStreamsImplProps,
          childName = settings.storeName,
          minBackoff = BackoffConfig.StateStoreKafkaStreamActor.minBackoff,
          maxBackoff = BackoffConfig.StateStoreKafkaStreamActor.maxBackoff,
          randomFactor = BackoffConfig.StateStoreKafkaStreamActor.randomFactor)
        .withMaxNrOfRetries(BackoffConfig.StateStoreKafkaStreamActor.maxRetries))

    val underlyingCreatedActor = system.actorOf(underlyingActorProps)

    system.actorOf(BackoffChildActorTerminationWatcher.props(underlyingCreatedActor, () => onMaxRetries()))

    underlyingCreatedActor
  }

  private[streams] def getTopology: Future[Topology] = {
    underlyingActor.ask(GetTopology).mapTo[Topology]
  }
}

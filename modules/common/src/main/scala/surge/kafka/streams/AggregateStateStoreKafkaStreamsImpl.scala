// Copyright © 2017-2021 UKG Inc. <https://www.ukg.com>

package surge.kafka.streams

import java.util.Properties

import akka.actor.Props
import akka.pattern.pipe
import com.typesafe.config.ConfigFactory
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.Serdes.ByteArraySerde
import org.apache.kafka.streams.errors.InvalidStateStoreException
import org.apache.kafka.streams.kstream.Materialized
import org.apache.kafka.streams.kstream.internals.{ KTableImpl, KTableImplExtensions }
import org.apache.kafka.streams.state.QueryableStoreTypes
import org.apache.kafka.streams.{ LagInfo, StoreQueryParameters, StreamsConfig }
import surge.kafka.KafkaTopic
import surge.kafka.streams.AggregateStateStoreKafkaStreamsImpl._
import surge.kafka.streams.HealthyActor.GetHealth
import surge.metrics.Metrics

import scala.concurrent.Future
import scala.jdk.CollectionConverters._

private[streams] class AggregateStateStoreKafkaStreamsImpl[Agg >: Null](
    aggregateName: String,
    stateTopic: KafkaTopic,
    partitionTrackerProvider: KafkaStreamsPartitionTrackerProvider,
    aggregateValidator: (String, Array[Byte], Option[Array[Byte]]) => Boolean,
    applicationHostPort: Option[String],
    override val settings: AggregateStateStoreKafkaStreamsImplSettings,
    override val metrics: Metrics)
    extends KafkaStreamLifeCycleManagement[String, Array[Byte], KafkaByteStreamsConsumer, Array[Byte]] {

  import DefaultSerdes._
  import ImplicitConversions._
  import KTableImplExtensions._
  import context.dispatcher

  private val persistencePlugin = SurgeKafkaStreamsPersistencePluginLoader.load()

  val aggregateStateStoreName: String = settings.storeName

  val healthCheckName = "aggregate-state-store"

  val topologyProps = new Properties()
  topologyProps.setProperty(StreamsConfig.TOPOLOGY_OPTIMIZATION_CONFIG, StreamsConfig.OPTIMIZE)

  val streamsConfig: Map[String, String] = baseStreamsConfig ++ Map(
    ConsumerConfig.ISOLATION_LEVEL_CONFIG -> "read_committed",
    ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG -> Integer.MAX_VALUE.toString,
    StreamsConfig.CLIENT_ID_CONFIG -> settings.clientId,
    StreamsConfig.COMMIT_INTERVAL_MS_CONFIG -> settings.commitInterval.toString,
    StreamsConfig.NUM_STANDBY_REPLICAS_CONFIG -> settings.standByReplicas.toString,
    StreamsConfig.TOPOLOGY_OPTIMIZATION_CONFIG -> StreamsConfig.OPTIMIZE,
    StreamsConfig.STATE_DIR_CONFIG -> settings.stateDirectory,
    StreamsConfig.ROCKSDB_CONFIG_SETTER_CLASS_CONFIG -> classOf[AggregateStreamsRocksDBConfig].getName)

  val validationProcessor = new ValidationProcessor[Array[Byte]](aggregateName, aggregateValidator)

  override def subscribeListeners(consumer: KafkaByteStreamsConsumer): Unit = {
    // In addition to the listener added by the KafkaStreamLifeCycleManagement we need to also subscribe this one
    val partitionTrackerListener =
      new KafkaStreamsUpdatePartitionsOnStateChangeListener(aggregateStateStoreName, partitionTrackerProvider.create(consumer.streams), false)
    consumer.streams.setStateListener(new KafkaStreamsStateChangeWithMultipleListeners(stateChangeListener, partitionTrackerListener))
    consumer.streams.setGlobalStateRestoreListener(stateRestoreListener)
    consumer.streams.setUncaughtExceptionHandler(uncaughtExceptionListener)
  }

  override def initialize(consumer: KafkaByteStreamsConsumer): Unit = {
    val aggregateStoreMaterializedBase =
      Materialized.as[String, Array[Byte]](persistencePlugin.createSupplier(aggregateStateStoreName)).withValueSerde(new ByteArraySerde())

    val aggregateStoreMaterialized = if (!persistencePlugin.enableLogging) {
      aggregateStoreMaterializedBase.withLoggingDisabled()
    } else {
      aggregateStoreMaterializedBase
    }

    val aggKTable = consumer.builder.table(stateTopic.name, aggregateStoreMaterialized)

    // Reach into the underlying implementation and tell it to send the old value for an aggregate
    // along with the newly updated value. The old value is only used for validation
    val aggKTableJavaImpl = aggKTable.inner.asInstanceOf[KTableImpl[String, Array[Byte], Array[Byte]]]
    aggKTableJavaImpl.sendOldValues()

    // Run business logic validation and transform values from an aggregate into metadata about
    // the processed record including topic/partition, and offset of the message in Kafka
    aggKTableJavaImpl.toStreamWithChanges.transformValues(validationProcessor.supplier)
  }

  override def createQueryableStore(consumer: KafkaByteStreamsConsumer): KafkaStreamsKeyValueStore[String, Array[Byte]] = {
    log.debug(s"Initializing state store ${settings.storeName}")
    val storeParams = StoreQueryParameters.fromNameAndType(aggregateStateStoreName, QueryableStoreTypes.keyValueStore[String, Array[Byte]]())
    val underlying = consumer.streams.store(storeParams)
    new KafkaStreamsKeyValueStore(underlying)
  }

  override def createConsumer(): KafkaByteStreamsConsumer = {
    val maybeOptimizeTopology = if (persistencePlugin.enableLogging) {
      Some(topologyProps)
    } else {
      None
    }

    KafkaByteStreamsConsumer(
      brokers = settings.brokers,
      applicationId = settings.applicationId,
      kafkaConfig = streamsConfig,
      applicationServerConfig = applicationHostPort,
      topologyProps = maybeOptimizeTopology)
  }

  override def uninitialized: Receive = stashIt

  override def created(consumer: KafkaByteStreamsConsumer): Receive = stashIt

  override def running(consumer: KafkaByteStreamsConsumer, aggregateQueryableStateStore: KafkaStreamsKeyValueStore[String, Array[Byte]]): Receive = {
    case GetSubstatesForAggregate(aggregateId) =>
      getSubstatesForAggregate(aggregateQueryableStateStore, aggregateId).pipeTo(sender())
    case GetAggregateBytes(aggregateId) =>
      getAggregateBytes(aggregateQueryableStateStore, aggregateId).pipeTo(sender())
    case GetLocalStorePartitionLags =>
      val lagAsScala = consumer.streams.allLocalStorePartitionLags().asScala.map(tup => tup._1 -> tup._2.asScala.toMap).toMap
      sender() ! LocalStorePartitionLags(lagAsScala)
    case GetTopology =>
      sender() ! consumer.topology
    case GetHealth =>
      val status = if (consumer.streams.state().isRunningOrRebalancing) HealthCheckStatus.UP else HealthCheckStatus.DOWN
      sender() ! getHealth(status)
  }

  def stashIt: Receive = { case _: GetAggregateBytes | _: GetSubstatesForAggregate | GetTopology =>
    stash()
  }

  def getSubstatesForAggregate(
      aggregateQueryableStateStore: KafkaStreamsKeyValueStore[String, Array[Byte]],
      aggregateId: String): Future[List[(String, Array[Byte])]] = {
    aggregateQueryableStateStore
      .range(aggregateId, s"$aggregateId:~")
      .map { result =>
        result.filter { case (key, _) =>
          val keyBeforeColon = key.takeWhile(_ != ':')
          keyBeforeColon == aggregateId
        }
      }
      .recoverWith {
        case err: InvalidStateStoreException =>
          handleInvalidStateStore(err)
        case err: Throwable =>
          log.error(s"State store ${settings.storeName} threw an unexpected error", err)
          Future.failed(err)
      }
  }

  def getAggregateBytes(aggregateQueryableStateStore: KafkaStreamsKeyValueStore[String, Array[Byte]], aggregateId: String): Future[Option[Array[Byte]]] = {
    aggregateQueryableStateStore.get(aggregateId).recoverWith {
      case err: InvalidStateStoreException =>
        handleInvalidStateStore(err)
      case err: Throwable =>
        log.error(s"State store ${settings.storeName} threw an unexpected error", err)
        Future.failed(err)
    }
  }

  private def handleInvalidStateStore[T](err: InvalidStateStoreException): Future[T] = {
    log.warn(
      s"State store ${settings.storeName} saw InvalidStateStoreException: ${err.getMessage}. " +
        s"This error is typically caused by a consumer group rebalance.")
    Future.failed(err)
  }
}

private[streams] object AggregateStateStoreKafkaStreamsImpl {

  sealed trait AggregateStateStoreKafkaStreamsCommand
  case object GetTopology extends AggregateStateStoreKafkaStreamsCommand
  case class GetSubstatesForAggregate(aggregateId: String) extends AggregateStateStoreKafkaStreamsCommand
  case class GetAggregateBytes(aggregateId: String) extends AggregateStateStoreKafkaStreamsCommand
  case object GetLocalStorePartitionLags extends AggregateStateStoreKafkaStreamsCommand

  case class LocalStorePartitionLags(lags: Map[String, Map[java.lang.Integer, LagInfo]])

  def props(
      aggregateName: String,
      stateTopic: KafkaTopic,
      partitionTrackerProvider: KafkaStreamsPartitionTrackerProvider,
      aggregateValidator: (String, Array[Byte], Option[Array[Byte]]) => Boolean,
      applicationHostPort: Option[String],
      settings: AggregateStateStoreKafkaStreamsImplSettings,
      metrics: Metrics): Props = {
    Props(
      new AggregateStateStoreKafkaStreamsImpl(aggregateName, stateTopic, partitionTrackerProvider, aggregateValidator, applicationHostPort, settings, metrics))
  }

  case class AggregateStateStoreKafkaStreamsImplSettings(
      storeName: String,
      brokers: Seq[String],
      applicationId: String,
      clientId: String,
      cacheMemory: Long,
      standByReplicas: Int,
      commitInterval: Int,
      stateDirectory: String,
      clearStateOnStartup: Boolean)
      extends KafkaStreamSettings

  object AggregateStateStoreKafkaStreamsImplSettings {
    def apply(applicationId: String, aggregateName: String, clientId: String): AggregateStateStoreKafkaStreamsImplSettings = {
      val config = ConfigFactory.load()
      val aggregateStateStoreName: String = s"${aggregateName}AggregateStateStore"
      val brokers = config.getString("kafka.brokers").split(",").toVector
      val cacheHeapPercentage = config.getDouble("kafka.streams.cache-heap-percentage")
      val totalMemory = Runtime.getRuntime.maxMemory()
      val cacheMemory = (totalMemory * cacheHeapPercentage).longValue
      val standbyReplicas = config.getInt("kafka.streams.num-standby-replicas")
      val commitInterval = config.getInt("kafka.streams.commit-interval-ms")
      val stateDirectory = config.getString("kafka.streams.state-dir")
      val clearStateOnStartup = config.getBoolean("kafka.streams.wipe-state-on-start")

      new AggregateStateStoreKafkaStreamsImplSettings(
        storeName = aggregateStateStoreName,
        brokers = brokers,
        applicationId = applicationId,
        clientId = clientId,
        cacheMemory = cacheMemory,
        standByReplicas = standbyReplicas,
        commitInterval = commitInterval,
        stateDirectory = stateDirectory,
        clearStateOnStartup = clearStateOnStartup)
    }
  }
}

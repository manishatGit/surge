// Copyright © 2017-2021 UKG Inc. <https://www.ukg.com>

package docs.command

import com.typesafe.config.ConfigFactory

import java.util.UUID
import play.api.libs.json.Json
import surge.core.{ SerializedAggregate, SerializedMessage, SurgeAggregateReadFormatting, SurgeWriteFormatting }
import surge.kafka.KafkaTopic
import surge.scaladsl.command.{ AggregateCommandModel, SurgeCommandBusinessLogic }

// #surge_model_class
object BankAccountSurgeModel extends SurgeCommandBusinessLogic[UUID, BankAccount, BankAccountCommand, BankAccountEvent](ConfigFactory.load()) {
  override def commandModel: AggregateCommandModel[BankAccount, BankAccountCommand, BankAccountEvent] = BankAccountCommandModel

  override def aggregateName: String = "bank-account"

  override def stateTopic: KafkaTopic = KafkaTopic("bank-account-state")

  override def eventsTopic: KafkaTopic = KafkaTopic("bank-account-events")

  override def readFormatting: SurgeAggregateReadFormatting[BankAccount] = (bytes: Array[Byte]) => Json.parse(bytes).asOpt[BankAccount]

  override def writeFormatting: SurgeWriteFormatting[BankAccount, BankAccountEvent] = new SurgeWriteFormatting[BankAccount, BankAccountEvent] {
    override def writeState(agg: BankAccount): SerializedAggregate = {
      val aggBytes = Json.toJson(agg).toString().getBytes()
      val messageHeaders = Map("aggregate_id" -> agg.accountNumber.toString)
      SerializedAggregate(aggBytes, messageHeaders)
    }

    override def writeEvent(evt: BankAccountEvent): SerializedMessage = {
      val evtKey = evt.accountNumber.toString
      val evtBytes = evt.toJson.toString().getBytes()
      val messageHeaders = Map("aggregate_id" -> evt.accountNumber.toString)
      SerializedMessage(evtKey, evtBytes, messageHeaders)
    }
  }
}
// #surge_model_class

package io.flow.event.v2

import javax.inject.{Inject, Singleton}

import io.flow.event.{Record, StreamNames}
import org.joda.time.DateTime
import play.api.libs.json.JsValue

import scala.concurrent.ExecutionContext
import scala.reflect.runtime.universe._

@Singleton
class MockQueue @Inject()() extends Queue {

  private[this] val consumers = scala.collection.mutable.Map[String, MockConsumer]()

  override def producer[T: TypeTag](
    numberShards: Int = 1,
    partitionKeyFieldName: String = "event_id"
  ): Producer = {
    val stream = MockStream()
    consumers.put(streamName[T], MockConsumer(stream))
    MockProducer(stream)
  }

  override def consumer[T: TypeTag]: Consumer = {
    consumers.get(streamName[T]).getOrElse {
      sys.error("Mock requires you to create the producer for this stream before consuming")
    }
  }

  private[this] def streamName[T: TypeTag] = {
    StreamNames.fromType[T] match {
      case Left(errors) => sys.error(errors.mkString(", "))
      case Right(name) => name
    }
  }

}

case class MockStream() {

  private[this] val pendingRecords = scala.collection.mutable.ListBuffer[Record]()
  private[this] val consumedRecords = scala.collection.mutable.ListBuffer[Record]()

  def publish(record: Record): Unit = {
    pendingRecords.append(record)
  }

  /**
    * Consumes a record, if any
    */
  def consume(): Option[Record] = {
    pendingRecords.headOption.map { r =>
      pendingRecords.remove(0)
      consumedRecords.append(r)
      r
    }
  }

  def consumeEventId(eventId: String): Option[Record] = {
    pendingRecords.find(_.eventId == eventId).map { rec =>
      val i = pendingRecords.indexOf(rec)
      pendingRecords.remove(i)
      consumedRecords.append(rec)
      rec
    }
  }

  /**
    * Returns all records seen - pending and consumed
    */
  def all: Seq[Record] = {
    pendingRecords ++ consumedRecords
  }

  def pending: Seq[Record] = pendingRecords
  def consumed: Seq[Record] = consumedRecords

}

case class MockProducer(stream: MockStream) extends Producer {

  def publish(event: JsValue)(implicit ec: ExecutionContext): Unit = {
    stream.publish(
      Record.fromJsValue(
        arrivalTimestamp = DateTime.now,
        js = event
      )
    )
  }

  def shutdown(implicit ec: ExecutionContext): Unit = {}

}

case class MockConsumer(stream: MockStream) extends Consumer {

  def consume(f: Record => Unit): Unit = {
    stream.consume().foreach(f)
  }

  def consumeEventId(eventId: String): Option[Record] = {
    stream.consumeEventId(eventId)
  }

  def shutdown(implicit ec: ExecutionContext): Unit = {}

}
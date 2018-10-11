package io.flow.event.v2

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{ConcurrentHashMap, ConcurrentLinkedQueue, Executors}

import javax.inject.{Inject, Singleton}
import io.flow.event.Record
import io.flow.util.StreamNames
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.json.{JsValue, Writes}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{FiniteDuration, MILLISECONDS}
import scala.reflect.runtime.universe._
import scala.collection.JavaConverters._

@Singleton
class MockQueue @Inject()() extends Queue with StreamUsage {

  private[this] val streams = new ConcurrentHashMap[String, MockStream]()
  private[this] val consumers = new ConcurrentLinkedQueue[RunningConsumer]()

  private[this] val debug: AtomicBoolean = new AtomicBoolean(false)

  def withDebugging(): Unit = {
    debug.set(true)
  }

  override def appName: String = "io.flow.event.v2.MockQueue"

  override def producer[T: TypeTag](
    numberShards: Int = 1,
    partitionKeyFieldName: String = "event_id"
  ): Producer[T] = {
    markProduced[T]()
    MockProducer(stream[T], debug = debug.get)
  }

  override def consume[T: TypeTag](
    f: Seq[Record] => Unit,
    pollTime: FiniteDuration = FiniteDuration(20, MILLISECONDS)
  )(
    implicit ec: ExecutionContext
  ) {
    val s = stream[T]
    val consumer = RunningConsumer(s, f, pollTime)
    markConsumed[T]()
    consumers.add(consumer)
  }

  override def shutdown(implicit ec: ExecutionContext): Unit = {
    shutdownConsumers
    streams.clear()
  }

  override def shutdownConsumers(implicit ec: ExecutionContext): Unit = {
    synchronized {
      consumers.asScala.foreach(_.shutdown())
      consumers.clear()
    }
  }

  def stream[T: TypeTag]: MockStream = {
    streams.computeIfAbsent(streamName[T],
      new java.util.function.Function[String, MockStream] { override def apply(s: String) = MockStream(debug = debug.get) })
  }

  private[this] def streamName[T: TypeTag] = {
    StreamNames.fromType[T] match {
      case Left(errors) => sys.error(errors.mkString(", "))
      case Right(name) => name
    }
  }

  /**
    * Clears all pending records from the queue.
    * Does not shutdown the consumers.
    */
  def clear(): Unit = streams.values().asScala.foreach(_.clearPending())

}

case class RunningConsumer(stream: MockStream, action: Seq[Record] => Unit, pollTime: FiniteDuration) {

  private val runnable = new Runnable() {
    override def run(): Unit = stream.consume().foreach(e => action(Seq(e)))
  }

  private val ses = Executors.newSingleThreadScheduledExecutor()
  ses.scheduleWithFixedDelay(runnable, 0, pollTime.length, pollTime.unit)

  def shutdown(): Unit =
    // use shutdownNow in case the provided action comes with a while-sleep loop.
    ses.shutdownNow()

}

case class MockStream(debug: Boolean = false) {

  private[this] def logDebug(f: => String): Unit = {
    if (debug) {
      Logger.info(s"[MockQueue Debug ${getClass.getName}] $f")
    }
  }

  private[this] val pendingRecords = new ConcurrentLinkedQueue[Record]()
  private[this] val consumedRecords = new ConcurrentLinkedQueue[Record]()

  def publish(record: Record): Unit = {
    logDebug { s"publishing record: ${record.js}" }
    pendingRecords.add(record)
  }

  /**
    * Consumes the next event in the stream, if any
    */
  def consume(): Option[Record] = {
    // synchronized for consistency between pending and consumed
    synchronized {
      logDebug { s"consume() starting" }
      val r = Option(pendingRecords.poll())
      r.foreach { rec =>
        logDebug { s"Consumed record: $rec" }
        consumedRecords.add(rec)
      }
      r
    }
  }

  /**
    * Finds the event w/ the specified id. Returns none if
    * we have not yet received this event.
    */
  def findByEventId(eventId: String): Option[Record] = {
    all.find(_.eventId == eventId)
  }

  /**
    * Returns all records seen - pending and consumed
    */
  def all: Seq[Record] = {
    // synchronized for consistency between pending and consumed
    synchronized {
      (pendingRecords.asScala ++ consumedRecords.asScala).toSeq
    }
  }

  def pending: Seq[Record] = pendingRecords.asScala.toSeq
  def consumed: Seq[Record] = consumedRecords.asScala.toSeq

  def clearPending(): Unit = pendingRecords.clear()
  def clearConsumed(): Unit = consumedRecords.clear()

}

case class MockProducer[T](stream: MockStream, debug: Boolean = false) extends Producer[T] {

  private[this] def logDebug(f: => String): Unit = {
    if (debug) {
      Logger.info(s"[MockQueue Debug ${getClass.getName}] $f")
    }
  }

  private def publish(event: JsValue)(implicit ec: ExecutionContext): Unit = {
    logDebug { s"Publishing event: $event" }
    stream.publish(
      Record.fromJsValue(
        arrivalTimestamp = DateTime.now,
        js = event
      )
    )
  }

  override def publish[U <: T](event: U)(implicit ec: ExecutionContext, serializer: Writes[U]): Unit =
    publish(serializer.writes(event))

  def shutdown(implicit ec: ExecutionContext): Unit = {
    logDebug { "shutting down" }
  }

}

package io.flow.event.v2

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{ConcurrentHashMap, ConcurrentLinkedQueue, Executors}

import com.github.ghik.silencer.silent
import io.flow.event.Record
import io.flow.log.RollbarLogger
import javax.inject.{Inject, Singleton}
import org.joda.time.DateTime
import play.api.libs.json.{JsValue, Writes}

import scala.collection.JavaConverters._
import scala.concurrent.duration.{FiniteDuration, MILLISECONDS}
import scala.reflect.runtime.universe._

// TODO: produce and consume more than one record at a time
@Singleton
class MockQueue @Inject()(
  logger: RollbarLogger
)extends Queue with StreamUsage {
  def pollTime: FiniteDuration = FiniteDuration(20, MILLISECONDS)

  private[this] val streams = new ConcurrentHashMap[String, MockStream]()
  private[this] val consumers = new ConcurrentLinkedQueue[RunningConsumer]()

  private[this] val debug: AtomicBoolean = new AtomicBoolean(false)

  private val runnable = new Runnable() {
    override def run(): Unit = {
      consumers.asScala.foreach(_.run())
    }
  }

  private val ses = Executors.newSingleThreadScheduledExecutor()
  ses.scheduleWithFixedDelay(runnable, pollTime.length, pollTime.length, pollTime.unit)

  def withDebugging(): Unit = {
    debug.set(true)
  }

  override def appName: String = "io.flow.event.v2.MockQueue"

  override def producer[T: TypeTag](
    numberShards: Int = 1
  ): Producer[T] = {
    markProducesStream(streamName[T], typeOf[T])
    MockProducer(stream[T], debug = debug.get, logger)
  }

  /**
    * @param pollTime Ignored in the mock as it is set once at the queue level
    *                 and cannot be verified in the mock per stream to minimize
    *                 resource usage in tests
    */
  @silent override def consume[T: TypeTag](
    f: Seq[Record] => Unit,
    pollTime: FiniteDuration = FiniteDuration(5, "seconds")
  ): Unit = {
    val s = stream[T]
    val consumer = RunningConsumer(s, f)
    markConsumesStream(streamName[T], typeOf[T])
    consumers.add(consumer)
    ()
  }

  override def shutdown(): Unit = {
    // use shutdownNow in case the provided action comes with a while-sleep loop.
    ses.shutdownNow()
    shutdownConsumers()
    streams.clear()
  }

  override def shutdownConsumers(): Unit = {
    synchronized {
      consumers.asScala.foreach(_.shutdown())
      consumers.clear()
    }
  }

  def stream[T: TypeTag]: MockStream = {
    streams.computeIfAbsent(
      streamName[T],
      (s: String) => MockStream(s, debug = debug.get, logger)
    )
  }

  /**
    * Clears all pending records from the queue.
    * Does not shutdown the consumers.
    */
  def clear(): Unit = streams.values().asScala.foreach(_.clearPending())

}

case class RunningConsumer(stream: MockStream, action: Seq[Record] => Unit) {

  def run(): Unit = {
    stream.consume().foreach(e => action(Seq(e)))
  }

  def shutdown(): Unit = {
    stream.clear()
  }

}

case class MockStream(streamName: String, debug: Boolean = false, logger: RollbarLogger) {

  private[this] def logDebug(f: => String): Unit = {
    if (debug) {
      logger.withKeyValue("class", getClass.getName).info(f)
    }
  }

  private[this] val pendingRecords = new ConcurrentLinkedQueue[Record]()
  private[this] val consumedRecords = new ConcurrentLinkedQueue[Record]()

  def publish(record: Record): Unit = {
    logDebug { s"publishing record: ${record.js}" }
    pendingRecords.add(record)
    ()
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

  def clear(): Unit = {
    synchronized {
      clearPending()
      clearConsumed()
    }
  }

  def clearPending(): Unit = pendingRecords.clear()
  def clearConsumed(): Unit = consumedRecords.clear()

}

case class MockProducer[T](stream: MockStream, debug: Boolean = false, logger: RollbarLogger) extends Producer[T] with StreamUsage {

  private[this] def logDebug(f: => String): Unit = {
    if (debug) {
      logger
        .withKeyValue("class", getClass.getName)
        .info(f)
    }
  }

  private def publish(event: JsValue): Unit = {
    val r = Record.fromJsValue(
      arrivalTimestamp = DateTime.now,
      js = event
    )

    logDebug { s"Publishing event: $event" }
    stream.publish(r)
  }

  override def publish[U <: T](
    event: U,
    shardProvider: KinesisShardProvider[U] = (_: U, _: JsValue) => ""
  )(implicit serializer: Writes[U]): Unit = {
    val w = serializer.writes(event)
    markProducedEvent(stream.streamName, w)
    publish(w)
  }

  def shutdown(): Unit = {
    logDebug { "shutting down" }
  }

}

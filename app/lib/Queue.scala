package io.flow.event

import io.flow.play.util.FlowEnvironment

import com.amazonaws.auth.{AWSCredentials, BasicAWSCredentials}
import com.amazonaws.services.kinesis.AmazonKinesisClient
import com.amazonaws.services.kinesis.model._

import play.api.libs.json.{JsValue,Json}
import play.api.Logger

import scala.reflect.runtime.universe._
import scala.concurrent.{Await,Future,ExecutionContext}
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success}

import java.nio.ByteBuffer
import collection.JavaConverters._

trait Queue {
  def publish[T: TypeTag](event: JsValue)(implicit ec: ExecutionContext)
  def consume[T: TypeTag](f: JsValue => T)(implicit ec: ExecutionContext)
}

@javax.inject.Singleton
class KinesisQueue @javax.inject.Inject() (
  config: io.flow.play.util.Config
) extends Queue {

  private[this] lazy val credentials = new BasicAWSCredentials(
    config.requiredString("aws.access.key"),
    config.requiredString("aws.secret.key")
  )
  private[this] lazy val client = new AmazonKinesisClient(credentials)
  private[this] val recordLimit = 1000
  private[this] val shardCreateCount = 1
  private[this] val ApidocClass = "^io.flow.([a-z]+).(v\\d+).models.(\\w+)$".r
  private[this] val setupStreams = scala.collection.mutable.Set[String]()

  def getStreamName(service: String, version: String, klass: String): String = {
    s"${FlowEnvironment.Current}.$service.$version.$klass.json"
  }

  def publish[T: TypeTag](event: JsValue)(implicit ec: ExecutionContext) {
    typeOf[T].toString match {
      case ApidocClass(service, majorVersion, className) => {
        val streamName = getStreamName(service, majorVersion, className.toLowerCase)
        val partitionKey = streamName // TODO: Figure out what this should be based on available TypeRef vals
        val data = Json.stringify(event)
        insertMessage(streamName, partitionKey, data)
      }
      case _ => {
        sys.error("Could not parse type")
      }
    }
  }

  def consume[T: TypeTag](f: JsValue => T)(implicit ec: ExecutionContext) {
    typeOf[T].toString match {
      case ApidocClass(service, majorVersion, className) => {
        val streamName = getStreamName(service, majorVersion, className.toLowerCase)
        ensureSetup(streamName)

        processMessages(streamName, f).recover {
          case e: Throwable => {
            Logger.error(s"Error processing stream $streamName")
          }
        }
      }
      case other => {
        sys.error(s"Could not identify stream name of type[$other]. Expected something like io.flow.user.v0.models.Event")
      }
    }
  }

  private[this] def ensureSetup(
    streamName: String
  )(
    implicit ec: ExecutionContext
  ) {
    setupStreams.contains(streamName) match {
      case true => {
        // No-op
      }
      case false => {
        Await.ready(
          setup(streamName), Duration(5, "seconds")
        ).value.get match {
          case Failure(err) => {
            sys.error(s"Error setting up stream[$streamName]: $err")
          }
          case Success(_) => {
            setupStreams += streamName
          }
        }
      }
    }
  }

  /**
   * Publish Helper Functions
   **/
  def insertMessage(
    streamName: String,
    partitionKey: String,
    data: String
  )(implicit ec: ExecutionContext): Future[Unit] = {
    Future {
      try {
        client.putRecord(
          new PutRecordRequest()
          .withData(ByteBuffer.wrap(data.getBytes))
          .withPartitionKey(partitionKey)
          .withStreamName(streamName)
        )
      } catch {
        case e: ResourceNotFoundException => {
          Logger.error(s"Stream $streamName does not exist. Error Message: ${e.getMessage}")
        }
        case e: Throwable => {
          Logger.error(s"Could not insert message to stream $streamName. Error Message: ${e.getMessage}")
        }
      }
    }
  }

  /**
   * Consume Helper Functions
   **/
  def processMessages[T](
    streamName: String,
    f: JsValue => T
  )(implicit ec: ExecutionContext): Future[Unit] = {
    getShards(streamName).map{ shards =>
      shards.map{ shardId =>
        getShardIterator(streamName, shardId).map { shardIterator =>
          processShard(shardIterator, f)
        }
      }
    }
  }

  def processShard[T](
    shardIterator: String,
    f: JsValue => T
  )(implicit ec: ExecutionContext): Future[Unit] = {
    getMessagesForShardIterator(shardIterator).map { result =>
      result.messages.foreach{ msg =>
        f(Json.parse(msg))
      }

      result.nextShardIterator.map{nextShardIterator =>
        processShard(nextShardIterator, f)
      }
    }
  }

  def setup(
    streamName: String
  )(
    implicit ec: ExecutionContext
  ): Future[Unit] = {
    Future {
      try {
        client.createStream(
          new CreateStreamRequest()
          .withStreamName(streamName)
          .withShardCount(shardCreateCount)
        )
        setupStreams += streamName
      } catch {
        case e: ResourceInUseException => {
          // do nothing... already exists, ignore
        }
        case e: Throwable => {
          Logger.error(s"Stream $streamName could not be created. Error Message: ${e.getMessage}")
        }
      }
    }
  }

  def getMessagesForShardIterator(
    shardIterator: String
  )(implicit ec: ExecutionContext): Future[KinesisShardMessageSummary] = {
    Future {
      val request = new GetRecordsRequest()
        .withLimit(recordLimit)
        .withShardIterator(shardIterator)
      val result = client.getRecords(request)
      val records = result.getRecords

      val messages = records.asScala.map{record =>
        val buffer = record.getData
        val bytes = Array.fill[Byte](buffer.remaining)(0)
        buffer.get(bytes)
        new String(bytes)
      }

      val nextShardIterator = (records.size == recordLimit) match {
        case true => Some(result.getNextShardIterator)
        case false => None
      }

      KinesisShardMessageSummary(messages, nextShardIterator)
    }
  }

  def getShards(
    streamName: String
  )(implicit ec: ExecutionContext): Future[Seq[String]] = {
    Future {
      client.describeStream(
        new DescribeStreamRequest()
        .withStreamName(streamName)
      ).getStreamDescription.getShards.asScala.map(_.getShardId)
    }
  }

  def getShardIterator(
    streamName: String,
    shardId: String
  )(implicit ec: ExecutionContext): Future[String] = {
    Future {
      client.getShardIterator(
        new GetShardIteratorRequest()
        .withShardIteratorType(ShardIteratorType.TRIM_HORIZON)
        .withShardId(shardId)
        .withStreamName(streamName)
      ).getShardIterator
    }
  }

}

@javax.inject.Singleton
class MockQueue extends Queue {

  def publish[T: TypeTag](event: JsValue)(implicit ec: ExecutionContext) {
    typeOf[T] match {
      // example:
      // scala> paramInfo(io.flow.common.v0.models.Name(Some("x"), Some("y")))
      // io.flow.common.v0.models.type, class Name, List()
      case TypeRef(packageName, className, args) => {
        val streamName = s"${FlowEnvironment.Current}.${packageName}.${className}"
        val partitionKey = streamName // TODO: Figure out what this should be based on available TypeRef vals
        val data = Json.stringify(event)
        Logger.info(s"insertMessage($streamName, $partitionKey, $data)")
      }
      case _ => {
        Logger.error(s"Could not parse JsValue event: $event")
      }
    }
  }

  def consume[T: TypeTag](f: JsValue => T)(implicit ec: ExecutionContext) {
    typeOf[T] match {
      // example:
      // scala> paramInfo(io.flow.common.v0.models.Name(Some("x"), Some("y")))
      // io.flow.common.v0.models.type, class Name, List()
      case TypeRef(packageName, className, args) => {
        val streamName = s"${FlowEnvironment.Current}.${packageName}.${className}"
        Logger.info(s"Consuming from $streamName")
      }
      case _ => {
        Logger.error(s"Count not consume TypeTag")
      }
    }
  }

}

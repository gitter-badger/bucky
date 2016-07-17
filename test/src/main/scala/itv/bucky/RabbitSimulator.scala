package itv.bucky

import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

import com.typesafe.scalalogging.StrictLogging
import itv.bucky.decl.{Binding, Exchange, Queue}
import itv.contentdelivery.lifecycle.{Lifecycle, NoOpLifecycle}

import scala.collection.concurrent.TrieMap
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.concurrent.{Await, ExecutionContext, Future}

case object IdentityBindings extends Bindings {
  def apply(routingQueue: RoutingKey): QueueName = QueueName(routingQueue.value)

  override def isDefinedAt(key: RoutingKey): Boolean = true
}

/**
  * Provides an AmqpClient implementation that simulates RabbitMQ server with one main difference:
  * Messages are sent directly to the consumer when published, there is no intermediate queue.
  * This makes it easy for tests to publish a message and see the corresponding ConsumeAction, e.g. Ack, Nack or Requeue.
  * Tests can use `RabbitSimulator.watchQueue` to see what messages get published to a queue that the application doesn't consume from.
  *
  * @param bindings A mapping from routing key to queue name, defaults to identity.
  */
class RabbitSimulator(bindings: Bindings = IdentityBindings)(implicit executionContext: ExecutionContext) extends AmqpClient with StrictLogging {

  case class Publication(queueName: QueueName, message: Payload, consumeActionValue: Future[ConsumeAction])

  private val consumers = new scala.collection.mutable.HashMap[QueueName, Handler[Delivery]]()
  private val messagesBeingProcessed: TrieMap[UUID, Publication] = TrieMap.empty
  private val deliveryTag = new AtomicLong()

  def consumer(queueName: QueueName, handler: Handler[Delivery], exceptionalAction: ConsumeAction = DeadLetter)(implicit executionContext: ExecutionContext): Lifecycle[Unit] = NoOpLifecycle {
    val monitorHandler: Handler[Delivery] = delivery => {
      val key = UUID.randomUUID()
      val consumeActionValue = handler(delivery)
      messagesBeingProcessed += key -> Publication(queueName, delivery.body, consumeActionValue)
      consumeActionValue.onComplete { _ =>
        val publication = messagesBeingProcessed(key)
        messagesBeingProcessed -= key
        logger.debug(s"Consume message [${publication.message.unmarshal[String]}] from ${publication.queueName}")
      }
      consumeActionValue
    }
    consumers += (queueName -> monitorHandler)
  }

  def publisher(timeout: Duration = FiniteDuration(10, TimeUnit.SECONDS)): Lifecycle[Publisher[PublishCommand]] =
    NoOpLifecycle[Publisher[PublishCommand]] {
      (command: PublishCommand) => {
        publish(command.body)(command.routingKey).map(_ => ())
      }
    }

  def publish(message: Payload)(routingKey: RoutingKey, headers: Map[String, AnyRef] = Map.empty[String, AnyRef]): Future[ConsumeAction] = {
    logger.debug(s"Publish message [${message.unmarshal[String]}] with $routingKey")
    if (bindings.isDefinedAt(routingKey)) {
      val queueName = bindings(routingKey)
      consumers.get(queueName).fold(Future.failed[ConsumeAction](new RuntimeException(s"No consumers found for $queueName!"))) { handler =>
        handler(Delivery(message, ConsumerTag("ctag"), Envelope(deliveryTag.getAndIncrement(), false, ExchangeName(""), routingKey),
          MessageProperties.persistentBasic.copy(
            headers = headers
          )))
      }
    } else
      Future.failed(new RuntimeException("No queue defined for" + routingKey))
  }

  def watchQueue(queueName: QueueName): ListBuffer[Delivery] = {
    val messages = new ListBuffer[Delivery]()
    this.consumer(queueName, { delivery =>
      messages += delivery
      logger.debug(s"Watch queue consume message [$delivery]")
      Future.successful(Ack)
    })
    messages
  }

  def waitForMessagesToBeProcessed()(implicit timeout: Duration): Unit = {
    Await.result(Future.sequence(messagesBeingProcessed.values.map(_.consumeActionValue)), timeout)
  }

  //FIXME Define the methods
  override def withDeclarations[T](thunk: AmqpOps => T)(implicit executionContext: ExecutionContext): T = thunk(new AmqpOps {
    override def declareExchange(echange: Exchange): Future[Unit] = Future.successful(())

    override def declareQueue(queue: Queue): Future[Unit] = Future.successful(())

    override def bindQueue(binding: Binding): Future[Unit] = Future.successful(())
  })

}

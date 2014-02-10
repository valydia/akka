package akka.streams
package impl

import rx.async.api.{ Producer, Processor }
import rx.async.spi.{ Publisher, Subscription, Subscriber }
import akka.actor.{ Actor, Props }
import Operation._
import akka.streams.ProcessorSettings
import Operation.FromConsumerSink
import Operation.Pipeline

object ProcessorImplementation {
  def operation[I, O](operation: Operation[I, O], settings: ProcessorSettings): Processor[I, O] =
    new OperationProcessor(operation, settings)

  def pipeline(pipeline: Pipeline[_], settings: ProcessorSettings): Unit =
    settings.ctx.actorOf(Props(new PipelineProcessorActor(pipeline)))
}

private class OperationProcessor[I, O](val operation: Operation[I, O], val settings: ProcessorSettings) extends Processor[I, O] {
  def isRunning = running

  val getSubscriber: Subscriber[I] =
    new Subscriber[I] {
      def onSubscribe(subscription: Subscription): Unit = if (running) actor ! OnSubscribed(subscription)
      def onNext(element: I): Unit = if (running) actor ! OnNext(element)
      def onComplete(): Unit = if (running) actor ! OnComplete
      def onError(cause: Throwable): Unit = if (running) actor ! OnError(cause)
    }
  val getPublisher: Publisher[O] =
    new Publisher[O] {
      def subscribe(subscriber: Subscriber[O]): Unit = if (running) actor ! Subscribe(subscriber)
    }

  @volatile protected var running = true
  val actor = settings.ctx.actorOf(Props(new OperationProcessorActor))

  case class OnSubscribed(subscription: Subscription)
  case class OnNext(element: I)
  case object OnComplete
  case class OnError(cause: Throwable)

  case class Subscribe(subscriber: Subscriber[O])
  case class RequestMore(subscriber: Subscriber[O], elements: Int)
  case class CancelSubscription(subscriber: Subscriber[O])

  class OperationProcessorActor extends Actor with WithFanOutBox with ProcessorActorImpl {
    val impl = OperationImpl(UpstreamSideEffects, DownstreamSideEffects, ActorContextEffects, operation)
    var upstream: Subscription = _

    val fanOutBox: FanOutBox = settings.constructFanOutBox()
    def requestNextBatch(): Unit = if (upstream ne null) Effect.run(impl.handleRequestMore(1))
    def allSubscriptionsCancelled(): Unit = context.become(WaitingForDownstream) // or autoUnsubscribe
    def fanOutBoxFinished(): Unit = {} // ignore for now

    def receive = WaitingForUpstream

    def WaitingForUpstream: Receive = {
      case OnSubscribed(subscription) ⇒
        upstream = subscription
        if (hasSubscribers) {
          if (fanOutBox.state == FanOutBox.Ready) requestNextBatch()
          context.become(Running)
        } else context.become(WaitingForDownstream)
      case Subscribe(sub) ⇒
        sub.onSubscribe(newSubscription(sub))
        handleNewSubscription(sub)
      case RequestMore(subscriber, elements) ⇒ handleRequestMore(subscriber, elements)
      case CancelSubscription(subscriber)    ⇒ handleSubscriptionCancelled(subscriber)
    }
    def WaitingForDownstream: Receive = {
      case Subscribe(sub) ⇒
        sub.onSubscribe(newSubscription(sub))
        handleNewSubscription(sub)
        context.become(Running)
    }
    def Running: Receive = {
      case Subscribe(sub) ⇒
        sub.onSubscribe(newSubscription(sub))
        handleNewSubscription(sub)
      case OnNext(element)                   ⇒ Effect.run(impl.handleNext(element))
      case OnComplete                        ⇒ Effect.run(impl.handleComplete())
      case OnError(cause)                    ⇒ Effect.run(impl.handleError(cause))

      case RequestMore(subscriber, elements) ⇒ handleRequestMore(subscriber, elements)
      case CancelSubscription(subscriber)    ⇒ handleSubscriptionCancelled(subscriber)

      case RunDeferred(body)                 ⇒ body()
    }

    object UpstreamSideEffects extends Upstream {
      val requestMore: Int ⇒ Effect = BasicEffects.RequestMoreFromSubscription(upstream, _)
      val cancel: Effect = BasicEffects.CancelSubscription(upstream)
    }
    object DownstreamSideEffects extends Downstream[O] {
      val next: O ⇒ Effect = BasicEffects.SubscriberOnNext(fanOutInput, _)
      val complete: Effect = BasicEffects.SubscriberOnComplete(fanOutInput)
      val error: Throwable ⇒ Effect = BasicEffects.SubscriberOnError(fanOutInput, _)
    }

    def newSubscription(subscriber: Subscriber[O]): Subscription =
      new Subscription {
        def requestMore(elements: Int): Unit = if (running) self ! RequestMore(subscriber, elements)
        def cancel(): Unit = if (running) self ! CancelSubscription(subscriber)
      }
  }
}

class PipelineProcessorActor(pipeline: Pipeline[_]) extends Actor with ProcessorActorImpl {
  Effect.run(OperationImpl(ActorContextEffects, pipeline).start())

  def receive: Receive = {
    case RunDeferred(body) ⇒ body()
  }
}

trait ProcessorActorImpl { _: Actor ⇒
  object ActorContextEffects extends ContextEffects {
    def subscribeTo[O](source: Source[O])(onSubscribe: Upstream ⇒ (SyncSink[O], Effect)): Effect =
      Effect.step(handleSubscribeTo(source, onSubscribe))

    override def subscribeFrom[O](sink: Sink[O])(onSubscribe: (Downstream[O]) ⇒ (SyncSource, Effect)): Effect = {
      val FromConsumerSink(consumer) = sink
      Effect.step {
        case class NextElement(o: O) extends SideEffect {
          def run() = consumer.getSubscriber.onNext(o)
        }
        case object CompleteSink extends SideEffect {
          def run() = consumer.getSubscriber.onComplete()
        }
        case class Error(cause: Throwable) extends SideEffect {
          def run() = consumer.getSubscriber.onError(cause)
        }
        object SinkDownstream extends Downstream[O] {
          val next: O ⇒ Effect = NextElement
          val complete: Effect = CompleteSink
          val error: Throwable ⇒ Effect = Error
        }
        class SubSubscription(source: SyncSource) extends Subscription {
          def requestMore(elements: Int): Unit = runEffectInThisActor(source.handleRequestMore(elements))
          def cancel(): Unit = runEffectInThisActor(source.handleCancel())
        }
        val (handler, effect) = onSubscribe(SinkDownstream)
        consumer.getSubscriber.onSubscribe(new SubSubscription(handler))
        effect
      }
    }
  }

  def handleSubscribeTo[O](source: Source[O], onSubscribeCallback: Upstream ⇒ (SyncSink[O], Effect)): Effect = {
    case class SubUpstream(subscription: Subscription) extends Upstream {
      val requestMore: Int ⇒ Effect = BasicEffects.RequestMoreFromSubscription(subscription, _)
      val cancel: Effect = BasicEffects.CancelSubscription(subscription)
    }

    object SubSubscriber extends Subscriber[O] {
      var subscription: Subscription = _
      var sink: SyncSink[O] = _
      def onSubscribe(subscription: Subscription): Unit = runEffectInThisActor {
        this.subscription = subscription
        val (handler, effect) = onSubscribeCallback(SubUpstream(subscription))
        sink = handler
        effect
      }
      def onNext(element: O): Unit = runEffectInThisActor(sink.handleNext(element))
      def onComplete(): Unit = runEffectInThisActor(sink.handleComplete())
      def onError(cause: Throwable): Unit = runEffectInThisActor(sink.handleError(cause))
    }
    val FromProducerSource(prod: Producer[O]) = source
    prod.getPublisher.subscribe(SubSubscriber)
    Continue // we need to wait for onSubscribe being called
  }

  case class RunDeferred(body: () ⇒ Unit)
  def runInThisActor(body: ⇒ Unit): Unit = self ! RunDeferred(body _)
  def runEffectInThisActor(body: ⇒ Effect): Unit = runInThisActor(Effect.run(body))
}

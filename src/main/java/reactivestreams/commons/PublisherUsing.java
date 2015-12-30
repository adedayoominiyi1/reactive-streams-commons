package reactivestreams.commons;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactivestreams.commons.internal.subscription.EmptySubscription;
import reactivestreams.commons.internal.support.SubscriptionHelper;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Uses a resource, generated by a supplier for each individual Subscriber,
 * while streaming the values from a
 * Publisher derived from the same resource and makes sure the resource is released
 * if the sequence terminates or the Subscriber cancels.
 * <p>
 * <p>
 * Eager resource cleanup happens just before the source termination and exceptions
 * raised by the cleanup Consumer may override the terminal even. Non-eager
 * cleanup will drop any exception.
 *
 * @param <T> the value type streamed
 * @param <S> the resource type
 */
public final class PublisherUsing<T, S> implements Publisher<T> {

    final Supplier<S> resourceSupplier;

    final Function<? super S, ? extends Publisher<? extends T>> sourceFactory;

    final Consumer<? super S> resourceCleanup;

    final boolean eager;

    public PublisherUsing(Supplier<S> resourceSupplier,
                          Function<? super S, ? extends Publisher<? extends T>> sourceFactory, Consumer<? super S>
                                  resourceCleanup,
                          boolean eager) {
        this.resourceSupplier = Objects.requireNonNull(resourceSupplier, "resourceSupplier");
        this.sourceFactory = Objects.requireNonNull(sourceFactory, "sourceFactory");
        this.resourceCleanup = Objects.requireNonNull(resourceCleanup, "resourceCleanup");
        this.eager = eager;
    }

    @Override
    public void subscribe(Subscriber<? super T> s) {
        S resource;

        try {
            resource = resourceSupplier.get();
        } catch (Throwable e) {
            EmptySubscription.error(s, e);
            return;
        }

        Publisher<? extends T> p;

        try {
            p = sourceFactory.apply(resource);
        } catch (Throwable e) {

            try {
                resourceCleanup.accept(resource);
            } catch (Throwable ex) {
                ex.addSuppressed(e);
                e = ex;
            }

            EmptySubscription.error(s, e);
            return;
        }

        if (p == null) {
            Throwable e = new NullPointerException("The sourceFactory returned a null value");
            try {
                resourceCleanup.accept(resource);
            } catch (Throwable ex) {
                ex.addSuppressed(e);
                e = ex;
            }

            EmptySubscription.error(s, e);
            return;
        }

        p.subscribe(new PublisherUsingSubscriber<>(s, resourceCleanup, resource, eager));
    }

    static final class PublisherUsingSubscriber<T, S>
      implements Subscriber<T>, Subscription {

        final Subscriber<? super T> actual;

        final Consumer<? super S> resourceCleanup;

        final S resource;

        final boolean eager;

        Subscription s;

        volatile int wip;
        @SuppressWarnings("rawtypes")
        static final AtomicIntegerFieldUpdater<PublisherUsingSubscriber> WIP =
          AtomicIntegerFieldUpdater.newUpdater(PublisherUsingSubscriber.class, "wip");

        public PublisherUsingSubscriber(Subscriber<? super T> actual, Consumer<? super S> resourceCleanup, S
                resource, boolean eager) {
            this.actual = actual;
            this.resourceCleanup = resourceCleanup;
            this.resource = resource;
            this.eager = eager;
        }

        @Override
        public void request(long n) {
            s.request(n);
        }

        @Override
        public void cancel() {
            if (WIP.compareAndSet(this, 0, 1)) {
                s.cancel();

                cleanup();
            }
        }

        void cleanup() {
            try {
                resourceCleanup.accept(resource);
            } catch (Throwable e) {
                // FIXME nowhere to go
            }
        }

        @Override
        public void onSubscribe(Subscription s) {
            if (SubscriptionHelper.validate(this.s, s)) {
                this.s = s;

                actual.onSubscribe(this);
            }
        }

        @Override
        public void onNext(T t) {
            actual.onNext(t);
        }

        @Override
        public void onError(Throwable t) {
            if (eager) {
                try {
                    resourceCleanup.accept(resource);
                } catch (Throwable e) {
                    e.addSuppressed(t);
                    t = e;
                }
            }

            actual.onError(t);

            if (!eager) {
                cleanup();
            }
        }

        @Override
        public void onComplete() {
            if (eager) {
                try {
                    resourceCleanup.accept(resource);
                } catch (Throwable e) {
                    actual.onError(e);
                    return;
                }
            }

            actual.onComplete();

            if (!eager) {
                cleanup();
            }
        }
    }
}

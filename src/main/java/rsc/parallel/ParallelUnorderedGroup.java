package rsc.parallel;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import rsc.flow.Fuseable;
import rsc.publisher.GroupedPublisher;
import rsc.publisher.PublisherArray;
import rsc.publisher.Px;
import rsc.util.BackpressureHelper;
import rsc.util.EmptySubscription;
import rsc.util.SubscriptionHelper;

/**
 * Exposes the 'rails' as individual GroupedPublisher instances, keyed by the rail index (zero based).
 * <p>
 * Each group can be consumed only once; requests and cancellation compose through. Note
 * that cancelling only one rail may result in undefined behavior.
 * 
 * @param <T> the value type
 */
public final class ParallelUnorderedGroup<T> extends Px<GroupedPublisher<Integer, T>> implements Fuseable {

    final ParallelPublisher<? extends T> source;

    public ParallelUnorderedGroup(ParallelPublisher<? extends T> source) {
        this.source = source;
    }
    
    @Override
    public void subscribe(Subscriber<? super GroupedPublisher<Integer, T>> s) {
        int n = source.parallelism();
        
        @SuppressWarnings("unchecked")
        ParallelGroup<T>[] groups = new ParallelGroup[n];
        
        for (int i = 0; i < n; i++) {
            groups[i] = new ParallelGroup<>(i);
        }
        
        PublisherArray.subscribe(s, groups);
        
        source.subscribe(groups);
    }
    
    static final class ParallelGroup<T> extends GroupedPublisher<Integer, T> 
    implements Subscriber<T>, Subscription {
        final int key;
        
        volatile int once;
        @SuppressWarnings("rawtypes")
        static final AtomicIntegerFieldUpdater<ParallelGroup> ONCE =
                AtomicIntegerFieldUpdater.newUpdater(ParallelGroup.class, "once");
        
        volatile Subscription s;
        @SuppressWarnings("rawtypes")
        static final AtomicReferenceFieldUpdater<ParallelGroup, Subscription> S =
                AtomicReferenceFieldUpdater.newUpdater(ParallelGroup.class, Subscription.class, "s");
        
        volatile long requested;
        @SuppressWarnings("rawtypes")
        static final AtomicLongFieldUpdater<ParallelGroup> REQUESTED =
                AtomicLongFieldUpdater.newUpdater(ParallelGroup.class, "requested");
        
        Subscriber<? super T> actual;
        
        public ParallelGroup(int key) {
            this.key = key;
        }
        
        @Override
        public Integer key() {
            return key;
        }
        
        @Override
        public void subscribe(Subscriber<? super T> s) {
            if (ONCE.compareAndSet(this, 0, 1)) {
                this.actual = s;
                s.onSubscribe(this);
            } else {
                EmptySubscription.error(s, new IllegalStateException("This ParallelGroup can be subscribed to at most once."));
            }
        }
        
        @Override
        public void onSubscribe(Subscription s) {
            if (SubscriptionHelper.setOnce(S, this, s)) {
                long r = REQUESTED.getAndSet(this, 0L);
                if (r != 0L) {
                    s.request(r);
                }
            }
        }
        
        @Override
        public void onNext(T t) {
            actual.onNext(t);
        }
        
        @Override
        public void onError(Throwable t) {
            actual.onError(t);
        }
        
        @Override
        public void onComplete() {
            actual.onComplete();
        }
        
        @Override
        public void request(long n) {
            if (SubscriptionHelper.validate(n)) {
                Subscription a = s;
                if (a == null) {
                    BackpressureHelper.getAndAddCap(REQUESTED, this, n);
                    
                    a = s;
                    if (a != null) {
                        long r = REQUESTED.getAndSet(this, 0L);
                        if (r != 0L) {
                            a.request(n);
                        }
                    }
                } else {
                    a.request(n);
                }
            }
        }
        
        @Override
        public void cancel() {
            SubscriptionHelper.terminate(S, this);
        }
    }
}
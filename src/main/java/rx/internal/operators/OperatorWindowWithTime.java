/**
 * Copyright 2014 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package rx.internal.operators;

import java.util.*;
import java.util.concurrent.TimeUnit;

import rx.*;
import rx.Observable;
import rx.Observable.Operator;
import rx.Observer;
import rx.Scheduler.Worker;
import rx.functions.Action0;
import rx.observers.*;
import rx.subjects.UnicastSubject;
import rx.subscriptions.Subscriptions;

/**
 * Creates windows of values into the source sequence with timed window creation, length and size bounds.
 * If timespan == timeshift, windows are non-overlapping but always continuous, i.e., when the size bound is reached, a new
 * window is opened.
 *
 * <p>Note that this conforms the Rx.NET behavior, but does not match former RxJava
 * behavior, which operated as a regular buffer and mapped its lists to Observables.</p>
 *
 * @param <T> the value type
 */
public final class OperatorWindowWithTime<T> implements Operator<Observable<T>, T> {
    /** Length of each window. */
    final long timespan;
    /** Period of creating new windows. */
    final long timeshift;
    final TimeUnit unit;
    final Scheduler scheduler;
    final int size;
    /** Indicate the current subject should complete and a new subject be emitted. */
    static final Object NEXT_SUBJECT = new Object();
    /** For error and completion indication. */
    static final NotificationLite<Object> NL = NotificationLite.instance();
    
    
    public OperatorWindowWithTime(long timespan, long timeshift, TimeUnit unit, int size, Scheduler scheduler) {
        this.timespan = timespan;
        this.timeshift = timeshift;
        this.unit = unit;
        this.size = size;
        this.scheduler = scheduler;
    }
    
    
    @Override
    public Subscriber<? super T> call(Subscriber<? super Observable<T>> child) {
        Worker worker = scheduler.createWorker();
        
        if (timespan == timeshift) {
            ExactSubscriber s = new ExactSubscriber(child, worker);
            s.add(worker);
            s.scheduleExact();
            return s;
        }
        
        InexactSubscriber s = new InexactSubscriber(child, worker);
        s.add(worker);
        s.startNewChunk();
        s.scheduleChunk();
        return s;
    }
    /** The immutable windowing state with one subject. */
    static final class State<T> {
        final Observer<T> consumer;
        final Observable<T> producer;
        final int count;
        static final State<Object> EMPTY = new State<Object>(null, null, 0);
        
        public State(Observer<T> consumer, Observable<T> producer, int count) {
            this.consumer = consumer;
            this.producer = producer;
            this.count = count;
        }
        public State<T> next() {
            return new State<T>(consumer, producer, count + 1);
        }
        public State<T> create(Observer<T> consumer, Observable<T> producer) {
            return new State<T>(consumer, producer, 0);
        }
        public State<T> clear() {
            return empty();
        }
        @SuppressWarnings("unchecked")
        public static <T> State<T> empty() {
            return (State<T>)EMPTY;
        }
    }
    /** Subscriber with exact, non-overlapping windows. */
    final class ExactSubscriber extends Subscriber<T> {
        final Subscriber<? super Observable<T>> child;
        final Worker worker;
        final Object guard;
        /** Guarded by guard. */
        List<Object> queue;
        /** Guarded by guard. */
        boolean emitting;
        volatile State<T> state;
        
        public ExactSubscriber(Subscriber<? super Observable<T>> child, Worker worker) {
            this.child = new SerializedSubscriber<Observable<T>>(child);
            this.worker = worker;
            this.guard = new Object();
            this.state = State.empty();
            child.add(Subscriptions.create(new Action0() {
                @Override
                public void call() {
                    // if there is no active window, unsubscribe the upstream
                    if (state.consumer == null) {
                        unsubscribe();
                    }
                }
            }));
        }
        
        @Override
        public void onStart() {
            request(Long.MAX_VALUE);
        }
        
        @Override
        public void onNext(T t) {
            synchronized (guard) {
                if (emitting) {
                    if (queue == null) {
                        queue = new ArrayList<Object>();
                    }
                    queue.add(t);
                    return;
                }
                emitting = true;
            }
            boolean skipFinal = false;
            try {
                if (!emitValue(t)) {
                    return;
                }

                for (;;) {
                    List<Object> localQueue;
                    synchronized (guard) {
                        localQueue = queue;
                        if (localQueue == null) {
                            emitting = false;
                            skipFinal = true;
                            return;
                        }
                        queue = null;
                    }
                    if (!drain(localQueue)) {
                        return;
                    }
                }
            } finally {
                if (!skipFinal) {
                    synchronized (guard) {
                        emitting = false;
                    }
                }
            }
        }
        boolean drain(List<Object> queue) {
            if (queue == null) {
                return true;
            }
            for (Object o : queue) {
                if (o == NEXT_SUBJECT) {
                    if (!replaceSubject()) {
                        return false;
                    }
                } else
                if (NL.isError(o)) {
                    error(NL.getError(o));
                    break;
                } else
                if (NL.isCompleted(o)) {
                    complete();
                    break;
                } else {
                    @SuppressWarnings("unchecked")
                    T t = (T)o;
                    if (!emitValue(t)) {
                        return false;
                    }
                }
            }
            return true;
        }
        boolean replaceSubject() {
            Observer<T> s = state.consumer;
            if (s != null) {
                s.onCompleted();
            }
            // if child has unsubscribed, unsubscribe upstream instead of opening a new window
            if (child.isUnsubscribed()) {
                state = state.clear();
                unsubscribe();
                return false;
            }
            UnicastSubject<T> bus = UnicastSubject.create();
            state = state.create(bus, bus);
            child.onNext(bus);
            return true;
        }
        boolean emitValue(T t) {
            State<T> s = state;
            if (s.consumer == null) {
                if (!replaceSubject()) {
                    return false;
                }
                s = state;
            }
            s.consumer.onNext(t);
            if (s.count == size - 1) {
                s.consumer.onCompleted();
                s = s.clear();
            } else {
                s = s.next();
            }
            state = s;
            return true;
        }
        
        @Override
        public void onError(Throwable e) {
            synchronized (guard) {
                if (emitting) {
                    // drop any queued action and terminate asap
                    queue = Collections.<Object>singletonList(NL.error(e));
                    return;
                }
                queue = null;
                emitting = true;
            }
            error(e);
        }
        void error(Throwable e) {
            Observer<T> s = state.consumer;
            state = state.clear();
            if (s != null) {
                s.onError(e);
            }
            child.onError(e);
            unsubscribe();
        }
        void complete() {
            Observer<T> s = state.consumer;
            state = state.clear();
            if (s != null) {
                s.onCompleted();
            }
            child.onCompleted();
            unsubscribe();
        }
        @Override
        public void onCompleted() {
            List<Object> localQueue;
            synchronized (guard) {
                if (emitting) {
                    if (queue == null) {
                        queue = new ArrayList<Object>();
                    }
                    queue.add(NL.completed());
                    return;
                }
                localQueue = queue;
                queue = null;
                emitting = true;
            }
            try {
                drain(localQueue);
            } catch (Throwable e) {
                error(e);
                return;
            }
            complete();
        }
        
        void scheduleExact() {
            worker.schedulePeriodically(new Action0() {
                
                @Override
                public void call() {
                    nextWindow();
                }
                
            }, 0, timespan, unit);
        }
        void nextWindow() {
            synchronized (guard) {
                if (emitting) {
                    if (queue == null) {
                        queue = new ArrayList<Object>();
                    }
                    queue.add(NEXT_SUBJECT);
                    return;
                }
                emitting = true;
            }
            boolean skipFinal = false;
            try {
                if (!replaceSubject()) {
                    return;
                }
                for (;;) {
                    List<Object> localQueue;
                    synchronized (guard) {
                        localQueue = queue;
                        if (localQueue == null) {
                            emitting = false;
                            skipFinal = true;
                            return;
                        }
                        queue = null;
                    }
                    
                    if (!drain(localQueue)) {
                        return;
                    }
                }
            } finally {
                if (!skipFinal) {
                    synchronized (guard) {
                        emitting = false;
                    }
                }
            }
        }
    }
    /** 
     * Record to store the subject and the emission count. 
     * @param <T> the subject's in-out type
     */
    static final class CountedSerializedSubject<T> {
        final Observer<T> consumer;
        final Observable<T> producer;
        int count;

        public CountedSerializedSubject(Observer<T> consumer, Observable<T> producer) {
            this.consumer = new SerializedObserver<T>(consumer);
            this.producer = producer;
        }
    }
    /** Subscriber with inexact, potentially overlapping or discontinuous windows. */
    final class InexactSubscriber extends Subscriber<T> {
        final Subscriber<? super Observable<T>> child;
        final Worker worker;
        final Object guard;
        /** Guarded by this. */
        final List<CountedSerializedSubject<T>> chunks;
        /** Guarded by this. */
        boolean done;
        public InexactSubscriber(Subscriber<? super Observable<T>> child, Worker worker) {
            super(child);
            this.child = child;
            this.worker = worker;
            this.guard = new Object();
            this.chunks = new LinkedList<CountedSerializedSubject<T>>();
        }

        @Override
        public void onStart() {
            request(Long.MAX_VALUE);
        }
        
        @Override
        public void onNext(T t) {
            List<CountedSerializedSubject<T>> list;
            synchronized (guard) {
                if (done) {
                    return;
                }
                list = new ArrayList<CountedSerializedSubject<T>>(chunks);
                Iterator<CountedSerializedSubject<T>> it = chunks.iterator();
                while (it.hasNext()) {
                    CountedSerializedSubject<T> cs = it.next();
                    if (++cs.count == size) {
                        it.remove();
                    }
                }
            }
            for (CountedSerializedSubject<T> cs : list) {
                cs.consumer.onNext(t);
                if (cs.count == size) {
                    cs.consumer.onCompleted();
                }
            }
        }

        @Override
        public void onError(Throwable e) {
            List<CountedSerializedSubject<T>> list;
            synchronized (guard) {
                if (done) {
                    return;
                }
                done = true;
                list = new ArrayList<CountedSerializedSubject<T>>(chunks);
                chunks.clear();
            }
            for (CountedSerializedSubject<T> cs : list) {
                cs.consumer.onError(e);
            }
            child.onError(e);
        }

        @Override
        public void onCompleted() {
            List<CountedSerializedSubject<T>> list;
            synchronized (guard) {
                if (done) {
                    return;
                }
                done = true;
                list = new ArrayList<CountedSerializedSubject<T>>(chunks);
                chunks.clear();
            }
            for (CountedSerializedSubject<T> cs : list) {
                cs.consumer.onCompleted();
            }
            child.onCompleted();
        }
        void scheduleChunk() {
            worker.schedulePeriodically(new Action0() {

                @Override
                public void call() {
                    startNewChunk();
                }
                
            }, timeshift, timeshift, unit);
        }
        void startNewChunk() {
            final CountedSerializedSubject<T> chunk = createCountedSerializedSubject();
            synchronized (guard) {
                if (done) {
                    return;
                }
                chunks.add(chunk);
            }
            try {
                child.onNext(chunk.producer);
            } catch (Throwable e) {
                onError(e);
                return;
            }
            
            worker.schedule(new Action0() {

                @Override
                public void call() {
                    terminateChunk(chunk);
                }
                
            }, timespan, unit);
        }
        void terminateChunk(CountedSerializedSubject<T> chunk) {
            boolean terminate = false;
            synchronized (guard) {
                if (done) {
                    return;
                }
                Iterator<CountedSerializedSubject<T>> it = chunks.iterator();
                while (it.hasNext()) {
                    CountedSerializedSubject<T> cs = it.next();
                    if (cs == chunk) {
                        terminate = true;
                        it.remove();
                        break;
                    }
                }
            }
            if (terminate) {
                chunk.consumer.onCompleted();
            }
        }
        CountedSerializedSubject<T> createCountedSerializedSubject() {
            UnicastSubject<T> bus = UnicastSubject.create();
            return new CountedSerializedSubject<T>(bus, bus);
        }
    }
}

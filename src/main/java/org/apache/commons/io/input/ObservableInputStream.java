/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.commons.io.input;

import static org.apache.commons.io.IOUtils.EOF;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.commons.io.IOUtils;
import org.apache.commons.io.function.IOConsumer;

/**
 * The {@link ObservableInputStream} allows, that an InputStream may be consumed by other receivers, apart from the
 * thread, which is reading it. The other consumers are implemented as instances of {@link Observer}.
 * <p>
 * A typical application may be the generation of a {@link java.security.MessageDigest} on the fly.
 * </p>
 * <h2>Observer notification strategy</h2>
 * <p>
 * This class applies an explicit, deterministic strategy when notifying observers:
 * </p>
 * <ul>
 * <li><em>State first, callbacks second</em>: The internal state for an event (data read, EOF reached, stream closed)
 * is settled before any observer callback runs, so callbacks may safely re-enter state queries like
 * {@link #getObservers()} or {@link #isFinished()} without deadlocking or observing a half-updated stream.</li>
 * <li><em>Original failure wins</em>: If the underlying read or close fails, that original {@link IOException} is the
 * one propagated to the caller. Failures thrown by observers for the same event are aggregated as independent
 * {@linkplain Throwable#addSuppressed(Throwable) suppressed} causes on the propagated exception, so both the
 * underlying failure and every observer failure remain queryable.</li>
 * <li><em>All observers are notified</em>: A failing observer does not prevent the remaining observers from being
 * notified of the same event; their failures are collected and reported together.</li>
 * <li><em>EOF is not close</em>: EOF is signaled exactly once through {@link Observer#finished()} when the first EOF
 * is read; closing the stream is signaled separately through {@link Observer#closed()}. Reaching EOF never implies a
 * close notification, and closing never implies an EOF notification.</li>
 * <li><em>Each read is delivered once</em>: The bytes of every individual read are passed to each registered observer
 * exactly once.</li>
 * <li><em>Deterministic removal boundary</em>: {@link #remove(Observer)} and {@link #removeAllObservers()} take effect
 * with the next event. A notification round already in progress is completed against a snapshot of the observers
 * registered when that event started.</li>
 * <li><em>The proxied stream is never closed by observer failures</em>: An observer throwing from a read, EOF, or
 * error callback never causes the underlying ("borrowed") stream to be closed as a side effect.</li>
 * </ul>
 * <p>
 * <em>Note</em>: The {@link ObservableInputStream} is <em>not</em> thread safe, as instances of InputStream usually
 * aren't. If you must access the stream from multiple threads, then synchronization, locking, or a similar means must
 * be used.
 * </p>
 *
 * @see MessageDigestInputStream
 */
public class ObservableInputStream extends ProxyInputStream {

    /**
     * For subclassing builders from {@link BoundedInputStream} subclassses.
     *
     * @param <T> The subclass.
     * @since 2.18.0
     */
    public abstract static class AbstractBuilder<T extends AbstractBuilder<T>> extends ProxyInputStream.AbstractBuilder<ObservableInputStream, T> {

        private List<Observer> observers;

        /**
         * Constructs a new instance for subclasses.
         */
        public AbstractBuilder() {
            // empty
        }

        /**
         * Sets the list of observer callbacks.
         *
         * @param observers The list of observer callbacks.
         */
        public void setObservers(final List<Observer> observers) {
            this.observers = observers;
        }

    }

    /**
     * Builds instances of {@link ObservableInputStream}.
     *
     * @since 2.18.0
     */
    public static class Builder extends AbstractBuilder<Builder> {

        /**
         * Constructs a new builder of {@link ObservableInputStream}.
         */
        public Builder() {
            // empty
        }

        @Override
        public ObservableInputStream get() throws IOException {
            return new ObservableInputStream(this);
        }

    }

    /**
     * Abstracts observer callback for {@link ObservableInputStream}s.
     */
    public abstract static class Observer {

        /**
         * Constructs a new instance for subclasses.
         */
        public Observer() {
            // empty
        }

        /**
         * Called to indicate that the {@link ObservableInputStream} has been closed.
         *
         * @throws IOException Thrown if an I/O error occurs.
         */
        @SuppressWarnings("unused") // Possibly thrown from subclasses.
        public void closed() throws IOException {
            // noop
        }

        /**
         * Called to indicate that {@link InputStream#read(byte[])}, or {@link InputStream#read(byte[], int, int)} have
         * been called, and are about to invoke data.
         *
         * @param buffer The byte array, which has been passed to the read call, and where data has been stored.
         * @param offset The offset within the byte array, where data has been stored.
         * @param length The number of bytes, which have been stored in the byte array.
         * @throws IOException Thrown if an I/O error occurs.
         */
        @SuppressWarnings("unused") // Possibly thrown from subclasses.
        public void data(final byte[] buffer, final int offset, final int length) throws IOException {
            // noop
        }

        /**
         * Called to indicate, that {@link InputStream#read()} has been invoked on the {@link ObservableInputStream},
         * and will return a value.
         *
         * @param value The value, which is being returned. This will never be -1 (EOF), because, in that case,
         *        {@link #finished()} will be invoked instead.
         * @throws IOException Thrown if an I/O error occurs.
         */
        @SuppressWarnings("unused") // Possibly thrown from subclasses.
        public void data(final int value) throws IOException {
            // noop
        }

        /**
         * Called to indicate that an error occurred on the underlying stream.
         *
         * @param exception The exception to throw.
         * @throws IOException Thrown if an I/O error occurs.
         */
        public void error(final IOException exception) throws IOException {
            throw exception;
        }

        /**
         * Called to indicate that EOF has been seen on the underlying stream. This method is called at most once,
         * when the first EOF is read; subsequent reads that keep returning EOF do not trigger it again. EOF is
         * independent of {@link #closed()}, which is only signaled by {@link ObservableInputStream#close()}.
         *
         * @throws IOException Thrown if an I/O error occurs.
         */
        @SuppressWarnings("unused") // Possibly thrown from subclasses.
        public void finished() throws IOException {
            // noop
        }
    }

    private final List<Observer> observers;

    /**
     * Whether {@link Observer#closed()} has already been signaled; guards the once-only close notification.
     */
    private boolean closedNotified;

    /**
     * Whether {@link Observer#finished()} has already been signaled; guards the once-only EOF notification.
     */
    private boolean finishedNotified;

    private static List<Observer> observerList(final List<Observer> observers) {
        // A CopyOnWriteArrayList gives every notification round a stable snapshot: observers removed or added while
        // an event is being dispatched only affect subsequent events, and callbacks never see a
        // ConcurrentModificationException. No lock is held while observers run, so callbacks may re-enter
        // state queries (getObservers(), isFinished(), ...) without deadlocking.
        return new CopyOnWriteArrayList<>(observers != null ? observers : Collections.emptyList());
    }

    ObservableInputStream(final AbstractBuilder<?> builder) throws IOException {
        super(builder);
        this.observers = observerList(builder.observers);
    }

    /**
     * Constructs a new ObservableInputStream for the given InputStream.
     *
     * @param inputStream The input stream to observe.
     */
    public ObservableInputStream(final InputStream inputStream) {
        this(inputStream, new ArrayList<>());
    }

    /**
     * Constructs a new ObservableInputStream for the given InputStream.
     *
     * @param inputStream The input stream to observe.
     * @param observers List of observer callbacks.
     */
    private ObservableInputStream(final InputStream inputStream, final List<Observer> observers) {
        super(inputStream);
        this.observers = observerList(observers);
    }

    /**
     * Constructs a new ObservableInputStream for the given InputStream.
     *
     * @param inputStream The input stream to observe.
     * @param observers List of observer callbacks.
     * @since 2.9.0
     */
    public ObservableInputStream(final InputStream inputStream, final Observer... observers) {
        this(inputStream, Arrays.asList(observers));
    }

    /**
     * Adds an Observer.
     *
     * @param observer The observer to add.
     */
    public void add(final Observer observer) {
        observers.add(observer);
    }

    @Override
    public void close() throws IOException {
        IOException ioe = null;
        try {
            super.close();
        } catch (final IOException e) {
            ioe = e;
        }
        if (ioe == null) {
            noteClosed();
        } else {
            // The original close failure stays primary; observer failures are aggregated on it as suppressed causes.
            noteError(ioe);
        }
    }

    /**
     * Reads all data from the underlying {@link InputStream}, while notifying the observers.
     *
     * @throws IOException The underlying {@link InputStream}, or either of the observers has thrown an exception.
     */
    public void consume() throws IOException {
        IOUtils.consume(this);
    }

    /**
     * Notifies every observer registered for the current event, even if some of them fail.
     * <p>
     * Iteration runs over a snapshot of the observers taken when the event started, so each observer receives the
     * event exactly once and removals or additions made by callbacks only affect subsequent events. If one or more
     * observers fail, the first failure is thrown with the remaining failures attached as independent
     * {@linkplain Throwable#addSuppressed(Throwable) suppressed} causes.
     * </p>
     *
     * @param action The callback to invoke on each observer.
     * @throws IOException The first observer failure, with any further failures suppressed on it.
     */
    private void forEachObserver(final IOConsumer<Observer> action) throws IOException {
        List<IOException> failures = null;
        for (final Observer observer : observers) {
            try {
                action.accept(observer);
            } catch (final IOException e) {
                if (failures == null) {
                    failures = new ArrayList<>();
                }
                failures.add(e);
            }
        }
        if (failures != null) {
            final IOException first = failures.get(0);
            for (int i = 1; i < failures.size(); i++) {
                first.addSuppressed(failures.get(i));
            }
            throw first;
        }
    }

    /**
     * Gets a copy of currently registered observers.
     *
     * @return A copy of the list of currently registered observers.
     * @since 2.9.0
     */
    public List<Observer> getObservers() {
        return new ArrayList<>(observers);
    }

    /**
     * Tests whether EOF has been reached and observers have been notified through {@link Observer#finished()}.
     * <p>
     * The flag is settled before observer callbacks run, so it may be queried reentrantly from within any observer
     * callback.
     * </p>
     *
     * @return whether EOF has been observed on the underlying stream.
     * @since 2.23.0
     */
    public boolean isFinished() {
        return finishedNotified;
    }

    /**
     * Notifies the observers by invoking {@link Observer#closed()}. The notification happens at most once, and the
     * internal state is settled before any callback runs.
     *
     * @throws IOException Some observer has thrown an exception, which is being passed down. If several observers
     *         fail, the first failure is thrown with the remaining failures suppressed on it.
     */
    protected void noteClosed() throws IOException {
        if (closedNotified) {
            return;
        }
        closedNotified = true;
        forEachObserver(Observer::closed);
    }

    /**
     * Notifies the observers by invoking {@link Observer#data(int)} with the given arguments.
     *
     * @param value Passed to the observers.
     * @throws IOException Some observer has thrown an exception, which is being passed down.
     */
    protected void noteDataByte(final int value) throws IOException {
        forEachObserver(observer -> observer.data(value));
    }

    /**
     * Notifies the observers by invoking {@link Observer#data(byte[],int,int)} with the given arguments.
     *
     * @param buffer Passed to the observers.
     * @param offset Passed to the observers.
     * @param length Passed to the observers.
     * @throws IOException Some observer has thrown an exception, which is being passed down.
     */
    protected void noteDataBytes(final byte[] buffer, final int offset, final int length) throws IOException {
        forEachObserver(observer -> observer.data(buffer, offset, length));
    }

    /**
     * Notifies the observers by invoking {@link Observer#error(IOException)} with the given argument.
     * <p>
     * The given exception stays the primary failure: if any observer fails while handling it, every distinct observer
     * failure is aggregated on the given exception as an independent
     * {@linkplain Throwable#addSuppressed(Throwable) suppressed} cause, and the given exception is thrown. Both the
     * original failure and all observer failures therefore remain queryable by the caller.
     * </p>
     *
     * @param exception Passed to the observers.
     * @throws IOException The given exception, if at least one observer has thrown an exception while handling it.
     *         This may be the same exception, which has been passed as an argument.
     */
    protected void noteError(final IOException exception) throws IOException {
        boolean observerFailed = false;
        List<IOException> suppressed = null;
        for (final Observer observer : observers) {
            try {
                observer.error(exception);
            } catch (final IOException e) {
                observerFailed = true;
                if (e != exception) {
                    // The default Observer.error(IOException) rethrows the passed exception; only genuinely
                    // independent observer failures are aggregated as suppressed causes.
                    if (suppressed == null) {
                        suppressed = new ArrayList<>();
                    }
                    suppressed.add(e);
                }
            }
        }
        if (observerFailed) {
            if (suppressed != null) {
                suppressed.forEach(exception::addSuppressed);
            }
            throw exception;
        }
    }

    /**
     * Notifies the observers by invoking {@link Observer#finished()}. The notification happens at most once, when the
     * first EOF is read, and the internal state is settled before any callback runs. EOF is never conflated with
     * {@link Observer#closed()}, which is only signaled by {@link #close()}.
     *
     * @throws IOException Some observer has thrown an exception, which is being passed down. If several observers
     *         fail, the first failure is thrown with the remaining failures suppressed on it.
     */
    protected void noteFinished() throws IOException {
        if (finishedNotified) {
            return;
        }
        finishedNotified = true;
        forEachObserver(Observer::finished);
    }

    private void notify(final byte[] buffer, final int offset, final int result, final IOException ioe) throws IOException {
        if (ioe != null) {
            noteError(ioe);
            throw ioe;
        }
        if (result == EOF) {
            noteFinished();
        } else if (result > 0) {
            noteDataBytes(buffer, offset, result);
        }
    }

    @Override
    public int read() throws IOException {
        int result = 0;
        IOException ioe = null;
        try {
            result = super.read();
        } catch (final IOException ex) {
            ioe = ex;
        }
        if (ioe != null) {
            noteError(ioe);
            throw ioe;
        }
        if (result == EOF) {
            noteFinished();
        } else {
            noteDataByte(result);
        }
        return result;
    }

    @Override
    public int read(final byte[] buffer) throws IOException {
        int result = 0;
        IOException ioe = null;
        try {
            result = super.read(buffer);
        } catch (final IOException ex) {
            ioe = ex;
        }
        notify(buffer, 0, result, ioe);
        return result;
    }

    @Override
    public int read(final byte[] buffer, final int offset, final int length) throws IOException {
        int result = 0;
        IOException ioe = null;
        try {
            result = super.read(buffer, offset, length);
        } catch (final IOException ex) {
            ioe = ex;
        }
        notify(buffer, offset, result, ioe);
        return result;
    }

    /**
     * Removes an Observer.
     * <p>
     * The removal has a deterministic boundary: it takes effect with the next event. A notification round that is
     * already in progress is completed against the snapshot of observers taken when that event started, so the
     * removed observer still receives the remainder of the current event, if any.
     * </p>
     *
     * @param observer The observer to remove.
     */
    public void remove(final Observer observer) {
        observers.remove(observer);
    }

    /**
     * Removes all Observers.
     * <p>
     * Like {@link #remove(Observer)}, the removal takes effect with the next event; a notification round already in
     * progress is completed against its snapshot.
     * </p>
     */
    public void removeAllObservers() {
        observers.clear();
    }

}

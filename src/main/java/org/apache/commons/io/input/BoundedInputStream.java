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

import org.apache.commons.io.IOUtils;
import org.apache.commons.io.function.IOBiConsumer;

//@formatter:off
/**
 * Reads bytes up to a maximum count and stops once reached.
 * <p>
 * To build an instance: Use the {@link #builder()} to access all features.
 * </p>
 * <p>
 * By default, a {@link BoundedInputStream} is <em>unbound</em>; so make sure to call {@link AbstractBuilder#setMaxCount(long)}.
 * </p>
 * <p>
 * You can find out how many bytes this stream has seen so far by calling {@link BoundedInputStream#getCount()}. This value reflects bytes read and skipped.
 * </p>
 * <h2>Using a ServletInputStream</h2>
 * <p>
 * A {@code ServletInputStream} can block if you try to read content that isn't there
 * because it doesn't know whether the content hasn't arrived yet or whether the content has finished. Initialize an {@link BoundedInputStream} with the
 * {@code Content-Length} sent in the {@code ServletInputStream}'s header, this stop it from blocking, providing it's been sent with a correct content
 * length in the first place.
 * </p>
 * <h2>Using NIO</h2>
 * <pre>{@code
 * BoundedInputStream s = BoundedInputStream.builder()
 *   .setPath(Paths.get("MyFile.xml"))
 *   .setMaxCount(1024)
 *   .setPropagateClose(false)
 *   .get();
 * }
 * </pre>
 * <h2>Using IO</h2>
 * <pre>{@code
 * BoundedInputStream s = BoundedInputStream.builder()
 *   .setFile(new File("MyFile.xml"))
 *   .setMaxCount(1024)
 *   .setPropagateClose(false)
 *   .get();
 * }
 * </pre>
 * <h2>Counting Bytes</h2>
 * <p>You can set the running count when building, which is most useful when starting from another stream:
 * <pre>{@code
 * InputStream in = ...;
 * BoundedInputStream s = BoundedInputStream.builder()
 *   .setInputStream(in)
 *   .setCount(12)
 *   .setMaxCount(1024)
 *   .setPropagateClose(false)
 *   .get();
 * }
 * </pre>
 * <h2>Listening for the maximum count reached</h2>
 * <pre>{@code
 * BoundedInputStream s = BoundedInputStream.builder()
 *   .setPath(Paths.get("MyFile.xml"))
 *   .setMaxCount(1024)
 *   .setOnMaxCount((max, count) -> System.out.printf("Maximum count %,d reached with a last read count of %,d%n", max, count))
 *   .get();
 * }
 * </pre>
 * <h2>Retaining the bytes past the limit</h2>
 * <p>
 * By default, this stream simply reports end of file once the limit is reached and any extra bytes stay in the underlying stream. When you need to tell
 * apart "the limit was reached" from "the underlying stream ended", set {@link Builder#setRetainRemaining(boolean)} to {@code true}. In this mode, the
 * first read blocked by the limit probes a single extra byte from the underlying stream: {@link #isOverLimit()} then reports {@code true}, and the
 * probed byte (followed by the rest of the underlying stream) is available through {@link #getRemainingStream()}. Closing this stream never closes the
 * borrowed underlying stream in this mode.
 * </p>
 * <pre>{@code
 * InputStream in = ...;
 * BoundedInputStream s = BoundedInputStream.builder()
 *   .setInputStream(in)
 *   .setMaxCount(3)
 *   .setRetainRemaining(true)
 *   .get();
 * byte[] head = IOUtils.toByteArray(s); // at most 3 bytes
 * if (s.isOverLimit()) {
 *   try (InputStream tail = s.getRemainingStream()) {
 *     // 'tail' starts with the byte that was probed while detecting the overflow.
 *   }
 * }
 * }
 * </pre>
 *
 * @see Builder
 * @since 2.0
 */
//@formatter:on
public class BoundedInputStream extends ProxyInputStream {

    /**
     * For subclassing builders from {@link BoundedInputStream} subclassses.
     *
     * @param <T> The subclass.
     */
    abstract static class AbstractBuilder<T extends AbstractBuilder<T>> extends ProxyInputStream.AbstractBuilder<BoundedInputStream, T> {

        /** The current count of bytes counted. */
        private long count;

        /** The maximum count of bytes to read. */
        private long maxCount = EOF;

        private IOBiConsumer<Long, Long> onMaxCount = IOBiConsumer.noop();

        /** Flag if {@link #close()} should be propagated, {@code true} by default. */
        private boolean propagateClose = true;

        /** Whether bytes past the limit are retained and retrievable through a remaining stream. */
        private boolean retainRemaining;

        long getCount() {
            return count;
        }

        long getMaxCount() {
            return maxCount;
        }

        IOBiConsumer<Long, Long> getOnMaxCount() {
            return onMaxCount;
        }

        boolean isPropagateClose() {
            return propagateClose;
        }

        boolean isRetainRemaining() {
            return retainRemaining;
        }

        /**
         * Sets the current number of bytes counted.
         * <p>
         * Useful when building from another stream to carry forward a read count.
         * </p>
         * <p>
         * Default is {@code 0}, negative means 0.
         * </p>
         *
         * @param count The current number of bytes counted.
         * @return {@code this} instance.
         */
        public T setCount(final long count) {
            this.count = Math.max(0, count);
            return asThis();
        }

        /**
         * Sets the maximum number of bytes to return.
         * <p>
         * Default is {@value IOUtils#EOF}, negative means unbound.
         * </p>
         *
         * @param maxCount The maximum number of bytes to return, negative means unbound.
         * @return {@code this} instance.
         */
        public T setMaxCount(final long maxCount) {
            this.maxCount = Math.max(EOF, maxCount);
            return asThis();
        }

        /**
         * Sets the default {@link BoundedInputStream#onMaxLength(long, long)} behavior, {@code null} resets to a NOOP.
         * <p>
         * The first Long is the number of bytes remaining to read before the maximum is reached count of bytes to read. The second Long is the count of bytes
         * read.
         * </p>
         * <p>
         * This does <em>not</em> override a {@code BoundedInputStream} subclass' implementation of the {@link BoundedInputStream#onMaxLength(long, long)}
         * method.
         * </p>
         *
         * @param onMaxCount The {@link ProxyInputStream#afterRead(int)} behavior.
         * @return {@code this} instance.
         * @since 2.18.0
         */
        public T setOnMaxCount(final IOBiConsumer<Long, Long> onMaxCount) {
            this.onMaxCount = onMaxCount != null ? onMaxCount : IOBiConsumer.noop();
            return asThis();
        }

        /**
         * Sets whether the {@link #close()} method should propagate to the underling {@link InputStream}.
         * <p>
         * Default is {@code true}.
         * </p>
         *
         * @param propagateClose {@code true} if calling {@link #close()} propagates to the {@code close()} method of the underlying stream or {@code false} if
         *                       it does not.
         * @return {@code this} instance.
         */
        public T setPropagateClose(final boolean propagateClose) {
            this.propagateClose = propagateClose;
            return asThis();
        }

        /**
         * Sets whether to retain the bytes past the maximum count so they can be detected and retrieved.
         * <p>
         * When {@code true}, a read blocked by {@link #setMaxCount(long)} probes one extra byte from the underlying stream to distinguish a reached limit
         * from the end of the underlying stream. The probed byte and the following bytes are available from {@link BoundedInputStream#getRemainingStream()},
         * and {@link BoundedInputStream#isOverLimit()} reports whether the limit was exceeded. {@link #mark(int)} and {@link #reset()} save and restore
         * this detection state along with the read count.
         * </p>
         * <p>
         * Because the underlying stream is borrowed and must remain usable for the remaining stream, setting this to {@code true} forces
         * {@link #setPropagateClose(boolean)} to {@code false}: closing the built stream never closes the underlying stream.
         * </p>
         * <p>
         * Default is {@code false}, which preserves the classic {@link BoundedInputStream} behavior.
         * </p>
         *
         * @param retainRemaining {@code true} to retain and expose the bytes past the maximum count.
         * @return {@code this} instance.
         * @since 2.23.0
         */
        public T setRetainRemaining(final boolean retainRemaining) {
            this.retainRemaining = retainRemaining;
            if (retainRemaining) {
                this.propagateClose = false;
            }
            return asThis();
        }

    }

    //@formatter:off
    /**
     * Builds a new {@link BoundedInputStream}.
     * <p>
     * By default, a {@link BoundedInputStream} is <em>unbound</em>; so make sure to call {@link AbstractBuilder#setMaxCount(long)}.
     * </p>
     * <p>
     * You can find out how many bytes this stream has seen so far by calling {@link BoundedInputStream#getCount()}. This value reflects bytes read and skipped.
     * </p>
     * <h2>Using a ServletInputStream</h2>
     * <p>
     * A {@code ServletInputStream} can block if you try to read content that isn't there
     * because it doesn't know whether the content hasn't arrived yet or whether the content has finished. Initialize an {@link BoundedInputStream} with the
     * {@code Content-Length} sent in the {@code ServletInputStream}'s header, this stop it from blocking, providing it's been sent with a correct content
     * length in the first place.
     * </p>
     * <h2>Using NIO</h2>
     * <pre>{@code
     * BoundedInputStream s = BoundedInputStream.builder()
     *   .setPath(Paths.get("MyFile.xml"))
     *   .setMaxCount(1024)
     *   .setPropagateClose(false)
     *   .get();
     * }
     * </pre>
     * <h2>Using IO</h2>
     * <pre>{@code
     * BoundedInputStream s = BoundedInputStream.builder()
     *   .setFile(new File("MyFile.xml"))
     *   .setMaxCount(1024)
     *   .setPropagateClose(false)
     *   .get();
     * }
     * </pre>
     * <h2>Counting Bytes</h2>
     * <p>You can set the running count when building, which is most useful when starting from another stream:
     * <pre>{@code
     * InputStream in = ...;
     * BoundedInputStream s = BoundedInputStream.builder()
     *   .setInputStream(in)
     *   .setCount(12)
     *   .setMaxCount(1024)
     *   .setPropagateClose(false)
     *   .get();
     * }
     * </pre>
     *
     * @see #get()
     * @since 2.16.0
     */
    //@formatter:on
    public static class Builder extends AbstractBuilder<Builder> {

        /**
         * Constructs a new builder of {@link BoundedInputStream}.
         */
        public Builder() {
            // empty
        }

        /**
         * Builds a new {@link BoundedInputStream}.
         * <p>
         * You must set an aspect that supports {@link #getInputStream()}, otherwise, this method throws an exception.
         * </p>
         * <p>
         * If you start from an input stream, an exception can't be thrown, and you can call {@link #getUnchecked()} instead.
         * </p>
         * <p>
         * This builder uses the following aspects:
         * </p>
         * <ul>
         * <li>{@link #getInputStream()} gets the target aspect.</li>
         * <li>{@link #getAfterRead()}</li>
         * <li>{@code #getCount()}</li>
         * <li>{@code #getMaxCount()}</li>
         * <li>{@code #getOnMaxCount()}</li>
         * <li>{@code #isPropagateClose()}</li>
         * <li>{@code #isRetainRemaining()}</li>
         * </ul>
         *
         * @return A new instance.
         * @throws IllegalStateException         if the {@code origin} is {@code null}.
         * @throws UnsupportedOperationException if the origin cannot be converted to an {@link InputStream}.
         * @throws IOException                   if an I/O error occurs converting to an {@link InputStream} using {@link #getInputStream()}.
         * @see #getInputStream()
         * @see #getUnchecked()
         */
        @Override
        public BoundedInputStream get() throws IOException {
            return new BoundedInputStream(this);
        }

    }

    /**
     * Constructs a new {@link AbstractBuilder}.
     *
     * @return A new {@link AbstractBuilder}.
     * @since 2.16.0
     */
    public static Builder builder() {
        return new Builder();
    }

    /** The current count of bytes counted. */
    private long count;

    /** The current mark. */
    private long mark;

    /** The probed byte saved by a mark(int), -1 means no byte was saved. */
    private int markPushedByte = EOF;

    /** The maximum count of bytes to read. */
    private final long maxCount;

    private final IOBiConsumer<Long, Long> onMaxCount;

    /**
     * Flag if close should be propagated.
     *
     * TODO Make final in 3.0.
     */
    private boolean propagateClose = true;

    /**
     * The single byte probed past the limit while detecting an overflow.
     *
     * {@link IOUtils#EOF} means no byte has been probed. Only used when {@link #retainRemaining} is {@code true}.
     */
    private int pushedByte = EOF;

    /** Whether the bytes past the limit are retained and retrievable through {@link #getRemainingStream()}. */
    private final boolean retainRemaining;

    private BoundedInputStream(final Builder builder) throws IOException {
        super(builder);
        this.count = builder.getCount();
        this.maxCount = builder.getMaxCount();
        this.retainRemaining = builder.isRetainRemaining();
        this.propagateClose = !retainRemaining && builder.isPropagateClose();
        this.onMaxCount = builder.getOnMaxCount();
    }

    /**
     * Constructs a new {@link BoundedInputStream} that wraps the given input stream and is <em>unbounded</em>.
     * <p>
     * To build an instance: Use the {@link #builder()} to access all features.
     * </p>
     *
     * @param in The wrapped input stream.
     * @deprecated Use {@link AbstractBuilder#get()}.
     */
    @Deprecated
    public BoundedInputStream(final InputStream in) {
        this(in, EOF);
    }

    private BoundedInputStream(final InputStream inputStream, final Builder builder) {
        super(inputStream, builder);
        this.count = builder.getCount();
        this.maxCount = builder.getMaxCount();
        this.retainRemaining = builder.isRetainRemaining();
        this.propagateClose = !retainRemaining && builder.isPropagateClose();
        this.onMaxCount = builder.getOnMaxCount();
    }

    /**
     * Constructs a new {@link BoundedInputStream} that wraps the given input stream and limits it to a certain size.
     *
     * @param inputStream The wrapped input stream.
     * @param maxCount    The maximum number of bytes to return, negative means unbound.
     * @deprecated Use {@link AbstractBuilder#get()}.
     */
    @Deprecated
    public BoundedInputStream(final InputStream inputStream, final long maxCount) {
        // Some badly designed methods, for example the Servlet API, overload length
        // such that "-1" means stream finished
        this(inputStream, builder().setMaxCount(maxCount));
    }

    /**
     * Adds the number of read bytes to the count.
     *
     * @param n number of bytes read, or -1 if no more bytes are available.
     * @throws IOException Not thrown here but subclasses may throw.
     * @since 2.0
     */
    @Override
    protected synchronized void afterRead(final int n) throws IOException {
        if (n != EOF) {
            count += n;
        }
        super.afterRead(n);
    }

    @Override
    public int available() throws IOException {
        // Safe cast: value is between 0 and Integer.MAX_VALUE
        final int remaining = (int) Math.min(getRemaining(), Integer.MAX_VALUE);
        return Math.min(super.available(), remaining);
    }

    /**
     * Invokes the delegate's {@link InputStream#close()} method if {@link #isPropagateClose()} is {@code true}.
     *
     * @throws IOException Thrown if an I/O error occurs.
     */
    @Override
    public void close() throws IOException {
        if (propagateClose) {
            super.close();
        }
    }

    /**
     * Gets the count of bytes read.
     *
     * @return The count of bytes read.
     * @since 2.12.0
     */
    public synchronized long getCount() {
        return count;
    }

    /**
     * Gets the maximum number of bytes to read.
     *
     * @return The maximum number of bytes to read, or {@value IOUtils#EOF} if unbounded.
     * @since 2.16.0
     */
    public long getMaxCount() {
        return maxCount;
    }

    /**
     * Gets the maximum count of bytes to read.
     *
     * @return The maximum count of bytes to read.
     * @since 2.12.0
     * @deprecated Use {@link #getMaxCount()}.
     */
    @Deprecated
    public long getMaxLength() {
        return maxCount;
    }

    /**
     * Gets the number of bytes remaining to read before the maximum is reached.
     *
     * <p>
     * This method does <strong>not</strong> report the bytes available in the
     * underlying stream; it only reflects the remaining allowance imposed by this
     * {@code BoundedInputStream}.
     * </p>
     *
     * @return The number of bytes remaining to read before the maximum is reached,
     *         or {@link Long#MAX_VALUE} if no bound is set.
     * @since 2.16.0
     */
    public long getRemaining() {
        final long maxCount = getMaxCount();
        return maxCount == EOF ? Long.MAX_VALUE : Math.max(0, maxCount - getCount());
    }

    /**
     * Gets an input stream over the bytes past the maximum count.
     * <p>
     * The returned stream starts with the extra byte probed while detecting an overflow (if any), followed by the rest of the underlying stream. It shares
     * the underlying stream with this instance, so reading it advances the underlying position for both.
     * </p>
     * <p>
     * The returned stream does <strong>not</strong> close the underlying stream when closed itself; the caller owns the borrowed underlying stream.
     * </p>
     * <p>
     * This stream itself is unaffected by the returned view: reading past the limit through it still reports end of file.
     * </p>
     *
     * @return an input stream over the bytes past the maximum count.
     * @throws UnsupportedOperationException if this stream was not built with {@link Builder#setRetainRemaining(boolean)}.
     * @since 2.23.0
     */
    public InputStream getRemainingStream() {
        if (!retainRemaining) {
            throw new UnsupportedOperationException("Use Builder.setRetainRemaining(true) to enable the remaining input stream.");
        }
        return new RemainingInputStream();
    }

    private boolean isMaxCount() {
        return maxCount >= 0 && getCount() >= maxCount;
    }

    /**
     * Tests whether more bytes than the maximum count are available in the underlying stream.
     * <p>
     * When this stream has reached its limit, this method probes at most one extra byte from the underlying stream to distinguish a reached limit from the
     * natural end of the underlying stream. The probed byte is retained and returned first by {@link #getRemainingStream()}. Calling this method more than
     * once does not probe additional bytes.
     * </p>
     *
     * @return {@code true} if the underlying stream contains bytes past the limit.
     * @throws IOException Thrown if an I/O error occurs probing the underlying stream.
     * @throws UnsupportedOperationException if this stream was not built with {@link Builder#setRetainRemaining(boolean)}.
     * @since 2.23.0
     */
    public synchronized boolean isOverLimit() throws IOException {
        if (!retainRemaining) {
            throw new UnsupportedOperationException("Use Builder.setRetainRemaining(true) to enable over-limit detection.");
        }
        if (!isMaxCount()) {
            return false;
        }
        probeOverLimit();
        return pushedByte != EOF;
    }

    /**
     * Tests whether the {@link #close()} method should propagate to the underling {@link InputStream}.
     *
     * @return {@code true} if calling {@link #close()} propagates to the {@code close()} method of the underlying stream or {@code false} if it does not.
     */
    public boolean isPropagateClose() {
        return propagateClose;
    }

    /**
     * Tests whether this instance retains the bytes past the limit for {@link #isOverLimit()} and {@link #getRemainingStream()}.
     *
     * @return {@code true} if the bytes past the limit are retained and retrievable.
     * @since 2.23.0
     */
    public boolean isRetainRemaining() {
        return retainRemaining;
    }

    /**
     * Invokes the delegate's {@link InputStream#mark(int)} method.
     * <p>
     * The over-limit detection state is saved along with the read count and is restored by {@link #reset()}.
     * </p>
     *
     * @param readLimit read ahead limit.
     */
    @Override
    public synchronized void mark(final int readLimit) {
        in.mark(readLimit);
        mark = count;
        markPushedByte = pushedByte;
    }

    /**
     * Invokes the delegate's {@link InputStream#markSupported()} method.
     *
     * @return true if mark is supported, otherwise false.
     */
    @Override
    public boolean markSupported() {
        return in.markSupported();
    }

    /**
     * A caller has caused a request that would cross the {@code maxLength} boundary.
     * <p>
     * Delegates to the consumer set in {@link Builder#setOnMaxCount(IOBiConsumer)}.
     * </p>
     *
     * @param max The maximum count of bytes to read.
     * @param count     The count of bytes read.
     * @throws IOException Subclasses may throw.
     * @since 2.12.0
     */
    @SuppressWarnings("unused")
    // TODO Rename to onMaxCount for 3.0
    protected void onMaxLength(final long max, final long count) throws IOException {
        onMaxCount.accept(max, count);
    }

    /**
     * Invokes the delegate's {@link InputStream#read()} method if the current position is less than the limit.
     *
     * @return The byte read or -1 if the end of stream or the limit has been reached.
     * @throws IOException Thrown if an I/O error occurs.
     */
    @Override
    public int read() throws IOException {
        if (isMaxCount()) {
            onMaxLength(maxCount, getCount());
            if (retainRemaining) {
                probeOverLimit();
            }
            return EOF;
        }
        return super.read();
    }

    /**
     * Invokes the delegate's {@link InputStream#read(byte[])} method.
     *
     * @param b The buffer to read the bytes into.
     * @return The number of bytes read or -1 if the end of stream or the limit has been reached.
     * @throws IOException Thrown if an I/O error occurs.
     */
    @Override
    public int read(final byte[] b) throws IOException {
        return read(b, 0, b.length);
    }

    /**
     * Invokes the delegate's {@link InputStream#read(byte[], int, int)} method.
     *
     * @param b   The buffer to read the bytes into.
     * @param off The start offset.
     * @param len The number of bytes to read.
     * @return The number of bytes read or -1 if the end of stream or the limit has been reached.
     * @throws IOException Thrown if an I/O error occurs.
     */
    @Override
    public int read(final byte[] b, final int off, final int len) throws IOException {
        if (isMaxCount()) {
            onMaxLength(maxCount, getCount());
            if (retainRemaining) {
                probeOverLimit();
            }
            return EOF;
        }
        return super.read(b, off, (int) toReadLen(len));
    }

    /**
     * Probes the single byte past the limit, at most once until {@link #reset()} restores a prior state.
     *
     * @throws IOException Thrown if an I/O error occurs probing the underlying stream.
     */
    private synchronized void probeOverLimit() throws IOException {
        if (pushedByte == EOF) {
            // Read the delegate directly: the probe byte must not count towards getCount().
            pushedByte = in.read();
        }
    }

    /**
     * Invokes the delegate's {@link InputStream#reset()} method.
     * <p>
     * Restores the over-limit detection state saved by {@link #mark(int)} along with the read count.
     * </p>
     *
     * @throws IOException Thrown if an I/O error occurs.
     */
    @Override
    public synchronized void reset() throws IOException {
        in.reset();
        count = mark;
        pushedByte = markPushedByte;
    }

    /**
     * Sets whether the {@link #close()} method should propagate to the underling {@link InputStream}.
     *
     * @param propagateClose {@code true} if calling {@link #close()} propagates to the {@code close()} method of the underlying stream or {@code false} if it
     *                       does not.
     * @deprecated Use {@link AbstractBuilder#setPropagateClose(boolean)}.
     */
    @Deprecated
    public synchronized void setPropagateClose(final boolean propagateClose) {
        // In retain-remaining mode the borrowed underlying stream must stay open for getRemainingStream().
        this.propagateClose = propagateClose && !retainRemaining;
    }

    /**
     * Invokes the delegate's {@link InputStream#skip(long)} method.
     *
     * @param n The number of bytes to skip.
     * @return The actual number of bytes skipped.
     * @throws IOException Thrown if an I/O error occurs.
     */
    @Override
    public synchronized long skip(final long n) throws IOException {
        final long skip = super.skip(toReadLen(n));
        count += skip;
        return skip;
    }

    /**
     * Converts a request to read {@code len} bytes to a lower count if reading would put us over the limit.
     * <p>
     * If a {@code maxCount} is not set, then return max{@code maxCount}.
     * </p>
     *
     * @param len The requested byte count.
     * @return How many bytes to actually attempt to read.
     */
    private long toReadLen(final long len) {
        return maxCount >= 0 ? Math.min(len, maxCount - getCount()) : len;
    }

    /**
     * Invokes the delegate's {@link InputStream#toString()} method.
     *
     * @return The delegate's {@link InputStream#toString()}.
     */
    @Override
    public String toString() {
        return in.toString();
    }

    /**
     * Reads the bytes past the limit of the enclosing stream.
     * <p>
     * The first byte returned is the byte probed while detecting the overflow; subsequent bytes come straight from the underlying stream. Closing this
     * stream does not close the borrowed underlying stream.
     * </p>
     */
    private final class RemainingInputStream extends InputStream {

        @Override
        public int available() throws IOException {
            synchronized (BoundedInputStream.this) {
                return (pushedByte != EOF ? 1 : 0) + in.available();
            }
        }

        @Override
        public void close() {
            // The underlying stream is borrowed and owned by the caller; do not propagate close.
        }

        @Override
        public int read() throws IOException {
            synchronized (BoundedInputStream.this) {
                if (pushedByte != EOF) {
                    final int b = pushedByte;
                    pushedByte = EOF;
                    return b;
                }
                return in.read();
            }
        }

        @Override
        public int read(final byte[] b, final int off, final int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            synchronized (BoundedInputStream.this) {
                if (pushedByte == EOF) {
                    return in.read(b, off, len);
                }
                b[off] = (byte) pushedByte;
                pushedByte = EOF;
                if (len == 1) {
                    return 1;
                }
                final int n = in.read(b, off + 1, len - 1);
                return n == EOF ? 1 : n + 1;
            }
        }
    }
}

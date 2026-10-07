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

//@formatter:off
/**
 * Reads bytes up to a maximum count and stops once reached, like {@link BoundedInputStream}, but additionally lets
 * callers detect whether the underlying stream holds <em>more</em> bytes than the limit (overflow) without losing the
 * byte consumed by that detection.
 * <p>
 * To build an instance: Use the {@link #builder()} to access all features.
 * </p>
 * <p>
 * By default, an {@code OverflowBoundedInputStream} is <em>unbound</em>; so make sure to call
 * {@link AbstractBuilder#setMaxCount(long)}.
 * </p>
 * <h2>Overflow detection</h2>
 * <p>
 * Once the maximum count has been reached, {@link #isOverflow()} tells whether the underlying stream contains at
 * least one more byte. Detecting overflow consumes at most one byte from the underlying stream; that byte is
 * <em>not</em> lost: it is replayed first by the stream returned from {@link #getRemainingInputStream()}, followed by
 * all other bytes remaining in the underlying stream.
 * </p>
 * <h2>Mark and reset</h2>
 * <p>
 * {@link #mark(int)} and {@link #reset()} snapshot and restore the overflow detection state along with the read
 * count, so a {@code reset()} also undoes a pending overflow detection.
 * </p>
 * <h2>Closing</h2>
 * <p>
 * The underlying input stream is <em>borrowed</em>: {@link #close()} does <em>not</em> close it, and neither does
 * closing the stream returned by {@link #getRemainingInputStream()}.
 * </p>
 * <h2>Using IO</h2>
 * <pre>{@code
 * OverflowBoundedInputStream s = OverflowBoundedInputStream.builder()
 *   .setInputStream(in)
 *   .setMaxCount(1024)
 *   .get();
 * }
 * </pre>
 *
 * @see BoundedInputStream
 * @see Builder
 * @since 2.23.0
 */
//@formatter:on
public class OverflowBoundedInputStream extends ProxyInputStream {

    /**
     * For subclassing builders from {@link OverflowBoundedInputStream} subclasses.
     *
     * @param <T> The subclass.
     */
    abstract static class AbstractBuilder<T extends AbstractBuilder<T>> extends ProxyInputStream.AbstractBuilder<OverflowBoundedInputStream, T> {

        /** The maximum count of bytes to read. */
        private long maxCount = EOF;

        long getMaxCount() {
            return maxCount;
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

    }

    /**
     * Builds a new {@link OverflowBoundedInputStream}.
     * <p>
     * By default, an {@code OverflowBoundedInputStream} is <em>unbound</em>; so make sure to call
     * {@link AbstractBuilder#setMaxCount(long)}.
     * </p>
     * <p>
     * This builder uses the following aspects:
     * </p>
     * <ul>
     * <li>{@link #getInputStream()} gets the target aspect.</li>
     * <li>{@link #getAfterRead()}</li>
     * <li>{@code #getMaxCount()}</li>
     * </ul>
     *
     * @see #get()
     * @since 2.23.0
     */
    public static class Builder extends AbstractBuilder<Builder> {

        /**
         * Constructs a new builder of {@link OverflowBoundedInputStream}.
         */
        public Builder() {
            // empty
        }

        /**
         * Builds a new {@link OverflowBoundedInputStream}.
         * <p>
         * You must set an aspect that supports {@link #getInputStream()}, otherwise, this method throws an exception.
         * </p>
         * <p>
         * If you start from an input stream, an exception can't be thrown, and you can call {@link #getUnchecked()} instead.
         * </p>
         *
         * @return A new instance.
         * @throws IllegalStateException         if the {@code origin} is {@code null}.
         * @throws UnsupportedOperationException if the origin cannot be converted to an {@link InputStream}.
         * @throws IOException                   if an I/O error occurs converting to an {@link InputStream} using {@link #getInputStream()}.
         * @see #getInputStream()
         * @see #getUnchecked()
         */
        @Override
        public OverflowBoundedInputStream get() throws IOException {
            return new OverflowBoundedInputStream(this);
        }

    }

    /**
     * Constructs a new {@link Builder}.
     *
     * @return A new {@link Builder}.
     * @since 2.23.0
     */
    public static Builder builder() {
        return new Builder();
    }

    /** The current count of bytes counted. */
    private long count;

    /** The current mark. */
    private long mark;

    /** The maximum count of bytes to read. */
    private final long maxCount;

    /** The byte consumed from the underlying stream by overflow detection, or {@value IOUtils#EOF} if none. */
    private int overflowByte = EOF;

    /** Whether overflow detection has consumed a byte from the underlying stream. */
    private boolean overflowDetected;

    /** The overflow byte snapshot taken at {@link #mark(int)}. */
    private int markOverflowByte = EOF;

    /** The overflow detection snapshot taken at {@link #mark(int)}. */
    private boolean markOverflowDetected;

    private OverflowBoundedInputStream(final Builder builder) throws IOException {
        super(builder);
        this.maxCount = builder.getMaxCount();
    }

    /**
     * Adds the number of read bytes to the count.
     *
     * @param n number of bytes read, or -1 if no more bytes are available.
     * @throws IOException Not thrown here but subclasses may throw.
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
     * Does <em>not</em> close the borrowed underlying input stream.
     */
    @Override
    public void close() throws IOException {
        // The underlying stream is borrowed: do not propagate close().
    }

    /**
     * Gets the count of bytes read.
     *
     * @return The count of bytes read.
     */
    public synchronized long getCount() {
        return count;
    }

    /**
     * Gets the maximum number of bytes to read.
     *
     * @return The maximum number of bytes to read, or {@value IOUtils#EOF} if unbounded.
     */
    public long getMaxCount() {
        return maxCount;
    }

    /**
     * Gets the number of bytes remaining to read before the maximum is reached.
     * <p>
     * This method does <strong>not</strong> report the bytes available in the underlying stream; it only reflects the
     * remaining allowance imposed by this {@code OverflowBoundedInputStream}.
     * </p>
     *
     * @return The number of bytes remaining to read before the maximum is reached, or {@link Long#MAX_VALUE} if no
     *         bound is set.
     */
    public long getRemaining() {
        return maxCount == EOF ? Long.MAX_VALUE : Math.max(0, maxCount - getCount());
    }

    /**
     * Gets an input stream over the bytes of the underlying stream that lie beyond this stream's limit.
     * <p>
     * If overflow detection consumed a byte from the underlying stream (see {@link #isOverflow()}), that byte is
     * replayed first; all other bytes remaining in the underlying stream follow.
     * </p>
     * <p>
     * The returned stream is a live view over the borrowed underlying stream; closing it does <em>not</em> close the
     * underlying stream.
     * </p>
     *
     * @return An input stream over the bytes remaining in the underlying stream, including the byte consumed by
     *         overflow detection, if any.
     */
    public synchronized InputStream getRemainingInputStream() {
        return new InputStream() {

            /** Whether the detection-consumed byte still has to be replayed. */
            private boolean replayOverflowByte = overflowByte != EOF;

            @Override
            public int available() throws IOException {
                return (replayOverflowByte ? 1 : 0) + in.available();
            }

            @Override
            public void close() throws IOException {
                // The underlying stream is borrowed: do not propagate close().
            }

            @Override
            public int read() throws IOException {
                if (replayOverflowByte) {
                    replayOverflowByte = false;
                    return overflowByte;
                }
                return in.read();
            }

            @Override
            public int read(final byte[] b, final int off, final int len) throws IOException {
                if (len == 0) {
                    return 0;
                }
                if (replayOverflowByte) {
                    replayOverflowByte = false;
                    b[off] = (byte) overflowByte;
                    return 1;
                }
                return in.read(b, off, len);
            }
        };
    }

    private boolean isMaxCount() {
        return maxCount >= 0 && getCount() >= maxCount;
    }

    /**
     * Tests whether the underlying stream contains more bytes than this stream's limit allows to be read.
     * <p>
     * The first call made once the limit has been reached consumes at most one byte from the underlying stream to
     * perform the detection; that byte is retained and replayed by {@link #getRemainingInputStream()}. Calls made
     * before the limit is reached return {@code false} without touching the underlying stream.
     * </p>
     *
     * @return {@code true} if the limit has been reached and the underlying stream holds at least one more byte.
     * @throws IOException Thrown if an I/O error occurs.
     */
    public synchronized boolean isOverflow() throws IOException {
        if (!isMaxCount()) {
            return false;
        }
        if (!overflowDetected) {
            // Read the delegate directly: this detection byte must not count against the limit.
            overflowByte = in.read();
            overflowDetected = true;
        }
        return overflowByte != EOF;
    }

    /**
     * Invokes the delegate's {@link InputStream#mark(int)} method and snapshots the overflow detection state.
     *
     * @param readLimit read ahead limit.
     */
    @Override
    public synchronized void mark(final int readLimit) {
        in.mark(readLimit);
        mark = count;
        markOverflowByte = overflowByte;
        markOverflowDetected = overflowDetected;
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
     * Invokes the delegate's {@link InputStream#read()} method if the current position is less than the limit.
     *
     * @return The byte read or -1 if the end of stream or the limit has been reached.
     * @throws IOException Thrown if an I/O error occurs.
     */
    @Override
    public int read() throws IOException {
        if (isMaxCount()) {
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
            return EOF;
        }
        return super.read(b, off, (int) toReadLen(len));
    }

    /**
     * Invokes the delegate's {@link InputStream#reset()} method and restores the overflow detection state snapshot
     * taken by {@link #mark(int)}.
     *
     * @throws IOException Thrown if an I/O error occurs.
     */
    @Override
    public synchronized void reset() throws IOException {
        in.reset();
        count = mark;
        overflowByte = markOverflowByte;
        overflowDetected = markOverflowDetected;
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
     * If a {@code maxCount} is not set, then return {@code len}.
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
}

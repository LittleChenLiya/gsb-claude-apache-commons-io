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

package org.apache.commons.io;

/**
 * Describes the first difference found while comparing the contents of two {@link java.io.InputStream}s.
 * <p>
 * An instance reports the zero-based byte offset of the first difference along with the unsigned byte value (0-255) read from each
 * stream at that offset. A value of {@link #EOF} marks a stream that was already exhausted, which happens when the streams have
 * different lengths. {@link #isEqual()} returns true when no difference was found at all.
 * </p>
 * <p>
 * Instances are immutable and are created through {@link #equal()} and {@link #of(long, int, int)}, for example by
 * {@link IOUtils#firstDifference(java.io.InputStream, java.io.InputStream)}.
 * </p>
 *
 * @since 2.23.0
 */
public final class StreamDifference {

    /**
     * Value returned by {@link #getValue1()} and {@link #getValue2()} when the corresponding stream was already at end-of-stream
     * (EOF) at the difference offset, which happens when the streams have different lengths.
     */
    public static final int EOF = -1;

    private static final StreamDifference EQUAL = new StreamDifference(-1, EOF, EOF);

    /**
     * Gets the shared instance reporting that two streams have equal contents.
     *
     * @return the shared "equal" instance.
     */
    public static StreamDifference equal() {
        return EQUAL;
    }

    /**
     * Creates an instance reporting a difference at the given offset.
     *
     * @param offset the zero-based byte offset of the first difference.
     * @param value1 the unsigned byte value (0-255) from the first stream, or {@link #EOF} if it was exhausted.
     * @param value2 the unsigned byte value (0-255) from the second stream, or {@link #EOF} if it was exhausted.
     * @return a new difference instance.
     */
    public static StreamDifference of(final long offset, final int value1, final int value2) {
        return new StreamDifference(offset, value1, value2);
    }

    private static String toString(final int value) {
        return value == EOF ? "EOF" : String.valueOf(value);
    }

    private final long offset;

    private final int value1;

    private final int value2;

    private StreamDifference(final long offset, final int value1, final int value2) {
        this.offset = offset;
        this.value1 = value1;
        this.value2 = value2;
    }

    /**
     * Gets the zero-based byte offset of the first difference, or -1 when the streams are equal.
     *
     * @return the zero-based byte offset of the first difference, or -1 when equal.
     */
    public long getOffset() {
        return offset;
    }

    /**
     * Gets the unsigned byte value (0-255) read from the first stream at the difference offset, or {@link #EOF} if that stream was
     * already exhausted.
     *
     * @return the first stream's byte value, or {@link #EOF}.
     */
    public int getValue1() {
        return value1;
    }

    /**
     * Gets the unsigned byte value (0-255) read from the second stream at the difference offset, or {@link #EOF} if that stream was
     * already exhausted.
     *
     * @return the second stream's byte value, or {@link #EOF}.
     */
    public int getValue2() {
        return value2;
    }

    /**
     * Tests whether the compared streams have equal contents.
     *
     * @return true if no difference was found.
     */
    public boolean isEqual() {
        return offset < 0;
    }

    @Override
    public String toString() {
        if (isEqual()) {
            return "StreamDifference[equal]";
        }
        return String.format("StreamDifference[offset=%,d,value1=%s,value2=%s]", offset, toString(value1), toString(value2));
    }
}

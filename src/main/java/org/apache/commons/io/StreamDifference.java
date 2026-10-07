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

import java.io.Serializable;
import java.util.Objects;

/**
 * Describes the first difference found between two byte streams, as computed by
 * {@link IOUtils#firstDifference(java.io.InputStream, java.io.InputStream)}.
 * <p>
 * A difference is either two different byte values at the same offset, or one stream reaching its end before the
 * other. A side that has reached its end is reported with the {@link #EOF} marker instead of a byte value.
 * </p>
 *
 * @since 2.23.0
 */
public final class StreamDifference implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Marker value reported by {@link #getValue1()} or {@link #getValue2()} when that side of the comparison has
     * already reached its end of stream at the difference offset.
     */
    public static final int EOF = -1;

    /**
     * Formats a byte value or the EOF marker for display.
     *
     * @param value a byte value in the range 0-255 or {@link #EOF}.
     * @return a display string.
     */
    private static String toDisplay(final int value) {
        return value == EOF ? "EOF" : String.format("%d (0x%02X)", value, value);
    }

    /**
     * The zero-based byte offset at which the streams first differ.
     */
    private final long offset;

    /**
     * The unsigned byte value from the first stream at {@link #offset}, or {@link #EOF}.
     */
    private final int value1;

    /**
     * The unsigned byte value from the second stream at {@link #offset}, or {@link #EOF}.
     */
    private final int value2;

    /**
     * Constructs a new difference.
     *
     * @param offset the zero-based byte offset of the first difference.
     * @param value1 the unsigned byte value from the first stream, or {@link #EOF} if it ended here.
     * @param value2 the unsigned byte value from the second stream, or {@link #EOF} if it ended here.
     */
    public StreamDifference(final long offset, final int value1, final int value2) {
        this.offset = offset;
        this.value1 = value1;
        this.value2 = value2;
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof StreamDifference)) {
            return false;
        }
        final StreamDifference other = (StreamDifference) obj;
        return offset == other.offset && value1 == other.value1 && value2 == other.value2;
    }

    /**
     * Gets the zero-based byte offset at which the two streams first differ.
     *
     * @return the zero-based byte offset of the first difference.
     */
    public long getOffset() {
        return offset;
    }

    /**
     * Gets the unsigned byte value (0-255) read from the first stream at the difference offset, or {@link #EOF} if
     * the first stream had already reached its end.
     *
     * @return the first stream's byte value or {@link #EOF}.
     */
    public int getValue1() {
        return value1;
    }

    /**
     * Gets the unsigned byte value (0-255) read from the second stream at the difference offset, or {@link #EOF} if
     * the second stream had already reached its end.
     *
     * @return the second stream's byte value or {@link #EOF}.
     */
    public int getValue2() {
        return value2;
    }

    @Override
    public int hashCode() {
        return Objects.hash(offset, value1, value2);
    }

    /**
     * Tests whether the first stream had reached its end at the difference offset.
     *
     * @return true if the first stream ended at the difference offset.
     */
    public boolean isEof1() {
        return value1 == EOF;
    }

    /**
     * Tests whether the second stream had reached its end at the difference offset.
     *
     * @return true if the second stream ended at the difference offset.
     */
    public boolean isEof2() {
        return value2 == EOF;
    }

    @Override
    public String toString() {
        return String.format("StreamDifference[offset=%,d, value1=%s, value2=%s]", offset, toDisplay(value1), toDisplay(value2));
    }
}

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

import java.io.IOException;

/**
 * An {@link IOException} that records which side of a two-stream comparison failed to read, the byte offset at which
 * the read was attempted, and the original failure as the {@linkplain #getCause() cause}.
 *
 * @since 2.23.0
 */
public class StreamReadException extends IOException {

    private static final long serialVersionUID = 1L;

    /**
     * Identifies which stream of a two-stream comparison failed.
     */
    public enum Side {

        /** The first stream argument. */
        FIRST,

        /** The second stream argument. */
        SECOND
    }

    /**
     * Builds an exception message.
     *
     * @param side the failing side.
     * @param offset the byte offset at which the read was attempted.
     * @param cause the original failure.
     * @return a message.
     */
    private static String toMessage(final Side side, final long offset, final Throwable cause) {
        final String msg = cause == null ? "null" : cause.getMessage();
        return String.format("%s stream read failed at byte offset %,d: %s", side, offset, msg);
    }

    /**
     * The failing side.
     */
    private final Side side;

    /**
     * The byte offset at which the read was attempted.
     */
    private final long offset;

    /**
     * Constructs a new exception.
     *
     * @param side which stream failed to read.
     * @param offset the zero-based byte offset at which the read was attempted.
     * @param cause the original read failure, kept as this exception's {@linkplain #getCause() cause}.
     */
    public StreamReadException(final Side side, final long offset, final IOException cause) {
        super(toMessage(side, offset, cause), cause);
        this.side = side;
        this.offset = offset;
    }

    /**
     * Gets the zero-based byte offset at which the failing read was attempted.
     *
     * @return the zero-based byte offset of the failing read.
     */
    public long getOffset() {
        return offset;
    }

    /**
     * Gets which stream of the comparison failed to read.
     *
     * @return the failing side.
     */
    public Side getSide() {
        return side;
    }
}

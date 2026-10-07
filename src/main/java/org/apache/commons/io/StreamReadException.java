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
 * An {@link IOException} that records which side of a two-stream comparison failed while reading, preserving the original
 * exception as the {@linkplain #getCause() cause}.
 *
 * @since 2.23.0
 */
public class StreamReadException extends IOException {

    private static final long serialVersionUID = 1L;

    /**
     * Identifies which stream of a two-stream comparison failed.
     */
    public enum Side {

        /** The first stream. */
        INPUT_1,

        /** The second stream. */
        INPUT_2
    }

    private final Side side;

    /**
     * Constructs a new exception for the given side and cause.
     *
     * @param side the stream side that failed to read.
     * @param cause the original read failure.
     */
    public StreamReadException(final Side side, final IOException cause) {
        super(String.format("%s read failed: %s", side, cause), cause);
        this.side = side;
    }

    /**
     * Gets the original read failure.
     *
     * @return the original read failure.
     */
    @Override
    public synchronized IOException getCause() {
        return (IOException) super.getCause();
    }

    /**
     * Gets the stream side that failed to read.
     *
     * @return the stream side that failed to read.
     */
    public Side getSide() {
        return side;
    }
}

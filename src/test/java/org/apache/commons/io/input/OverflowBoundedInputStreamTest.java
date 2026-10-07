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
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests for {@link OverflowBoundedInputStream}.
 * <p>
 * All fixtures use a 3 byte limit over in-memory streams holding exactly 3 bytes (at the limit) or 4 bytes (1 byte
 * over the limit).
 * </p>
 */
class OverflowBoundedInputStreamTest {

    /** The limit used by all fixtures. */
    private static final int LIMIT = 3;

    /** In-memory data holding exactly the limit. */
    private static final byte[] DATA_AT_LIMIT = "abc".getBytes(StandardCharsets.UTF_8);

    /** In-memory data holding one byte more than the limit. */
    private static final byte[] DATA_OVER_LIMIT = "abcd".getBytes(StandardCharsets.UTF_8);

    /**
     * A {@link ByteArrayInputStream} that counts how many times it has been closed.
     */
    private static final class CloseCountingInputStream extends ByteArrayInputStream {

        private final AtomicInteger closeCount = new AtomicInteger();

        CloseCountingInputStream(final byte[] buf) {
            super(buf);
        }

        @Override
        public void close() throws IOException {
            closeCount.incrementAndGet();
            super.close();
        }

        int getCloseCount() {
            return closeCount.get();
        }
    }

    static Stream<Arguments> limitFixtures() {
        return Stream.of(
                Arguments.of("3 bytes at a 3 byte limit", DATA_AT_LIMIT, false),
                Arguments.of("4 bytes at a 3 byte limit", DATA_OVER_LIMIT, true));
    }

    private static OverflowBoundedInputStream bounded(final InputStream inputStream) throws IOException {
        return OverflowBoundedInputStream.builder().setInputStream(inputStream).setMaxCount(LIMIT).get();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("limitFixtures")
    void testReadStopsAtLimit(final String name, final byte[] data, final boolean overflowExpected) throws IOException {
        try (OverflowBoundedInputStream in = bounded(new ByteArrayInputStream(data))) {
            assertEquals(LIMIT, in.getMaxCount());
            assertArrayEquals(DATA_AT_LIMIT, IOUtils.toByteArray(in));
            assertEquals(LIMIT, in.getCount());
            assertEquals(0, in.getRemaining());
            assertEquals(EOF, in.read());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("limitFixtures")
    void testOverflowDetection(final String name, final byte[] data, final boolean overflowExpected) throws IOException {
        try (OverflowBoundedInputStream in = bounded(new ByteArrayInputStream(data))) {
            // Before the limit is reached, no overflow can be reported.
            assertFalse(in.isOverflow());
            assertEquals(LIMIT, IOUtils.read(in, new byte[LIMIT]));
            // At the limit, detection reflects whether the underlying stream holds more bytes.
            assertEquals(overflowExpected, in.isOverflow());
            // Detection is idempotent and does not count against the limit.
            assertEquals(overflowExpected, in.isOverflow());
            assertEquals(LIMIT, in.getCount());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("limitFixtures")
    void testRemainingInputStreamReturnsDetectionByte(final String name, final byte[] data, final boolean overflowExpected)
            throws IOException {
        try (OverflowBoundedInputStream in = bounded(new ByteArrayInputStream(data))) {
            IOUtils.skipFully(in, LIMIT);
            assertEquals(overflowExpected, in.isOverflow());
            // The byte consumed by detection is replayed first by the remaining stream.
            try (InputStream remaining = in.getRemainingInputStream()) {
                final byte[] expected = overflowExpected ? new byte[] { 'd' } : new byte[0];
                assertArrayEquals(expected, IOUtils.toByteArray(remaining));
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("limitFixtures")
    void testRemainingInputStreamWithoutDetection(final String name, final byte[] data, final boolean overflowExpected)
            throws IOException {
        try (OverflowBoundedInputStream in = bounded(new ByteArrayInputStream(data))) {
            IOUtils.skipFully(in, LIMIT);
            // Without detection, the remaining stream simply exposes what is left in the underlying stream.
            try (InputStream remaining = in.getRemainingInputStream()) {
                final byte[] expected = overflowExpected ? new byte[] { 'd' } : new byte[0];
                assertArrayEquals(expected, IOUtils.toByteArray(remaining));
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("limitFixtures")
    void testMarkResetRestoresDetectionState(final String name, final byte[] data, final boolean overflowExpected)
            throws IOException {
        try (OverflowBoundedInputStream in = bounded(new ByteArrayInputStream(data))) {
            assertTrue(in.markSupported());
            assertEquals('a', in.read());
            in.mark(data.length);
            assertEquals('b', in.read());
            assertEquals('c', in.read());
            assertEquals(EOF, in.read());
            // Detect overflow, consuming a byte from the underlying stream when it overflows.
            assertEquals(overflowExpected, in.isOverflow());
            assertEquals(LIMIT, in.getCount());
            // Reset restores both the read count and the overflow detection state.
            in.reset();
            assertEquals(1, in.getCount());
            assertEquals(2, in.getRemaining());
            assertEquals('b', in.read());
            assertEquals('c', in.read());
            assertEquals(EOF, in.read());
            // Detection state was restored: detection runs again and gives the same answer.
            assertEquals(overflowExpected, in.isOverflow());
            // And the remaining stream still replays the detection byte.
            try (InputStream remaining = in.getRemainingInputStream()) {
                final byte[] expected = overflowExpected ? new byte[] { 'd' } : new byte[0];
                assertArrayEquals(expected, IOUtils.toByteArray(remaining));
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("limitFixtures")
    void testCloseDoesNotCloseBorrowedStream(final String name, final byte[] data, final boolean overflowExpected)
            throws IOException {
        final CloseCountingInputStream borrowed = new CloseCountingInputStream(data);
        final OverflowBoundedInputStream in = bounded(borrowed);
        IOUtils.skipFully(in, LIMIT);
        final InputStream remaining = in.getRemainingInputStream();
        in.close();
        in.close();
        remaining.close();
        // Neither close() propagated to the borrowed underlying stream.
        assertEquals(0, borrowed.getCloseCount());
        // The borrowed stream is still usable and positioned where the bounded stream left it.
        assertEquals(overflowExpected ? 'd' : EOF, borrowed.read());
        borrowed.close();
        assertEquals(1, borrowed.getCloseCount());
    }

    @Test
    void testDefaultIsUnbound() throws IOException {
        try (OverflowBoundedInputStream in = OverflowBoundedInputStream.builder()
                .setInputStream(new ByteArrayInputStream(DATA_OVER_LIMIT)).get()) {
            assertEquals(EOF, in.getMaxCount());
            assertEquals(Long.MAX_VALUE, in.getRemaining());
            assertFalse(in.isOverflow());
            assertArrayEquals(DATA_OVER_LIMIT, IOUtils.toByteArray(in));
            assertEquals(DATA_OVER_LIMIT.length, in.getCount());
        }
    }

    @Test
    void testAvailableAndSkipAreBounded() throws IOException {
        try (OverflowBoundedInputStream in = bounded(new ByteArrayInputStream(DATA_OVER_LIMIT))) {
            assertEquals(LIMIT, in.available());
            assertEquals(2, in.skip(2));
            // A skip past the limit is clamped to the remaining allowance.
            assertEquals(1, in.skip(10));
            assertEquals(0, in.available());
            assertEquals(EOF, in.read());
            assertTrue(in.isOverflow());
        }
    }
}

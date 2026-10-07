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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.apache.commons.io.StreamReadException.Side;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@link IOUtils#firstDifference(InputStream, InputStream)}, {@link StreamDifference}, and
 * {@link StreamReadException}.
 */
public class IOUtilsFirstDifferenceTest {

    /**
     * An {@link InputStream} that delivers its data in short reads of at most {@code chunkSize} bytes, to prove the
     * comparison does not depend on how the two sides chunk their reads.
     */
    private static final class ChunkedInputStream extends FilterInputStream {

        private final int chunkSize;

        ChunkedInputStream(final byte[] data, final int chunkSize) {
            super(new ByteArrayInputStream(data));
            this.chunkSize = chunkSize;
        }

        @Override
        public int read(final byte[] b, final int off, final int len) throws IOException {
            return super.read(b, off, Math.min(len, chunkSize));
        }
    }

    /**
     * An {@link InputStream} that tracks whether {@link #close()} was invoked.
     */
    private static final class CloseTrackingInputStream extends FilterInputStream {

        private boolean closed;

        CloseTrackingInputStream(final InputStream in) {
            super(in);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }

        boolean isClosed() {
            return closed;
        }
    }

    /**
     * An {@link InputStream} that throws a fixed {@link IOException} once a given byte offset is reached.
     */
    private static final class FailingInputStream extends InputStream {

        private final byte[] data;
        private final long failAt;
        private final IOException failure;
        private long offset;

        FailingInputStream(final byte[] data, final long failAt, final IOException failure) {
            this.data = data;
            this.failAt = failAt;
            this.failure = failure;
        }

        @Override
        public int read() throws IOException {
            if (offset >= failAt) {
                throw failure;
            }
            return offset < data.length ? data[(int) offset++] & 0xFF : -1;
        }
    }

    private static byte[] bytes(final int... values) {
        final byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = (byte) values[i];
        }
        return result;
    }

    @TempDir
    private Path tempDir;

    @Test
    public void testEqualContentWithDifferentShortReadChunking() throws IOException {
        final byte[] data = bytes(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        final CloseTrackingInputStream input1 = new CloseTrackingInputStream(new ChunkedInputStream(data, 3));
        final CloseTrackingInputStream input2 = new CloseTrackingInputStream(new ChunkedInputStream(data, 4));
        assertEquals(Optional.empty(), IOUtils.firstDifference(input1, input2));
        assertFalse(input1.isClosed(), "first stream must not be closed");
        assertFalse(input2.isClosed(), "second stream must not be closed");
    }

    @Test
    public void testDifferenceAtFirstByte() throws IOException {
        final Optional<StreamDifference> difference = IOUtils.firstDifference(
            new ChunkedInputStream(bytes(0x0A, 2, 3), 2),
            new ChunkedInputStream(bytes(0x0B, 2, 3), 5));
        assertTrue(difference.isPresent());
        assertEquals(0, difference.get().getOffset());
        assertEquals(0x0A, difference.get().getValue1());
        assertEquals(0x0B, difference.get().getValue2());
        assertFalse(difference.get().isEof1());
        assertFalse(difference.get().isEof2());
    }

    @Test
    public void testDifferenceAtLastByte() throws IOException {
        final Optional<StreamDifference> difference = IOUtils.firstDifference(
            new ChunkedInputStream(bytes(1, 2, 3, 0x7F), 1),
            new ChunkedInputStream(bytes(1, 2, 3, 0x80), 7));
        assertTrue(difference.isPresent());
        assertEquals(3, difference.get().getOffset());
        assertEquals(0x7F, difference.get().getValue1());
        assertEquals(0x80, difference.get().getValue2());
    }

    @Test
    public void testDifferenceInMiddle() throws IOException {
        final Optional<StreamDifference> difference = IOUtils.firstDifference(
            new ByteArrayInputStream(bytes(9, 8, 7, 6)),
            new ByteArrayInputStream(bytes(9, 8, 0, 6)));
        assertTrue(difference.isPresent());
        assertEquals(2, difference.get().getOffset());
        assertEquals(7, difference.get().getValue1());
        assertEquals(0, difference.get().getValue2());
    }

    @Test
    public void testFirstStreamEndsEarly() throws IOException {
        final Optional<StreamDifference> difference = IOUtils.firstDifference(
            new ChunkedInputStream(bytes(1, 2, 3), 2),
            new ChunkedInputStream(bytes(1, 2, 3, 4, 5), 2));
        assertTrue(difference.isPresent());
        assertEquals(3, difference.get().getOffset());
        assertEquals(StreamDifference.EOF, difference.get().getValue1());
        assertTrue(difference.get().isEof1());
        assertFalse(difference.get().isEof2());
        assertEquals(4, difference.get().getValue2());
    }

    @Test
    public void testSecondStreamEndsEarly() throws IOException {
        final Optional<StreamDifference> difference = IOUtils.firstDifference(
            new ByteArrayInputStream(bytes(1, 2, 3, 4)),
            new ByteArrayInputStream(bytes(1, 2)));
        assertTrue(difference.isPresent());
        assertEquals(2, difference.get().getOffset());
        assertEquals(3, difference.get().getValue1());
        assertEquals(StreamDifference.EOF, difference.get().getValue2());
        assertTrue(difference.get().isEof2());
    }

    @Test
    public void testBothEmpty() throws IOException {
        assertEquals(Optional.empty(), IOUtils.firstDifference(new ByteArrayInputStream(new byte[0]), new ByteArrayInputStream(new byte[0])));
    }

    @Test
    public void testSameInstanceAndNulls() throws IOException {
        final InputStream input = new ByteArrayInputStream(bytes(1));
        assertEquals(Optional.empty(), IOUtils.firstDifference(input, input));
        assertEquals(Optional.empty(), IOUtils.firstDifference(null, null));
        // A null input is treated as an immediately exhausted stream.
        final Optional<StreamDifference> difference = IOUtils.firstDifference(null, new ByteArrayInputStream(bytes(42)));
        assertTrue(difference.isPresent());
        assertEquals(0, difference.get().getOffset());
        assertEquals(StreamDifference.EOF, difference.get().getValue1());
        assertEquals(42, difference.get().getValue2());
    }

    @Test
    public void testReadExceptionOnFirstSide() {
        final IOException boom = new IOException("boom-1");
        final CloseTrackingInputStream input1 = new CloseTrackingInputStream(new FailingInputStream(bytes(1, 2, 3), 2, boom));
        final CloseTrackingInputStream input2 = new CloseTrackingInputStream(new ByteArrayInputStream(bytes(1, 2, 3)));
        final StreamReadException e = assertThrows(StreamReadException.class, () -> IOUtils.firstDifference(input1, input2));
        assertEquals(Side.FIRST, e.getSide());
        assertEquals(2, e.getOffset());
        assertSame(boom, e.getCause());
        assertFalse(input1.isClosed(), "first stream must not be closed");
        assertFalse(input2.isClosed(), "second stream must not be closed");
    }

    @Test
    public void testReadExceptionOnSecondSide() {
        final IOException boom = new IOException("boom-2");
        final CloseTrackingInputStream input1 = new CloseTrackingInputStream(new ByteArrayInputStream(bytes(5, 6, 7, 8)));
        final CloseTrackingInputStream input2 = new CloseTrackingInputStream(new FailingInputStream(bytes(5, 6, 7, 8), 3, boom));
        final StreamReadException e = assertThrows(StreamReadException.class, () -> IOUtils.firstDifference(input1, input2));
        assertEquals(Side.SECOND, e.getSide());
        assertEquals(3, e.getOffset());
        assertSame(boom, e.getCause());
        assertFalse(input1.isClosed(), "first stream must not be closed");
        assertFalse(input2.isClosed(), "second stream must not be closed");
    }

    @Test
    public void testStreamDifferenceValueObject() {
        final StreamDifference difference = new StreamDifference(7, 0x41, StreamDifference.EOF);
        assertEquals(7, difference.getOffset());
        assertEquals(0x41, difference.getValue1());
        assertEquals(StreamDifference.EOF, difference.getValue2());
        assertFalse(difference.isEof1());
        assertTrue(difference.isEof2());
        assertEquals(difference, new StreamDifference(7, 0x41, StreamDifference.EOF));
        assertEquals(difference.hashCode(), new StreamDifference(7, 0x41, StreamDifference.EOF).hashCode());
        assertTrue(difference.toString().contains("offset=7"), difference.toString());
        assertTrue(difference.toString().contains("EOF"), difference.toString());
    }

    /**
     * Demonstrates the helper on a small temporary-folder fixture: two tiny files differing at a known offset.
     */
    @Test
    public void testTemporaryFileFixtureDemo() throws IOException {
        final Path file1 = tempDir.resolve("first.bin");
        final Path file2 = tempDir.resolve("second.bin");
        Files.write(file1, bytes(72, 101, 108, 108, 111, 33)); // "Hello!"
        Files.write(file2, bytes(72, 101, 108, 108, 97, 33)); // "Hella!"
        try (InputStream input1 = Files.newInputStream(file1);
                InputStream input2 = Files.newInputStream(file2)) {
            final Optional<StreamDifference> difference = IOUtils.firstDifference(input1, input2);
            assertTrue(difference.isPresent());
            assertEquals(4, difference.get().getOffset());
            assertEquals(111, difference.get().getValue1());
            assertEquals(97, difference.get().getValue2());
            System.out.println("firstDifference demo: " + difference.get());
        }
    }
}

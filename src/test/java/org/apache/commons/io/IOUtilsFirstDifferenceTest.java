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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.commons.io.StreamReadException.Side;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@link IOUtils#firstDifference(InputStream, InputStream)} and its use by
 * {@link IOUtils#contentEquals(InputStream, InputStream)}.
 */
public class IOUtilsFirstDifferenceTest {

    /**
     * An InputStream that caps each read at a fixed size to force short reads, and records whether it was closed.
     */
    private static final class ChunkedInputStream extends FilterInputStream {

        private final int maxChunk;
        private boolean closed;

        ChunkedInputStream(final byte[] data, final int maxChunk) {
            super(new ByteArrayInputStream(data));
            this.maxChunk = maxChunk;
        }

        @Override
        public void close() {
            closed = true;
        }

        boolean isClosed() {
            return closed;
        }

        @Override
        public int read(final byte[] b, final int off, final int len) throws IOException {
            return super.read(b, off, Math.min(len, maxChunk));
        }
    }

    /**
     * An InputStream that throws a fixed IOException once a given number of bytes has been read.
     */
    private static final class BrokenInputStream extends InputStream {

        private final byte[] data;
        private final int failAfter;
        private final IOException failure;
        private int pos;
        private boolean closed;

        BrokenInputStream(final byte[] data, final int failAfter, final IOException failure) {
            this.data = data;
            this.failAfter = failAfter;
            this.failure = failure;
        }

        @Override
        public void close() {
            closed = true;
        }

        boolean isClosed() {
            return closed;
        }

        @Override
        public int read() throws IOException {
            if (pos >= failAfter) {
                throw failure;
            }
            return pos < data.length ? data[pos++] & 0xff : -1;
        }
    }

    private static byte[] bytes(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    public void testEqualContentWithDifferentShortReadChunking() throws IOException {
        final byte[] data = bytes("abcdefghijklmnopqrstuvwxyz0123456789");
        final ChunkedInputStream input1 = new ChunkedInputStream(data, 3);
        final ChunkedInputStream input2 = new ChunkedInputStream(data, 7);
        final StreamDifference difference = IOUtils.firstDifference(input1, input2);
        assertTrue(difference.isEqual());
        assertSame(StreamDifference.equal(), difference);
        assertTrue(IOUtils.contentEquals(new ChunkedInputStream(data, 3), new ChunkedInputStream(data, 7)));
        // Neither stream may be closed by the comparison.
        assertFalse(input1.isClosed());
        assertFalse(input2.isClosed());
    }

    @Test
    public void testDifferenceAtFirstByte() throws IOException {
        final StreamDifference difference = IOUtils.firstDifference(new ByteArrayInputStream(bytes("Xbc")),
            new ByteArrayInputStream(bytes("abc")));
        assertFalse(difference.isEqual());
        assertEquals(0, difference.getOffset());
        assertEquals('X', difference.getValue1());
        assertEquals('a', difference.getValue2());
        assertFalse(IOUtils.contentEquals(new ByteArrayInputStream(bytes("Xbc")), new ByteArrayInputStream(bytes("abc"))));
    }

    @Test
    public void testDifferenceAtLastByte() throws IOException {
        final StreamDifference difference = IOUtils.firstDifference(new ByteArrayInputStream(bytes("abc")),
            new ByteArrayInputStream(bytes("abZ")));
        assertFalse(difference.isEqual());
        assertEquals(2, difference.getOffset());
        assertEquals('c', difference.getValue1());
        assertEquals('Z', difference.getValue2());
    }

    @Test
    public void testDifferenceAcrossChunkBoundaries() throws IOException {
        // Difference located past many short, differently-sized reads on each side.
        final byte[] data1 = bytes("abcdefghijklmnopqrstuvwxyz");
        final byte[] data2 = bytes("abcdefghijklmnopqrstuvwxyz");
        data2[20] = '!';
        final StreamDifference difference = IOUtils.firstDifference(new ChunkedInputStream(data1, 3),
            new ChunkedInputStream(data2, 5));
        assertEquals(20, difference.getOffset());
        assertEquals('u', difference.getValue1());
        assertEquals('!', difference.getValue2());
    }

    @Test
    public void testFirstSideEndsEarly() throws IOException {
        final StreamDifference difference = IOUtils.firstDifference(new ByteArrayInputStream(bytes("abc")),
            new ByteArrayInputStream(bytes("abcd")));
        assertFalse(difference.isEqual());
        assertEquals(3, difference.getOffset());
        assertEquals(StreamDifference.EOF, difference.getValue1());
        assertEquals('d', difference.getValue2());
        assertFalse(IOUtils.contentEquals(new ByteArrayInputStream(bytes("abc")), new ByteArrayInputStream(bytes("abcd"))));
    }

    @Test
    public void testSecondSideEndsEarly() throws IOException {
        final StreamDifference difference = IOUtils.firstDifference(new ByteArrayInputStream(bytes("abcd")),
            new ByteArrayInputStream(bytes("abc")));
        assertFalse(difference.isEqual());
        assertEquals(3, difference.getOffset());
        assertEquals('d', difference.getValue1());
        assertEquals(StreamDifference.EOF, difference.getValue2());
    }

    @Test
    public void testNullStreams() throws IOException {
        assertSame(StreamDifference.equal(), IOUtils.firstDifference(null, null));
        // A null stream is treated as immediately exhausted.
        StreamDifference difference = IOUtils.firstDifference(null, new ByteArrayInputStream(bytes("a")));
        assertEquals(0, difference.getOffset());
        assertEquals(StreamDifference.EOF, difference.getValue1());
        assertEquals('a', difference.getValue2());
        difference = IOUtils.firstDifference(new ByteArrayInputStream(bytes("a")), null);
        assertEquals(0, difference.getOffset());
        assertEquals('a', difference.getValue1());
        assertEquals(StreamDifference.EOF, difference.getValue2());
        // contentEquals keeps its historical null semantics.
        assertTrue(IOUtils.contentEquals((InputStream) null, null));
        assertFalse(IOUtils.contentEquals(new ByteArrayInputStream(bytes("a")), null));
        assertFalse(IOUtils.contentEquals(null, new ByteArrayInputStream(bytes("a"))));
    }

    @Test
    public void testReadFailureOnFirstSide() {
        final IOException boom = new IOException("boom-1");
        final BrokenInputStream broken = new BrokenInputStream(bytes("abcdef"), 2, boom);
        final ByteArrayInputStream other = new ByteArrayInputStream(bytes("abcdef"));
        final StreamReadException exception = assertThrows(StreamReadException.class,
            () -> IOUtils.firstDifference(broken, other));
        assertSame(Side.INPUT_1, exception.getSide());
        assertSame(boom, exception.getCause());
        // Streams are left open even when a read fails.
        assertFalse(broken.isClosed());
    }

    @Test
    public void testReadFailureOnSecondSide() {
        final IOException boom = new IOException("boom-2");
        final ByteArrayInputStream other = new ByteArrayInputStream(bytes("abcdef"));
        final BrokenInputStream broken = new BrokenInputStream(bytes("abcdef"), 4, boom);
        final StreamReadException exception = assertThrows(StreamReadException.class,
            () -> IOUtils.firstDifference(other, broken));
        assertSame(Side.INPUT_2, exception.getSide());
        assertSame(boom, exception.getCause());
        assertFalse(broken.isClosed());
    }

    @Test
    public void testReadFailurePropagatesThroughContentEquals() {
        final IOException boom = new IOException("boom-3");
        final StreamReadException exception = assertThrows(StreamReadException.class, () -> IOUtils
            .contentEquals(new BrokenInputStream(bytes("abc"), 0, boom), new ByteArrayInputStream(bytes("abc"))));
        assertSame(Side.INPUT_1, exception.getSide());
        assertSame(boom, exception.getCause());
    }

    @Test
    public void testTempFolderFiles(@TempDir final Path tempDir) throws IOException {
        final Path file1 = tempDir.resolve("first.bin");
        final Path file2 = tempDir.resolve("second.bin");
        Files.write(file1, bytes("The quick brown fox"));
        Files.write(file2, bytes("The quick brown FOX"));
        try (InputStream input1 = Files.newInputStream(file1); InputStream input2 = Files.newInputStream(file2)) {
            final StreamDifference difference = IOUtils.firstDifference(input1, input2);
            assertEquals(16, difference.getOffset());
            assertEquals('f', difference.getValue1());
            assertEquals('F', difference.getValue2());
            System.out.println("file diff: " + difference);
        }
        // Length mismatch against a file: EOF marker on the shorter side.
        final Path file3 = tempDir.resolve("third.bin");
        Files.write(file3, bytes("The quick brown"));
        try (InputStream input1 = Files.newInputStream(file1); InputStream input3 = Files.newInputStream(file3)) {
            final StreamDifference difference = IOUtils.firstDifference(input1, input3);
            assertEquals(15, difference.getOffset());
            assertEquals(' ', difference.getValue1());
            assertEquals(StreamDifference.EOF, difference.getValue2());
            System.out.println("file diff: " + difference);
        }
    }

    @Test
    public void testToStringRepresentation() {
        assertEquals("StreamDifference[equal]", StreamDifference.equal().toString());
        assertEquals("StreamDifference[offset=3,value1=EOF,value2=100]",
            StreamDifference.of(3, StreamDifference.EOF, 'd').toString());
    }
}

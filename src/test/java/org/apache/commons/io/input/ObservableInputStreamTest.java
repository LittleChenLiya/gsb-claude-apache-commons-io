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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.ObservableInputStream.Observer;
import org.apache.commons.io.output.NullOutputStream;
import org.apache.commons.io.test.CustomIOException;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link ObservableInputStream}.
 */
class ObservableInputStreamTest {

    private static final class DataViewObserver extends MethodCountObserver {
        private byte[] buffer;
        private int lastValue = -1;
        private int length = -1;
        private int offset = -1;

        @Override
        public void data(final byte[] buffer, final int offset, final int length) throws IOException {
            this.buffer = buffer;
            this.offset = offset;
            this.length = length;
        }

        @Override
        public void data(final int value) throws IOException {
            super.data(value);
            lastValue = value;
        }
    }

    private static final class LengthObserver extends Observer {
        private long total;

        @Override
        public void data(final byte[] buffer, final int offset, final int length) throws IOException {
            this.total += length;
        }

        @Override
        public void data(final int value) throws IOException {
            total++;
        }

        public long getTotal() {
            return total;
        }
    }

    private static class MethodCountObserver extends Observer {
        private long closedCount;
        private long dataBufferCount;
        private long dataCount;
        private long errorCount;
        private long finishedCount;

        @Override
        public void closed() throws IOException {
            closedCount++;
        }

        @Override
        public void data(final byte[] buffer, final int offset, final int length) throws IOException {
            dataBufferCount++;
        }

        @Override
        public void data(final int value) throws IOException {
            dataCount++;
        }

        @Override
        public void error(final IOException exception) throws IOException {
            errorCount++;
        }

        @Override
        public void finished() throws IOException {
            finishedCount++;
        }

        public long getClosedCount() {
            return closedCount;
        }

        public long getDataBufferCount() {
            return dataBufferCount;
        }

        public long getDataCount() {
            return dataCount;
        }

        public long getErrorCount() {
            return errorCount;
        }

        public long getFinishedCount() {
            return finishedCount;
        }

    }

    private static final class CloseTrackingInputStream extends ByteArrayInputStream {
        private boolean closed;

        CloseTrackingInputStream(final byte[] buffer) {
            super(buffer);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }

    private ObservableInputStream brokenObservableInputStream() {
        return new ObservableInputStream(BrokenInputStream.INSTANCE);
    }

    private InputStream createInputStream() {
        final byte[] buffer = MessageDigestInputStreamTest.generateRandomByteStream(IOUtils.DEFAULT_BUFFER_SIZE);
        return createInputStream(new ByteArrayInputStream(buffer));
    }

    private ObservableInputStream createInputStream(final InputStream origin) {
        return new ObservableInputStream(origin);
    }

    @Test
    void testAfterReadConsumer() throws Exception {
        final AtomicBoolean boolRef = new AtomicBoolean();
        // @formatter:off
        try (InputStream bounded = new ObservableInputStream.Builder()
                .setCharSequence("Hi")
                .setAfterRead(i -> boolRef.set(true))
                .get()) {
            IOUtils.consume(bounded);
        }
        // @formatter:on
        assertTrue(boolRef.get());
        // Throwing
        final String message = "test exception message";
        // @formatter:off
        try (InputStream bounded = new ObservableInputStream.Builder()
                .setCharSequence("Hi")
                .setAfterRead(i -> {
                    throw new CustomIOException(message);
                })
                .get()) {
            assertEquals(message, assertThrowsExactly(CustomIOException.class, () -> IOUtils.consume(bounded)).getMessage());
        }
        // @formatter:on
    }

    @SuppressWarnings("resource")
    @Test
    void testAvailableAfterClose() throws Exception {
        final InputStream shadow;
        try (InputStream in = createInputStream()) {
            assertTrue(in.available() > 0);
            shadow = in;
        }
        assertEquals(0, shadow.available());
    }

    @Test
    void testAvailableAfterOpen() throws Exception {
        try (InputStream in = createInputStream()) {
            assertTrue(in.available() > 0);
            assertNotEquals(IOUtils.EOF, in.read());
            assertTrue(in.available() > 0);
        }
    }

    @Test
    void testBrokenInputStreamRead() throws IOException {
        try (ObservableInputStream ois = brokenObservableInputStream()) {
            assertThrows(IOException.class, ois::read);
        }
    }

    @Test
    void testBrokenInputStreamReadBuffer() throws IOException {
        try (ObservableInputStream ois = brokenObservableInputStream()) {
            assertThrows(IOException.class, () -> ois.read(new byte[1]));
        }
    }

    @Test
    void testBrokenInputStreamReadSubBuffer() throws IOException {
        try (ObservableInputStream ois = brokenObservableInputStream()) {
            assertThrows(IOException.class, () -> ois.read(new byte[2], 0, 1));
        }
    }

    /**
     * Tests that {@link Observer#data(int)} is called.
     */
    @Test
    void testDataByteCalled_add() throws Exception {
        final byte[] buffer = MessageDigestInputStreamTest.generateRandomByteStream(IOUtils.DEFAULT_BUFFER_SIZE);
        final DataViewObserver lko = new DataViewObserver();
        try (ObservableInputStream ois = new ObservableInputStream(new ByteArrayInputStream(buffer))) {
            assertEquals(-1, lko.lastValue);
            ois.read();
            assertEquals(-1, lko.lastValue);
            assertEquals(0, lko.getFinishedCount());
            assertEquals(0, lko.getClosedCount());
            ois.add(lko);
            for (int i = 1; i < buffer.length; i++) {
                final int result = ois.read();
                assertEquals((byte) result, buffer[i]);
                assertEquals(result, lko.lastValue);
                assertEquals(0, lko.getFinishedCount());
                assertEquals(0, lko.getClosedCount());
            }
            final int result = ois.read();
            assertEquals(-1, result);
            assertEquals(1, lko.getFinishedCount());
            assertEquals(0, lko.getClosedCount());
            ois.close();
            assertEquals(1, lko.getFinishedCount());
            assertEquals(1, lko.getClosedCount());
        }
    }

    /**
     * Tests that {@link Observer#data(int)} is called.
     */
    @Test
    void testDataByteCalled_ctor() throws Exception {
        final byte[] buffer = MessageDigestInputStreamTest.generateRandomByteStream(IOUtils.DEFAULT_BUFFER_SIZE);
        final DataViewObserver lko = new DataViewObserver();
        try (ObservableInputStream ois = new ObservableInputStream(new ByteArrayInputStream(buffer), lko)) {
            assertEquals(-1, lko.lastValue);
            ois.read();
            assertNotEquals(-1, lko.lastValue);
            assertEquals(0, lko.getFinishedCount());
            assertEquals(0, lko.getClosedCount());
            for (int i = 1; i < buffer.length; i++) {
                final int result = ois.read();
                assertEquals((byte) result, buffer[i]);
                assertEquals(result, lko.lastValue);
                assertEquals(0, lko.getFinishedCount());
                assertEquals(0, lko.getClosedCount());
            }
            final int result = ois.read();
            assertEquals(-1, result);
            assertEquals(1, lko.getFinishedCount());
            assertEquals(0, lko.getClosedCount());
            ois.close();
            assertEquals(1, lko.getFinishedCount());
            assertEquals(1, lko.getClosedCount());
        }
    }

    /**
     * Tests that {@link Observer#data(byte[],int,int)} is called.
     */
    @Test
    void testDataBytesCalled() throws Exception {
        final byte[] buffer = MessageDigestInputStreamTest.generateRandomByteStream(IOUtils.DEFAULT_BUFFER_SIZE);
        try (ByteArrayInputStream bais = new ByteArrayInputStream(buffer);
                ObservableInputStream ois = createInputStream(bais)) {
            final DataViewObserver observer = new DataViewObserver();
            final byte[] readBuffer = new byte[23];
            assertNull(observer.buffer);
            ois.read(readBuffer);
            assertNull(observer.buffer);
            ois.add(observer);
            for (;;) {
                if (bais.available() >= 2048) {
                    final int result = ois.read(readBuffer);
                    if (result == -1) {
                        ois.close();
                        break;
                    }
                    assertEquals(readBuffer, observer.buffer);
                    assertEquals(0, observer.offset);
                    assertEquals(readBuffer.length, observer.length);
                } else {
                    final int res = Math.min(11, bais.available());
                    final int result = ois.read(readBuffer, 1, 11);
                    if (result == -1) {
                        ois.close();
                        break;
                    }
                    assertEquals(readBuffer, observer.buffer);
                    assertEquals(1, observer.offset);
                    assertEquals(res, observer.length);
                }
            }
        }
    }

    @Test
    void testGetObservers0() throws IOException {
        try (ObservableInputStream ois = new ObservableInputStream(new NullInputStream())) {
            assertTrue(ois.getObservers().isEmpty());
        }
    }

    @Test
    void testGetObservers1() throws IOException {
        final DataViewObserver observer0 = new DataViewObserver();
        try (ObservableInputStream ois = new ObservableInputStream(new NullInputStream(), observer0)) {
            assertEquals(observer0, ois.getObservers().get(0));
        }
    }

    @Test
    void testGetObserversOrder() throws IOException {
        final DataViewObserver observer0 = new DataViewObserver();
        final DataViewObserver observer1 = new DataViewObserver();
        try (ObservableInputStream ois = new ObservableInputStream(new NullInputStream(), observer0, observer1)) {
            assertEquals(observer0, ois.getObservers().get(0));
            assertEquals(observer1, ois.getObservers().get(1));
        }
    }

    private void testNotificationCallbacks(final int bufferSize) throws IOException {
        final byte[] buffer = IOUtils.byteArray();
        final LengthObserver lengthObserver = new LengthObserver();
        final MethodCountObserver methodCountObserver = new MethodCountObserver();
        try (ObservableInputStream ois = new ObservableInputStream(new ByteArrayInputStream(buffer), lengthObserver, methodCountObserver)) {
            assertEquals(IOUtils.DEFAULT_BUFFER_SIZE, IOUtils.copy(ois, NullOutputStream.INSTANCE, bufferSize));
        }
        assertEquals(IOUtils.DEFAULT_BUFFER_SIZE, lengthObserver.getTotal());
        assertEquals(1, methodCountObserver.getClosedCount());
        assertEquals(1, methodCountObserver.getFinishedCount());
        assertEquals(0, methodCountObserver.getErrorCount());
        assertEquals(0, methodCountObserver.getDataCount());
        assertEquals(buffer.length / bufferSize, methodCountObserver.getDataBufferCount());
    }

    @Test
    void testNotificationCallbacksBufferSize1() throws Exception {
        testNotificationCallbacks(1);
    }

    @Test
    void testNotificationCallbacksBufferSize2() throws Exception {
        testNotificationCallbacks(2);
    }

    @Test
    void testNotificationCallbacksBufferSizeDefault() throws Exception {
        testNotificationCallbacks(IOUtils.DEFAULT_BUFFER_SIZE);
    }

    @Test
    void testReadAfterClose_ByteArrayInputStream() throws Exception {
        try (InputStream in = createInputStream()) {
            in.close();
            assertNotEquals(IOUtils.EOF, in.read());
        }
    }

    @SuppressWarnings("resource")
    @Test
    void testReadAfterClose_ChannelInputStream() throws Exception {
        try (InputStream in = createInputStream(Files.newInputStream(Paths.get("src/test/resources/org/apache/commons/io/abitmorethan16k.txt")))) {
            in.close();
            // ChannelInputStream throws when closed
            assertThrows(IOException.class, in::read);
        }
    }

    /**
     * Tests that the default {@link Observer} implementation rethrows the original read error unchanged.
     */
    @Test
    void testDefaultObserverRethrowsOriginalReadError() {
        final IOException readException = new IOException("underlying read failure");
        final ObservableInputStream ois = new ObservableInputStream(new BrokenInputStream(readException), new Observer() {
            // default behavior
        });
        final IOException thrown = assertThrows(IOException.class, ois::read);
        assertSame(readException, thrown);
        assertEquals(0, thrown.getSuppressed().length);
    }

    /**
     * Tests that failures of several observers are aggregated as independent causes: the first failure is thrown,
     * the others are suppressed on it, and all observers are still notified.
     */
    @Test
    void testObserverFailuresAggregatedAsSuppressedCauses() throws Exception {
        final IOException failure1 = new IOException("observer 1 failure");
        final IOException failure2 = new IOException("observer 2 failure");
        final Observer observer1 = new Observer() {
            @Override
            public void data(final int value) throws IOException {
                throw failure1;
            }
        };
        final Observer observer2 = new Observer() {
            @Override
            public void data(final int value) throws IOException {
                throw failure2;
            }
        };
        final MethodCountObserver stillNotified = new MethodCountObserver();
        try (ObservableInputStream ois = new ObservableInputStream(new ByteArrayInputStream(new byte[] { 1 }), observer1, observer2, stillNotified)) {
            final IOException thrown = assertThrows(IOException.class, ois::read);
            assertSame(failure1, thrown);
            assertArrayEquals(new Throwable[] { failure2 }, thrown.getSuppressed());
            assertEquals(1, stillNotified.getDataCount());
        }
    }

    /**
     * Tests that the original read error takes priority over an observer failure: the read error is thrown and the
     * observer failure is queryable as a suppressed exception, for all three read methods.
     */
    @Test
    void testReadErrorTakesPriorityOverObserverError() {
        final IOException observerException = new IOException("observer failure");
        final Observer failingObserver = new Observer() {
            @Override
            public void error(final IOException exception) throws IOException {
                throw observerException;
            }
        };
        final IOException readException1 = new IOException("underlying read failure");
        IOException thrown = assertThrows(IOException.class,
            () -> new ObservableInputStream(new BrokenInputStream(readException1), failingObserver).read());
        assertSame(readException1, thrown);
        assertArrayEquals(new Throwable[] { observerException }, thrown.getSuppressed());
        final IOException readException2 = new IOException("underlying read failure");
        thrown = assertThrows(IOException.class,
            () -> new ObservableInputStream(new BrokenInputStream(readException2), failingObserver).read(new byte[1]));
        assertSame(readException2, thrown);
        assertArrayEquals(new Throwable[] { observerException }, thrown.getSuppressed());
        final IOException readException3 = new IOException("underlying read failure");
        thrown = assertThrows(IOException.class,
            () -> new ObservableInputStream(new BrokenInputStream(readException3), failingObserver).read(new byte[1], 0, 1));
        assertSame(readException3, thrown);
        assertArrayEquals(new Throwable[] { observerException }, thrown.getSuppressed());
    }

    /**
     * Tests that reentrant state queries and mutations from within a callback neither deadlock nor break the
     * notification round in progress.
     */
    @Test
    void testReentrantStateQueryFromCallback() throws Exception {
        final ObservableInputStream ois = new ObservableInputStream(new ByteArrayInputStream(new byte[] { 1, 2 }));
        final AtomicBoolean queried = new AtomicBoolean();
        final Observer reentrant = new Observer() {
            @Override
            public void data(final int value) {
                queried.set(ois.getObservers().contains(this));
                ois.removeAllObservers();
            }
        };
        ois.add(reentrant);
        assertEquals(1, ois.read());
        assertTrue(queried.get());
        assertTrue(ois.getObservers().isEmpty());
        // Removal took effect after the notifying event: no observer sees the next read.
        assertEquals(2, ois.read());
        ois.close();
    }

    /**
     * Tests that removing an observer from within a callback has a deterministic boundary: the removed observer
     * still receives the event of the notification round in progress, but no events afterwards.
     */
    @Test
    void testRemoveHasDeterministicBoundary() throws Exception {
        final DataViewObserver removed = new DataViewObserver();
        final ObservableInputStream ois = new ObservableInputStream(new ByteArrayInputStream(new byte[] { 1, 2, 3 }));
        final Observer remover = new Observer() {
            @Override
            public void data(final int value) {
                ois.remove(removed);
            }
        };
        ois.add(remover);
        ois.add(removed);
        assertEquals(1, ois.read());
        assertEquals(1, removed.lastValue, "snapshot of the round in progress still includes the removed observer");
        assertEquals(2, ois.read());
        assertEquals(1, removed.lastValue, "removal only affects events after the removing callback");
        assertEquals(3, ois.read());
        assertEquals(1, removed.lastValue);
        ois.close();
    }

    /**
     * Tests the explicit observer strategy on three read events: data, data, EOF. Each read delivers its byte
     * exactly once, EOF is notified as {@code finished()} and is not {@code closed()}, and the borrowed underlying
     * stream is not closed at EOF.
     */
    @Test
    void testThreeReadEventsEofIsNotClose() throws Exception {
        final CloseTrackingInputStream underlying = new CloseTrackingInputStream(new byte[] { 1, 2 });
        final List<String> events = new ArrayList<>();
        final Observer recorder = new Observer() {
            @Override
            public void closed() {
                events.add("closed");
            }

            @Override
            public void data(final int value) {
                events.add("data(" + value + ")");
            }

            @Override
            public void finished() {
                events.add("finished");
            }
        };
        final ObservableInputStream ois = new ObservableInputStream(underlying, recorder);
        assertEquals(1, ois.read());
        assertEquals(2, ois.read());
        assertEquals(IOUtils.EOF, ois.read());
        // Three read events: two data callbacks, one EOF callback; EOF is not close.
        assertEquals(Arrays.asList("data(1)", "data(2)", "finished"), events);
        assertFalse(underlying.closed, "EOF must not close the borrowed underlying stream");
        ois.close();
        assertEquals(Arrays.asList("data(1)", "data(2)", "finished", "closed"), events);
        assertTrue(underlying.closed);
    }

}

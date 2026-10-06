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
 * <p>
 * Local command for this test class only: {@code mvn -Dtest=ObservableInputStreamTest test}
 * </p>
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
     * Tests that an underlying read failure stays the primary exception while an observer failure for the same event
     * is aggregated as an independent suppressed cause, so both remain queryable.
     */
    @Test
    void testReadFailurePrimaryAndObserverFailureSuppressed() throws IOException {
        final CustomIOException readFailure = new CustomIOException("read failure");
        final CustomIOException observerFailure = new CustomIOException("observer failure");
        final InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw readFailure;
            }
        };
        final Observer observer = new Observer() {
            @Override
            public void error(final IOException exception) throws IOException {
                throw observerFailure;
            }
        };
        try (ObservableInputStream ois = new ObservableInputStream(broken, observer)) {
            final IOException thrown = assertThrows(IOException.class, ois::read);
            assertSame(readFailure, thrown);
            assertArrayEquals(new Throwable[] { observerFailure }, thrown.getSuppressed());
        }
    }

    /**
     * Tests that a close failure of the underlying stream stays primary while an observer failure is aggregated as a
     * suppressed cause.
     */
    @Test
    void testCloseFailurePrimaryAndObserverFailureSuppressed() {
        final CustomIOException closeFailure = new CustomIOException("close failure");
        final CustomIOException observerFailure = new CustomIOException("observer failure");
        final InputStream brokenOnClose = new InputStream() {
            @Override
            public int read() {
                return IOUtils.EOF;
            }

            @Override
            public void close() throws IOException {
                throw closeFailure;
            }
        };
        final Observer observer = new Observer() {
            @Override
            public void error(final IOException exception) throws IOException {
                throw observerFailure;
            }
        };
        final ObservableInputStream ois = new ObservableInputStream(brokenOnClose, observer);
        final IOException thrown = assertThrows(IOException.class, ois::close);
        assertSame(closeFailure, thrown);
        assertArrayEquals(new Throwable[] { observerFailure }, thrown.getSuppressed());
    }

    /**
     * Tests that all observers are notified of an event even when some of them fail, and that the observer failures
     * are aggregated as independent suppressed causes on the first failure.
     */
    @Test
    void testObserverFailuresAggregatedAsIndependentCauses() throws Exception {
        final CustomIOException failure0 = new CustomIOException("observer failure 0");
        final CustomIOException failure1 = new CustomIOException("observer failure 1");
        final MethodCountObserver recording = new MethodCountObserver();
        final Observer failing0 = new Observer() {
            @Override
            public void data(final int value) throws IOException {
                throw failure0;
            }
        };
        final Observer failing1 = new Observer() {
            @Override
            public void data(final int value) throws IOException {
                throw failure1;
            }
        };
        try (ObservableInputStream ois = new ObservableInputStream(new ByteArrayInputStream(new byte[] { 1, 2 }), failing0, recording, failing1)) {
            final IOException thrown = assertThrows(IOException.class, ois::read);
            assertSame(failure0, thrown);
            assertArrayEquals(new Throwable[] { failure1 }, thrown.getSuppressed());
            // The failing observers did not prevent the remaining observer from being notified.
            assertEquals(1, recording.getDataCount());
            // The borrowed stream is not closed as a side effect of observer failures.
            ois.remove(failing0);
            ois.remove(failing1);
            assertEquals(2, ois.read());
            assertEquals(2, recording.getDataCount());
        }
    }

    /**
     * Tests that an observer failure during a read does not close the borrowed underlying stream.
     */
    @Test
    void testObserverFailureDoesNotCloseBorrowedStream() throws Exception {
        final AtomicBoolean underlyingClosed = new AtomicBoolean();
        final InputStream borrowed = new ByteArrayInputStream(new byte[] { 1, 2 }) {
            @Override
            public void close() throws IOException {
                underlyingClosed.set(true);
                super.close();
            }
        };
        final Observer failing = new Observer() {
            @Override
            public void data(final int value) throws IOException {
                throw new CustomIOException("observer failure");
            }
        };
        final ObservableInputStream ois = new ObservableInputStream(borrowed, failing);
        assertThrows(CustomIOException.class, ois::read);
        assertFalse(underlyingClosed.get(), "observer failure must not close the borrowed stream");
        ois.remove(failing);
        assertEquals(2, ois.read());
        ois.close();
        assertTrue(underlyingClosed.get());
    }

    /**
     * Tests that the default {@link Observer#error(IOException)} behavior is preserved: it rethrows the passed
     * exception, which stays the primary (and only) failure.
     */
    @Test
    void testDefaultObserverErrorRethrowsSameInstance() throws IOException {
        final CustomIOException readFailure = new CustomIOException("read failure");
        final InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw readFailure;
            }
        };
        try (ObservableInputStream ois = new ObservableInputStream(broken, new Observer() {
            // default behavior
        })) {
            final IOException thrown = assertThrows(IOException.class, ois::read);
            assertSame(readFailure, thrown);
            assertEquals(0, thrown.getSuppressed().length);
        }
    }

    /**
     * Tests that EOF is signaled exactly once and is never conflated with the close notification.
     */
    @Test
    void testFinishedOnceAndDistinctFromClosed() throws Exception {
        final MethodCountObserver observer = new MethodCountObserver();
        try (ObservableInputStream ois = new ObservableInputStream(new ByteArrayInputStream(new byte[] { 1 }), observer)) {
            assertEquals(1, ois.read());
            assertEquals(IOUtils.EOF, ois.read());
            assertEquals(IOUtils.EOF, ois.read());
            assertEquals(IOUtils.EOF, ois.read(new byte[4]));
            assertEquals(1, observer.getFinishedCount());
            assertEquals(0, observer.getClosedCount());
            assertTrue(ois.isFinished());
            ois.close();
            assertEquals(1, observer.getFinishedCount());
            assertEquals(1, observer.getClosedCount());
            ois.close();
            assertEquals(1, observer.getClosedCount());
        }
    }

    /**
     * Three-read event example: the first two reads each deliver their original byte exactly once, the third read
     * hits EOF and signals {@link Observer#finished()} exactly once; only the explicit {@code close()} signals
     * {@link Observer#closed()}.
     */
    @Test
    void testThreeReadEvents() throws Exception {
        final List<String> events = new ArrayList<>();
        final Observer observer = new Observer() {
            @Override
            public void data(final int value) throws IOException {
                events.add("data:" + value);
            }

            @Override
            public void finished() throws IOException {
                events.add("finished");
            }

            @Override
            public void closed() throws IOException {
                events.add("closed");
            }
        };
        try (ObservableInputStream ois = new ObservableInputStream(new ByteArrayInputStream(new byte[] { 7, 8 }), observer)) {
            assertEquals(7, ois.read());
            assertEquals(8, ois.read());
            assertEquals(IOUtils.EOF, ois.read());
            assertEquals(Arrays.asList("data:7", "data:8", "finished"), events);
            ois.close();
            assertEquals(Arrays.asList("data:7", "data:8", "finished", "closed"), events);
        }
    }

    /**
     * Tests that removing an observer from within a callback has a deterministic boundary: the current notification
     * round completes against its snapshot, and the removal only affects subsequent events.
     */
    @Test
    void testRemoveObserverBoundaryDuringCallback() throws Exception {
        final MethodCountObserver removed = new MethodCountObserver();
        final ObservableInputStream[] ref = new ObservableInputStream[1];
        final Observer removing = new Observer() {
            @Override
            public void data(final int value) throws IOException {
                ref[0].remove(removed);
            }
        };
        try (ObservableInputStream ois = new ObservableInputStream(new ByteArrayInputStream(new byte[] { 1, 2 }), removing, removed)) {
            ref[0] = ois;
            assertEquals(1, ois.read());
            // The removal happened during the first event, but the snapshot still delivered that event.
            assertEquals(1, removed.getDataCount());
            assertEquals(2, ois.read());
            // The removal boundary: only subsequent events are affected.
            assertEquals(1, removed.getDataCount());
        }
    }

    /**
     * Tests that observer callbacks may re-enter state queries without deadlocking, and that the internal state is
     * already settled when the callback runs.
     */
    @Test
    void testReentrantStateQueryFromCallback() throws Exception {
        final AtomicBoolean dataQueryOk = new AtomicBoolean();
        final AtomicBoolean finishedQueryOk = new AtomicBoolean();
        final ObservableInputStream[] ref = new ObservableInputStream[1];
        final Observer observer = new Observer() {
            @Override
            public void data(final int value) throws IOException {
                dataQueryOk.set(!ref[0].isFinished() && !ref[0].getObservers().isEmpty());
            }

            @Override
            public void finished() throws IOException {
                // The EOF state was settled before this callback ran.
                finishedQueryOk.set(ref[0].isFinished() && !ref[0].getObservers().isEmpty());
            }
        };
        try (ObservableInputStream ois = new ObservableInputStream(new ByteArrayInputStream(new byte[] { 1 }), observer)) {
            ref[0] = ois;
            assertEquals(1, ois.read());
            assertEquals(IOUtils.EOF, ois.read());
            assertTrue(dataQueryOk.get());
            assertTrue(finishedQueryOk.get());
        }
    }

}

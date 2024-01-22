/**
 * Copyright Pravega Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.pravega.client.stream.impl;


import io.pravega.client.ClientConfig;
import io.pravega.client.control.impl.Controller;
import io.pravega.client.security.auth.DelegationTokenProviderFactory;
import io.pravega.client.segment.impl.EndOfSegmentException;
import io.pravega.client.segment.impl.Segment;
import io.pravega.client.segment.impl.SegmentInputStreamFactory;
import io.pravega.client.segment.impl.SegmentMetadataClient;
import io.pravega.client.segment.impl.SegmentMetadataClientFactory;
import io.pravega.client.segment.impl.SegmentInputStream;
import io.pravega.client.segment.impl.SegmentOutputStream;
import io.pravega.client.segment.impl.SegmentTruncatedException;
import io.pravega.client.stream.EventWriterConfig;
import io.pravega.client.stream.EventReadWithStatus;
import io.pravega.client.stream.SegmentReader;
import io.pravega.client.stream.TruncatedDataException;
import io.pravega.client.stream.mock.MockConnectionFactoryImpl;
import io.pravega.client.stream.mock.MockController;
import io.pravega.client.stream.mock.MockSegmentStreamFactory;
import io.pravega.common.concurrent.Futures;
import io.pravega.shared.protocol.netty.PravegaNodeUri;
import io.pravega.test.common.AssertExtensions;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * Test case for
 */
public class SegmentReaderTest {

    private final JavaSerializer<String> stringSerializer = new JavaSerializer<>();

    private Controller controller = null;
    private MockConnectionFactoryImpl connectionFactory;


    @Before
    public void setUp() throws Exception {
        PravegaNodeUri uri = new PravegaNodeUri("endpoint", 12345);
        this.connectionFactory = new MockConnectionFactoryImpl();
        this.controller = new MockController(uri.getEndpoint(), uri.getPort(), connectionFactory, true);
    }

    @After
    public void tearDown() throws Exception {
        if (this.controller != null) {
            this.controller.close();
        }
        if (this.connectionFactory != null) {
            this.connectionFactory.close();
        }
    }

    @Test(timeout = 10000)
    public void read() {
        long timeout = 1000;
        MockSegmentStreamFactory factory = new MockSegmentStreamFactory();
        Segment segment = new Segment("Scope", "Stream", 1);
        EventWriterConfig config = EventWriterConfig.builder().build();
        SegmentOutputStream outputStream = factory.createOutputStreamForSegment(segment, c -> { }, config, DelegationTokenProviderFactory.createWithEmptyToken());
        sendData("1", outputStream);
        sendData("2", outputStream);
        sendData("3", outputStream);
        SegmentReader<String> segmentReader = new SegmentReaderImpl<>(factory, segment, stringSerializer, 0,
                ClientConfig.builder().build(), controller, factory);
        EventReadWithStatus<String> status = segmentReader.read(timeout);
        assertEquals("1", status.getEvent());
        assertEquals(SegmentReader.Status.AVAILABLE_NOW, status.getStatus());
        status = segmentReader.read(timeout);
        assertEquals("2", status.getEvent());
        assertEquals(SegmentReader.Status.AVAILABLE_NOW, status.getStatus());
        status = segmentReader.read(timeout);
        assertEquals("3", status.getEvent());
        assertEquals(SegmentReader.Status.AVAILABLE_NOW, status.getStatus());

        status = segmentReader.read(timeout);
        assertNull( status.getEvent());
        assertEquals(SegmentReader.Status.AVAILABLE_LATER, status.getStatus());

    }

    private void sendData(String data, SegmentOutputStream outputStream) {
        outputStream.write(PendingEvent.withHeader("routingKey", stringSerializer.serialize(data), new CompletableFuture<>()));
    }

    @Test(timeout = 10000)
    public void testReadWithException() throws EndOfSegmentException, SegmentTruncatedException {
        Segment segment = new Segment("Scope", "Stream", 1);
        SegmentInputStreamFactory factory = mock(SegmentInputStreamFactory.class);
        SegmentInputStream segmentInputStream = mock(SegmentInputStream.class);

        SegmentMetadataClient metadataClient = mock(SegmentMetadataClient.class);
        SegmentMetadataClientFactory segmentMetadataClientFactory = mock(SegmentMetadataClientFactory.class);

        doReturn(segmentInputStream).when(factory).createInputStreamForSegment(eq(segment), any(), eq(0L));
        doReturn(metadataClient).when(segmentMetadataClientFactory).createSegmentMetadataClient(eq(segment), any());
        doNothing().when(segmentInputStream).setOffset(anyLong());
        doReturn(CompletableFuture.completedFuture(0L)).when(metadataClient).fetchCurrentSegmentHeadOffset();

        SegmentReader<String> segmentReader = new SegmentReaderImpl<>(factory, segment, stringSerializer, 0,
                ClientConfig.builder().build(), controller, segmentMetadataClientFactory);

        doReturn(2).when(segmentInputStream).bytesInBuffer();
        doReturn(CompletableFuture.completedFuture(null)).when(segmentInputStream).fillBuffer();

        EventReadWithStatus<String> status = segmentReader.read(1000);
        assertNull(status.getEvent());
        assertEquals(SegmentReader.Status.AVAILABLE_LATER, status.getStatus());

        doReturn(-1).when(segmentInputStream).bytesInBuffer();
        doThrow(new EndOfSegmentException()).when(segmentInputStream).read(any(), anyLong());

        status = segmentReader.read(1000);
        assertNull(status.getEvent());
        assertEquals(SegmentReader.Status.FINISHED, status.getStatus());

        doReturn(-1).when(segmentInputStream).bytesInBuffer();
        doThrow(new SegmentTruncatedException()).when(segmentInputStream).read(any(), anyLong());
        assertThrows(TruncatedDataException.class, () -> segmentReader.read(1000));

        segmentReader.isAvailable().join();

        doReturn(-1).when(segmentInputStream).bytesInBuffer();

        doReturn(Futures.failedFuture(new SegmentTruncatedException())).when(segmentInputStream).fillBuffer();
        AssertExtensions.assertFutureThrows("IsDataAvailable", segmentReader.isAvailable(), e -> e instanceof TruncatedDataException);

        doReturn(Futures.failedFuture(new EndOfSegmentException())).when(segmentInputStream).fillBuffer();
        AssertExtensions.assertFutureThrows("IsDataAvailable", segmentReader.isAvailable(), e -> e instanceof EndOfSegmentException);
    }
}
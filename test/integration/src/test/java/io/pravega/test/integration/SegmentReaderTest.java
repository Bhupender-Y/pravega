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

package io.pravega.test.integration;

import com.google.common.collect.ImmutableMap;
import io.pravega.client.ClientConfig;
import io.pravega.client.admin.SegmentReaderManager;
import io.pravega.client.connection.impl.ConnectionFactory;
import io.pravega.client.connection.impl.SocketConnectionFactoryImpl;
import io.pravega.client.control.impl.Controller;
import io.pravega.client.segment.impl.EndOfSegmentException;
import io.pravega.client.segment.impl.Segment;
import io.pravega.client.stream.EventStreamWriter;
import io.pravega.client.stream.EventWriterConfig;
import io.pravega.client.stream.ScalingPolicy;
import io.pravega.client.stream.SegmentReader;
import io.pravega.client.stream.Stream;
import io.pravega.client.stream.StreamConfiguration;
import io.pravega.client.stream.StreamCut;
import io.pravega.client.stream.TruncatedDataException;
import io.pravega.client.stream.impl.ClientFactoryImpl;
import io.pravega.client.stream.impl.JavaSerializer;
import io.pravega.client.stream.impl.StreamCutImpl;
import io.pravega.segmentstore.contracts.StreamSegmentStore;
import io.pravega.segmentstore.contracts.tables.TableStore;
import io.pravega.segmentstore.server.host.handler.IndexAppendProcessor;
import io.pravega.segmentstore.server.host.handler.PravegaConnectionListener;
import io.pravega.segmentstore.server.store.ServiceBuilder;
import io.pravega.segmentstore.server.store.ServiceBuilderConfig;
import io.pravega.test.common.TestUtils;
import io.pravega.test.common.TestingServerStarter;
import io.pravega.test.integration.utils.ControllerWrapper;
import lombok.Cleanup;
import lombok.extern.slf4j.Slf4j;
import org.apache.curator.test.TestingServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.URI;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.stream.IntStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Integration test for Segment Reader.
 */
@Slf4j
public class SegmentReaderTest {

    protected final int controllerPort = TestUtils.getAvailableListenPort();
    protected final String serviceHost = "localhost";
    protected final int servicePort = TestUtils.getAvailableListenPort();
    protected final int containerCount = 4;

    protected TestingServer zkTestServer;

    private PravegaConnectionListener server;
    private ControllerWrapper controllerWrapper;
    private ServiceBuilder serviceBuilder;
    private JavaSerializer<Integer> serializer;
    private ClientConfig clientConfig;

    @Before
    public void setUp() throws Exception {
        zkTestServer = new TestingServerStarter().start();

        // Create and start segment store service
        serviceBuilder = createServiceBuilder();
        serviceBuilder.initialize();
        StreamSegmentStore store = serviceBuilder.createStreamSegmentService();
        TableStore tableStore = serviceBuilder.createTableStoreService();
        server = new PravegaConnectionListener(false, servicePort, store, tableStore, serviceBuilder.getLowPriorityExecutor(),
                new IndexAppendProcessor(serviceBuilder.getLowPriorityExecutor(), store));
        server.startListening();

        // Create and start controller service
        controllerWrapper = createControllerWrapper();
        controllerWrapper.awaitRunning();
        serializer = new JavaSerializer<>();

        clientConfig = createClientConfig();
    }

    @After
    public void tearDown() throws Exception {
        controllerWrapper.close();
        server.close();
        serviceBuilder.close();
        zkTestServer.close();
    }

    //region Factory methods that may be overridden by subclasses.

    protected ServiceBuilder createServiceBuilder() {
        return ServiceBuilder.newInMemoryBuilder(ServiceBuilderConfig.getDefaultConfig());
    }

    protected ControllerWrapper createControllerWrapper() {
        return new ControllerWrapper(zkTestServer.getConnectString(),
                false, true,
                controllerPort,
                serviceHost,
                servicePort,
                containerCount, -1);
    }

    protected ClientConfig createClientConfig() {
        return ClientConfig.builder()
                .controllerURI(URI.create(controllerUri()))
                .build();
    }

    protected String controllerUri() {
        return "tcp://localhost:" + controllerPort;
    }

    //endregion

    @Test(timeout = 50000)
    public void testSegmentReadOnSealedStream() throws ExecutionException, InterruptedException {
        String scope = "testSegmentReaderScope";
        String stream = "testSegmentReaderStream";
        int noOfEvents = 50;
        long timeout = 1000;
        int readEventCount = 0;
        createStream(scope, stream, 1);
        writeEventsIntoStream(noOfEvents, scope, stream);

        log.info("Creating segment reader manager.");
        @Cleanup
        SegmentReaderManager<Integer> segmentReaderManager = SegmentReaderManager.create(clientConfig, serializer);
        List<SegmentReader<Integer>> segmentReaderList = segmentReaderManager.getSegmentReaders(Stream.of(scope, stream), null).get();
        assertEquals(1, segmentReaderList.size());

        boolean isSealed = controllerWrapper.getController().sealStream(scope, stream).join();
        assertTrue("isSealed", isSealed);

        SegmentReader<Integer> segmentReader = segmentReaderList.get(0);
        log.info("Starting reading the events.");
        while (true) {
            try {
                segmentReader.read(timeout);
                readEventCount++;
            } catch (EndOfSegmentException e) {
                break;
            }
        }
        log.info("Reading of events is successful.");
        assertEquals(noOfEvents, readEventCount);
    }

    @Test(timeout = 50000)
    public void testSegmentReadWithTruncatedStream() throws ExecutionException, InterruptedException {
        String scope = "testSegmentReaderWithTruncatedScope";
        String stream = "testSegmentReaderWithTruncatedStream";
        int noOfEvents = 10;
        long timeout = 1000;
        int readEventCount = 0;
        createStream(scope, stream, 1);
        writeEventsIntoStream(noOfEvents, scope, stream);

        StreamCut streamCut = new StreamCutImpl(Stream.of(scope, stream), ImmutableMap.of(new Segment(scope, stream, 0L), 60L));
        log.info("Truncating two events from stream");
        boolean isTruncated = controllerWrapper.getController().truncateStream(scope, stream, streamCut).join();
        assertTrue("isTruncated", isTruncated);

        log.info("Sealing stream");
        boolean isSealed = controllerWrapper.getController().sealStream(scope, stream).join();
        assertTrue("isSealed", isSealed);

        streamCut = new StreamCutImpl(Stream.of(scope, stream), ImmutableMap.of(new Segment(scope, stream, 0L), 0L));

        log.info("Creating segment reader manager.");
        @Cleanup
        SegmentReaderManager<Integer> segmentReaderManager = SegmentReaderManager.create(clientConfig, serializer);
        //Try to read the segment from offset 0.
        List<SegmentReader<Integer>> segmentReaderList = segmentReaderManager.getSegmentReaders(Stream.of(scope, stream), streamCut).get();
        assertEquals(1, segmentReaderList.size());
        SegmentReader<Integer> segmentReader = segmentReaderList.get(0);

        while (true) {
            try {
                segmentReader.read(timeout);
                readEventCount++;
            } catch (EndOfSegmentException e) {
                log.warn("End of segment reached");
                break;
            } catch (TruncatedDataException e) {
                log.warn("Truncated data found.", e);
            }
        }

        assertEquals(noOfEvents - 2, readEventCount);
    }

    private void createStream(String scope, String stream, int numOfSegments) {
        log.info("Creating stream {}/{} with number of segments {}.", scope, stream, numOfSegments);
        StreamConfiguration config = StreamConfiguration.builder()
                .scalingPolicy(ScalingPolicy.fixed(numOfSegments))
                .build();
        controllerWrapper.getControllerService().createScope(scope, 0L).join();
        assertTrue("Create Stream operation", controllerWrapper.getController().createStream(scope, stream, config).join());
    }

    private void writeEventsIntoStream(int numberOfEvents, String scope, String stream) {
        String dataOfSize30 = "data of size 30"; // data length = 22 bytes , header = 8 bytes
        Controller controller = controllerWrapper.getController();
        @Cleanup
        ConnectionFactory connectionFactory = new SocketConnectionFactoryImpl(ClientConfig.builder().build());
        @Cleanup
        ClientFactoryImpl clientFactory = new ClientFactoryImpl(scope, controller, connectionFactory);
        @Cleanup
        EventStreamWriter<String> writer = clientFactory.createEventWriter(stream, new JavaSerializer<>(),
                EventWriterConfig.builder().build());
        IntStream.range(0, numberOfEvents).forEach(v -> writer.writeEvent(dataOfSize30).join());
    }


}

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
import io.pravega.client.security.auth.DelegationTokenProvider;
import io.pravega.client.security.auth.DelegationTokenProviderFactory;
import io.pravega.client.segment.impl.EventSegmentReader;
import io.pravega.client.segment.impl.SegmentInputStreamFactory;
import io.pravega.client.segment.impl.SegmentMetadataClient;
import io.pravega.client.segment.impl.SegmentMetadataClientFactory;
import io.pravega.client.segment.impl.NoSuchSegmentException;
import io.pravega.client.segment.impl.SegmentTruncatedException;
import io.pravega.client.segment.impl.EndOfSegmentException;
import io.pravega.client.segment.impl.ServerTimeoutException;
import io.pravega.client.segment.impl.SegmentInfo;
import io.pravega.client.stream.SegmentReader;
import io.pravega.client.stream.Serializer;
import io.pravega.client.stream.TruncatedDataException;
import io.pravega.client.segment.impl.Segment;
import io.pravega.common.concurrent.Futures;
import io.pravega.common.util.Retry;
import io.pravega.shared.security.auth.AccessOperation;
import lombok.extern.slf4j.Slf4j;


import java.nio.ByteBuffer;
import java.util.concurrent.TimeoutException;

import static java.lang.String.format;

@Slf4j
public class SegmentReaderImpl<T> implements SegmentReader<T> {

    private final Segment segment;
    private final Serializer<T> deserializer;
    private final EventSegmentReader input;
    private final ClientConfig clientConfig;
    private final SegmentMetadataClient metadataClient;
    private final Retry.RetryWithBackoff backoffSchedule = Retry.withExpBackoff(1, 10, 9, 30000);

    public SegmentReaderImpl(SegmentInputStreamFactory factory, Segment segment, Serializer<T> deserializer,
                             long startOffset, ClientConfig clientConfig, Controller controller,
                             SegmentMetadataClientFactory segmentMetadataClientFactory) {

        this.segment = segment;
        this.deserializer = deserializer;
        this.clientConfig = clientConfig;
        DelegationTokenProvider delegationTokenProvider = DelegationTokenProviderFactory.create(controller, segment, AccessOperation.READ);
        metadataClient = segmentMetadataClientFactory.createSegmentMetadataClient(segment,
                delegationTokenProvider);
        this.input = factory.createEventReaderForSegment(segment);
        input.setOffset(startOffset);
    }

    @Override
    public T read() {
        // retry in-case of an empty ByteBuffer
        ByteBuffer read = backoffSchedule.retryWhen(t -> t instanceof TimeoutException)
                        .run(() -> {
                            try {
                                ByteBuffer buffer = input.read();
                                if (buffer == null) {
                                    log.warn("Empty buffer while reading from Segment {} at offset {}",
                                            input.getSegmentId(), input.getOffset());
                                    throw new TimeoutException(input.toString());
                                }
                                return buffer;
                            } catch (NoSuchSegmentException | SegmentTruncatedException e) {
                                handleSegmentTruncated(segment);
                                throw new TruncatedDataException("Segment " + segment + " has been truncated.");
                            } catch (EndOfSegmentException e) {
                                return null;
                            }
                        });

        return deserializer.deserialize(read);
    }

    @Override
    public Status checkStatus() {
        SegmentInfo segmentInfo;
        try {
            segmentInfo = Futures.getThrowingExceptionWithTimeout(metadataClient.getSegmentInfo(), clientConfig.getConnectTimeoutMilliSec());
        } catch (TimeoutException e) {
            throw new ServerTimeoutException(format("Timeout occurred while reading the segment Info for segment {%s}", segment));
        }

        if (input.getOffset() > segmentInfo.getWriteOffset()) {
            throw new IllegalStateException("startOffset: " + input.getOffset() + " is grater than endOffset: " + segmentInfo.getWriteOffset());
        }

        Status status = null;
        if (input.getOffset() == segmentInfo.getWriteOffset()) {
            log.debug("No new events are available to read. Offset read : {}, End offset : {}, IsSegmentSealed: {}",
                    input.getOffset(), segmentInfo.getWriteOffset(), segmentInfo.isSealed());
            if (segmentInfo.isSealed()) {
                status = Status.FINISHED;
            } else {
                status = Status.AVAILABLE_LATER;
            }
        }
        if (input.getOffset() < segmentInfo.getWriteOffset()) {
            status = Status.AVAILABLE_NOW;
        }
        return status;
    }

    private void handleSegmentTruncated(Segment segmentId)  {
        log.info("{} encountered truncation for segment while read{} ", this, segmentId);
        try {
            input.setOffset(Futures.getThrowingExceptionWithTimeout(metadataClient.fetchCurrentSegmentHeadOffset(),
                    clientConfig.getConnectTimeoutMilliSec()));
        } catch (TimeoutException te) {
            log.warn("A timeout has occurred while attempting to retrieve segment information from the server for segment {}", segmentId);
        }
    }

    @Override
    public void close() {
        metadataClient.close();
        input.close();
    }

}

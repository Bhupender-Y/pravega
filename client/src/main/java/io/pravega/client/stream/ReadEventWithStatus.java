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

package io.pravega.client.stream;

/**
 * An event that was read from a segment.
 * Along with event, it will also provide the status {@link io.pravega.client.stream.SegmentReader.Status} of segment reader.
 */
public interface ReadEventWithStatus<T> {
    /**
     * Returns the event that is wrapped in this EventRead or null a timeout occurred or if a checkpoint was requested.
     *
     * @return The event itself.
     */
    T getEvent();

    /**
     * Returns the status of events in specific segment.
     *
     * @return The status of segment.
     */
    SegmentReader.Status getStatus();
}

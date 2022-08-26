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
package io.pravega.controller.server.bucket;

import com.google.common.base.Preconditions;
import io.pravega.common.Exceptions;
import io.pravega.common.cluster.Cluster;
import io.pravega.common.cluster.ClusterType;
import io.pravega.common.cluster.zkImpl.ClusterZKImpl;
import io.pravega.controller.store.stream.BucketControllerMap;
import io.pravega.controller.store.stream.BucketStore;
import io.pravega.controller.store.stream.ZookeeperBucketStore;
import io.pravega.controller.util.RetryHelper;
import io.pravega.controller.util.ZKUtils;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.curator.framework.recipes.cache.NodeCache;
import org.apache.curator.framework.recipes.cache.PathChildrenCache;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheListener;
import org.apache.curator.framework.recipes.leader.LeaderSelector;
import org.apache.curator.utils.ZKPaths;

@SuppressWarnings("deprecation")
@Slf4j
public class ZooKeeperBucketManager extends BucketManager {
    private final ZookeeperBucketStore bucketStore;
    private final ConcurrentMap<BucketStore.ServiceType, PathChildrenCache> bucketOwnershipCacheMap;
    private LeaderSelector leaderSelector;
    private final String processId;

    private final BucketManagerLeader bucketManagerLeader;
    private final Cluster cluster;

    ZooKeeperBucketManager(String processId, ZookeeperBucketStore bucketStore, BucketStore.ServiceType serviceType, ScheduledExecutorService executor,
                           Function<Integer, BucketService> bucketServiceSupplier, BucketManagerLeader bucketManagerLeader) {
        super(processId, serviceType, executor, bucketServiceSupplier, bucketStore);
        bucketOwnershipCacheMap = new ConcurrentHashMap<>();
        this.bucketStore = bucketStore;
        this.processId = processId;
        this.bucketManagerLeader = bucketManagerLeader;
        this.cluster = new ClusterZKImpl(bucketStore.getClient(), ClusterType.CONTROLLER);
    }

    /**
     * Get the health status.
     *
     * @return true if zookeeper is connected.
     */
    @Override
    public boolean isHealthy() {
        return this.bucketStore.isZKConnected();
    }

    @Override
    protected int getBucketCount() {
        return bucketStore.getBucketCount(getServiceType());
    }

    @Override
    public void startBucketOwnershipListener() {
        PathChildrenCache pathChildrenCache = bucketOwnershipCacheMap.computeIfAbsent(getServiceType(),
                x -> bucketStore.getServiceOwnershipPathChildrenCache(getServiceType()));

        PathChildrenCacheListener bucketListener = (client, event) -> {
            switch (event.getType()) {
                case CHILD_ADDED:
                    // no action required
                    break;
                case CHILD_REMOVED:
                    int bucketId = Integer.parseInt(ZKPaths.getNodeFromPath(event.getData().getPath()));
                    bucketStore.getBucketsForController(processId, getServiceType())
                            .thenAccept(buckets -> {
                                log.debug("{} : Buckets assigned to controller {} are {}", getServiceType(), processId, buckets);
                                if (buckets.contains(bucketId)) {
                                    RetryHelper.withIndefiniteRetriesAsync(() -> tryTakeOwnership(bucketId),
                                            e -> log.warn("{}: exception while attempting to take ownership for bucket {}: {}", getServiceType(),
                                                    bucketId, e.getMessage()), getExecutor());
                                }
                            }).whenComplete((r, e) -> log.debug("{}: Take Ownership finished with exception {}", getServiceType(), e));
                    break;
                case CONNECTION_LOST:
                    log.warn("{}: Received connectivity error", getServiceType());
                    break;
                default:
                    log.warn("Received unknown event {} on bucket root {} ", event.getType(), getServiceType());
            }
        };

        pathChildrenCache.getListenable().addListener(bucketListener);
        log.info("bucket ownership listener registered on bucket root {}", getServiceType());

        try {
            pathChildrenCache.start(PathChildrenCache.StartMode.NORMAL);
        } catch (Exception e) {
            log.error("Starting ownership listener for service {} threw exception", getServiceType(), e);
            throw Exceptions.sneakyThrow(e);
        }

    }

    @Override
    public void stopBucketOwnershipListener() {
        PathChildrenCache pathChildrenCache = bucketOwnershipCacheMap.remove(getServiceType());
        if (pathChildrenCache != null) {
            try {
                pathChildrenCache.clear();
                pathChildrenCache.close();
            } catch (IOException e) {
                log.warn("{} unable to close listener for bucket ownership", getServiceType(), e);
            }
        }
    }

    @Override
    public CompletableFuture<Void> initializeService() {
        return bucketStore.createBucketsRoot(getServiceType());
    }

    @Override
    public CompletableFuture<Void> initializeBucket(int bucket) {
        Preconditions.checkArgument(bucket < bucketStore.getBucketCount(getServiceType()));
        
        return bucketStore.createBucket(getServiceType(), bucket);
    }

    @SneakyThrows(Exception.class)
    @Override
    void addBucketControllerMapListener() {
            ZKUtils.createPathIfNotExists(bucketStore.getClient(), bucketStore.getBucketControllerMapPath(getServiceType()),
                    BucketControllerMap.EMPTY.toBytes());
            NodeCache cache = bucketStore.getBucketControllerMapNodeCache(getServiceType());
            cache.getListenable().addListener(this::handleBuckets);
            log.info("{} : Bucket controller map listener registered", getServiceType());
            cache.start(true);
            manageBuckets(cluster.getClusterMembers().size()).whenComplete((r, e)
                    -> log.debug("{} : Manage buckets completes with result : {} and exception : {}", getServiceType(), r, e));
    }

    @Override
    public CompletableFuture<Boolean> takeBucketOwnership(int bucket, String processId, Executor executor) {
        Preconditions.checkArgument(bucket < bucketStore.getBucketCount(getServiceType()));
        return bucketStore.takeBucketOwnership(getServiceType(), bucket, processId);
    }

    @Override
    public void startLeaderElection() {
        String bucketDistributorLeader = "bucketDistributorLeader";
        String leaderSubPath = ZKPaths.makePath("cluster", getServiceType().getName());
        String leaderZKPath = ZKPaths.makePath(leaderSubPath, bucketDistributorLeader);

        leaderSelector = new LeaderSelector(bucketStore.getClient(), leaderZKPath, bucketManagerLeader);
        //Listen for any zookeeper connection state changes
        bucketStore.getClient().getConnectionStateListenable().addListener(
                (curatorClient, newState) -> {
                    switch (newState) {
                        case LOST:
                            log.warn("Connection to zookeeper lost, attempting to interrrupt the leader thread");
                            leaderSelector.interruptLeadership();
                            break;
                        case SUSPENDED:
                            if (leaderSelector.hasLeadership()) {
                                log.info("Zookeeper session suspended, pausing the bucket manager");
                                bucketManagerLeader.suspend();
                            }
                            break;
                        case RECONNECTED:
                            if (leaderSelector.hasLeadership()) {
                                log.info("Zookeeper session reconnected, resume the bucket manager");
                                bucketManagerLeader.resume();
                            }
                            break;
                        //$CASES-OMITTED$
                        default:
                            log.debug("Connection state to zookeeper updated: " + newState.toString());
                    }
                }
        );
        startLeader();
    }

    @Override
    public void startLeader() {
        leaderSelector.autoRequeue();
        leaderSelector.start();
        log.debug("{} : Leader election started", getServiceType());
    }

    @SneakyThrows
    @Override
    public void stopLeader() {
        leaderSelector.interruptLeadership();
        leaderSelector.close();
        cluster.close();
        log.debug("{} : Leader election stopped", getServiceType());
    }

    private void handleBuckets() {
        manageBuckets(cluster.getClusterMembers().size()).whenComplete((r, e) -> {
            log.debug("{} : Manage bucket finished with exception {}", getServiceType(), e);
            BucketListener consumer = getListenerRef().get();
            if (consumer != null) {
                consumer.signal();
            }
        });

    }
}

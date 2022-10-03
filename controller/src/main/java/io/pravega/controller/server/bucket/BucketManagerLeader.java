package io.pravega.controller.server.bucket;

import com.google.common.base.Preconditions;
import io.pravega.common.cluster.Cluster;
import io.pravega.common.cluster.ClusterException;
import io.pravega.common.cluster.ClusterType;
import io.pravega.common.cluster.zkImpl.ClusterZKImpl;
import io.pravega.controller.store.host.HostStoreException;
import io.pravega.controller.store.stream.BucketStore;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.leader.LeaderSelectorListener;
import org.apache.curator.framework.state.ConnectionState;

/**
 * Copyright Pravega Authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
@Slf4j
public class BucketManagerLeader implements LeaderSelectorListener {

    private final BucketStore bucketStore;
    //The pravega cluster which this controller manages.
    private Cluster pravegaServiceCluster = null;
    //Semaphore to notify the leader thread to trigger a rebalance.
    private final Semaphore controllerChange = new Semaphore(0);

    //Semaphore to keep the current thread in suspended state.
    private final Semaphore suspendMonitor = new Semaphore(0);

    //Flag to check if monitor is suspended or not.
    private final AtomicBoolean suspended = new AtomicBoolean(false);
    //The minimum interval between any two rebalance operations. The minimum duration is not guaranteed when leadership
    //moves across controllers. Since this is uncommon and there are no significant side-effects to it, we don't
    //handle this scenario.
    private Duration minRebalanceInterval;

    //The controller to bucket distributor.
    @Getter
    private final BucketDistributor bucketDistributor;
    //Service type
    private final BucketStore.ServiceType serviceType;
    private final BucketManager bucketManager;

    public BucketManagerLeader(BucketStore bucketStore, int minRebalanceInterval, BucketDistributor bucketDistributor,
                               BucketStore.ServiceType serviceType, BucketManager bucketManager) {
        Preconditions.checkNotNull(bucketStore, "bucketStore");
        Preconditions.checkArgument(minRebalanceInterval >= 0, "minRebalanceInterval should not be negative");

        this.bucketStore = bucketStore;
        this.minRebalanceInterval = Duration.ofSeconds(minRebalanceInterval);
        this.bucketDistributor = bucketDistributor;
        this.serviceType = serviceType;
        this.bucketManager = bucketManager;
    }

    /**
     * Suspend the leader thread.
     */
    public void suspend() {
        suspended.set(true);
    }

    /**
     * Resume the suspended leader thread.
     */
    public void resume() {
        if (suspended.compareAndSet(true, false)) {
            suspendMonitor.release();
        }
    }

    @Override
    public void takeLeadership(CuratorFramework client) throws Exception {
        log.info("Obtained leadership to monitor the controller to buckets Mapping");

        //Attempt a rebalance whenever leadership is obtained to ensure no Controllers events are missed.
        controllerChange.release();

        //Start cluster monitor.
        pravegaServiceCluster = new ClusterZKImpl(client, ClusterType.CONTROLLER);

        //Add listener to track controller changes on the monitored pravega cluster.
        pravegaServiceCluster.addListener((type, controller) -> {
            switch (type) {
                case HOST_ADDED:
                case HOST_REMOVED:
                    //We don't keep track of the controllers and we always query for the entire set from the cluster
                    //when changes occur. This is to avoid any inconsistencies if we miss any notifications.
                    log.info("Received event: {} for host: {}. Wake up leader for rebalancing", type, controller);
                    controllerChange.release();
                    break;
                case ERROR:
                    //This event should be due to ZK connection errors and would have been received by the monitor too,
                    //hence not handling it explicitly here.
                    log.info("Received error event when monitoring the pravega host cluster, ignoring...");
                    break;
            }
        });

        //Keep looping here as long as possible to stay as the leader and exclusively monitor the pravega cluster.
        while (true) {
            try {
                if (suspended.get()) {
                    log.info("Monitor is suspended, waiting for notification to resume");
                    suspendMonitor.acquire();
                    log.info("Resuming monitor");
                }

                controllerChange.acquire();
                log.info("Received distribute buckets");

                // Wait here until distribution can be performed.
                //waitForReDistribute();

                // Clear all events that has been received until this point since this will be included in the current
                // distribution operation.
                controllerChange.drainPermits();
                triggerDistribution();
            } catch (InterruptedException e) {
                log.warn("Leadership interrupted, releasing monitor thread");

                //Stop watching the pravega cluster.
                pravegaServiceCluster.close();
                throw e;
            } catch (Exception e) {
                //We will not release leadership if in suspended mode.
                if (!suspended.get()) {
                    log.warn("Failed to perform distribution, relinquishing leadership");

                    //Stop watching the pravega cluster.
                    pravegaServiceCluster.close();
                    throw e;
                }
            }
        }

    }

    /**
     * Blocks until the rebalance interval. This wait serves multiple purposes:
     * -- Ensure rebalance does not happen in quick succession since its a costly cluster level operation.
     * -- Clubs multiple host events into one to reduce rebalance operations. For example:
     *      Fresh cluster start, cluster/multi-host/host restarts, etc.
     */
    private void waitForReDistribute() throws InterruptedException {
        log.info("Waiting for {} seconds before attempting to distribute", minRebalanceInterval.getSeconds());
        Thread.sleep(minRebalanceInterval.toMillis());
    }

    private void triggerDistribution() throws IOException {
        //Read the current mapping from the bucket store and write back the update after distribution.
        try {
            Set<String> currentControllers = pravegaServiceCluster.getClusterMembers().stream().map( controller ->
                                                                          controller.getHostId()).collect(Collectors.toSet());
            Map<String, Set<Integer>> newMapping = bucketDistributor.distribute(bucketStore.getBucketControllerMap(serviceType).join(),
                    currentControllers, bucketStore.getBucketCount(serviceType));
            Map<String, Set<Integer>> oldMapping = bucketStore.getBucketControllerMap(serviceType).join();
            bucketStore.updateBucketControllerMap(newMapping, serviceType);
            bucketManager.manageBuckets();
          //  hostContainerMetrics.updateHostContainerMetrics(oldMapping, newMapping);
        } catch (ClusterException e) {
            throw new IOException(e);
        } catch (Exception e) {
            throw new HostStoreException("Failed to persist bucket controller map to zookeeper", e);
        }
    }

    @Override
    public void stateChanged(CuratorFramework client, ConnectionState newState) {
        //Nothing to do here. We are already monitoring the state changes for shutdown.
        log.info("Zookeeper connection state changed to: " + newState.toString());
    }
}

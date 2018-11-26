/*
 * Copyright 2017-present Open Networking Foundation
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

package org.onosproject.vpls;

import com.google.common.collect.Maps;
import com.google.common.collect.Queues;
import com.google.common.collect.Sets;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.Service;
import org.onlab.util.Tools;
import org.onosproject.cluster.ClusterService;
import org.onosproject.cluster.LeadershipEvent;
import org.onosproject.cluster.LeadershipEventListener;
import org.onosproject.cluster.LeadershipService;
import org.onosproject.cluster.NodeId;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.intf.Interface;
import org.onosproject.net.Host;
import org.onosproject.net.host.HostService;
import org.onosproject.net.intent.Intent;
import org.onosproject.net.intent.IntentEvent;
import org.onosproject.net.intent.IntentException;
import org.onosproject.net.intent.IntentListener;
import org.onosproject.net.intent.IntentService;
import org.onosproject.net.intent.IntentUtils;
import org.onosproject.net.intent.Key;
import org.onosproject.net.intent.MultiPointToSinglePointIntent;
import org.onosproject.net.intent.SinglePointToMultiPointIntent;
import org.onosproject.vpls.api.VplsData;
import org.onosproject.vpls.api.VplsOperationException;
import org.onosproject.vpls.api.VplsOperationService;
import org.onosproject.vpls.api.VplsOperation;
import org.onosproject.vpls.api.VplsStore;
import org.onosproject.vpls.intent.VplsIntentUtility;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.onlab.util.BoundedThreadPool.newFixedThreadPool;
import static org.onlab.util.Tools.groupedThreads;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * An implementation of VplsOperationService.
 * Handles the execution order of the VPLS operations generated by the
 * application.
 */
@Component(immediate = true)
@Service
public class VplsOperationManager implements VplsOperationService {
    private static final int NUM_THREADS = 4;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected IntentService intentService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected LeadershipService leadershipService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected ClusterService clusterService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected HostService hostService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected VplsStore vplsStore;

    private final Logger log = getLogger(getClass());
    protected Map<String, Deque<VplsOperation>> pendingVplsOperations;
    protected final Map<String, VplsOperation> runningOperations = Maps.newHashMap();
    protected ScheduledExecutorService schedulerExecutor;
    protected ExecutorService workerExecutor;
    protected ApplicationId appId;
    protected boolean isLeader;
    protected NodeId localNodeId;
    protected LeadershipEventListener leadershipEventListener;

    @Activate
    public void activate() {
        appId = coreService.registerApplication(VplsManager.VPLS_APP);
        localNodeId = clusterService.getLocalNode().id();

        leadershipEventListener = new InternalLeadershipListener();
        leadershipService.addListener(leadershipEventListener);
        leadershipService.runForLeadership(appId.name());
        pendingVplsOperations = Maps.newConcurrentMap();

        // Thread pool for VplsOperationExecutor
        workerExecutor = newFixedThreadPool(NUM_THREADS,
                                            groupedThreads("onos/apps/vpls",
                                                           "worker-%d",
                                                           log));
        // A single thread pool for VplsOperationScheduler
        schedulerExecutor = Executors.newScheduledThreadPool(1,
                                                             groupedThreads("onos/apps/vpls",
                                                                            "scheduler-%d",
                                                                            log));
        // Start the scheduler
        schedulerExecutor.scheduleAtFixedRate(new VplsOperationScheduler(),
                                              0,
                                              500,
                                              TimeUnit.MILLISECONDS);

    }

    @Deactivate
    public void deactivate() {
        pendingVplsOperations.clear();
        runningOperations.clear();
        leadershipService.removeListener(leadershipEventListener);
        schedulerExecutor.shutdown();
        workerExecutor.shutdown();

        // remove all intents from VPLS application when deactivated
        Tools.stream(intentService.getIntents())
                .filter(intent -> intent.appId().equals(appId))
                .forEach(intentService::withdraw);
    }

    @Override
    public void submit(VplsOperation vplsOperation) {
        if (isLeader) {
            // Only leader can execute operation
            addVplsOperation(vplsOperation);
        }
    }

    /**
     * Adds a VPLS operation to the queue of pending operations.
     *
     * @param vplsOperation the VPLS operation to add
     */
    private void addVplsOperation(VplsOperation vplsOperation) {
        VplsData vplsData = vplsOperation.vpls();
        pendingVplsOperations.compute(vplsData.name(), (name, opQueue) -> {
            opQueue = opQueue == null ? Queues.newArrayDeque() : opQueue;

            // If the operation already exist in queue, ignore it.
            if (opQueue.contains(vplsOperation)) {
                return opQueue;
            }
            opQueue.add(vplsOperation);
            return opQueue;
        });
    }

    /**
     * Optimizes the VPLS operation queue and return a single VPLS operation to
     * execute.
     *
     * @param operations the queue to be optimized
     * @return optimized VPLS operation from the queue
     */
    protected static VplsOperation getOptimizedVplsOperation(Deque<VplsOperation> operations) {
        if (operations.isEmpty()) {
            return null;
        }
        // no need to optimize if the queue contains only one operation
        if (operations.size() == 1) {
            return operations.getFirst();
        }

        final VplsOperation firstOperation = operations.peekFirst();
        final VplsOperation lastOperation = operations.peekLast();
        final VplsOperation.Operation firstOp = firstOperation.op();
        final VplsOperation.Operation lastOp = lastOperation.op();

        if (firstOp.equals(VplsOperation.Operation.REMOVE)) {
            if (lastOp.equals(VplsOperation.Operation.REMOVE)) {
                // case 1: both first and last operation are REMOVE; do remove
                return firstOperation;
            } else if (lastOp.equals(VplsOperation.Operation.ADD)) {
                // case 2: if first is REMOVE, and last is ADD; do update
                return VplsOperation.of(lastOperation.vpls(),
                                                 VplsOperation.Operation.UPDATE);
            } else {
                // case 3: first is REMOVE, last is UPDATE; do update
                return lastOperation;
            }
        } else if (firstOp.equals(VplsOperation.Operation.ADD)) {
            if (lastOp.equals(VplsOperation.Operation.REMOVE)) {
                // case 4: first is ADD, last is REMOVE; nothing to do
                return null;
            } else if (lastOp.equals(VplsOperation.Operation.ADD)) {
                // case 5: both first and last are ADD, do add
                return VplsOperation.of(lastOperation.vpls(),
                                                 VplsOperation.Operation.ADD);
            } else {
                // case 6: first is ADD and last is update, do add
                return VplsOperation.of(lastOperation.vpls(),
                                                 VplsOperation.Operation.ADD);
            }
        } else {
            if (lastOp.equals(VplsOperation.Operation.REMOVE)) {
                // case 7: last is remove, do remove
                return lastOperation;
            } else if (lastOp.equals(VplsOperation.Operation.ADD)) {
                // case 8: do update only
                return VplsOperation.of(lastOperation.vpls(),
                                                 VplsOperation.Operation.UPDATE);
            } else {
                // case 9: from UPDATE to UPDATE
                // only need last UPDATE operation
                return VplsOperation.of(lastOperation.vpls(),
                                                 VplsOperation.Operation.UPDATE);
            }
        }
    }

    /**
     * Scheduler for VPLS operation.
     * Processes a batch of VPLS operations in a period.
     */
    class VplsOperationScheduler implements Runnable {
        private static final String UNKNOWN_STATE =
                "Unknown state {} for success consumer";
        private static final String OP_EXEC_ERR =
                "Error when executing VPLS operation {}, error: {}";

        /**
         * Process a batch of VPLS operations.
         */
        @Override
        public void run() {
            Set<String> vplsNames = pendingVplsOperations.keySet();
            vplsNames.forEach(vplsName -> {
                VplsOperation operation;
                synchronized (runningOperations) {
                    // Only one operation for a VPLS at the same time
                    if (runningOperations.containsKey(vplsName)) {
                        return;
                    }
                    Deque<VplsOperation> operations = pendingVplsOperations.remove(vplsName);
                    operation = getOptimizedVplsOperation(operations);
                    if (operation == null) {
                        // Nothing to do, this only happened when we add a VPLS
                        // and remove it before batch operations been processed.
                        return;
                    }
                    runningOperations.put(vplsName, operation);
                }

                VplsOperationExecutor operationExecutor =
                        new VplsOperationExecutor(operation);
                operationExecutor.setConsumers(
                        (vplsOperation) -> {
                            // Success consumer
                            VplsData vplsData = vplsOperation.vpls();
                            log.debug("VPLS operation success: {}", vplsOperation);
                            switch (vplsData.state()) {
                                case ADDING:
                                case UPDATING:
                                    vplsData.state(VplsData.VplsState.ADDED);
                                    vplsStore.updateVpls(vplsData);
                                    break;
                                case REMOVING:
                                    // The VPLS information does not exists in
                                    // store. No need to update the store.
                                    break;
                                default:
                                    log.warn(UNKNOWN_STATE, vplsData.state());
                                    vplsData.state(VplsData.VplsState.FAILED);
                                    vplsStore.updateVpls(vplsData);
                                    break;
                            }
                            runningOperations.remove(vplsName);
                        },
                        (vplsOperationException) -> {
                            // Error consumer
                            VplsOperation vplsOperation =
                                    vplsOperationException.vplsOperation();
                            log.warn(OP_EXEC_ERR,
                                     vplsOperation.toString(),
                                     vplsOperationException.getMessage());
                            VplsData vplsData = vplsOperation.vpls();
                            vplsData.state(VplsData.VplsState.FAILED);
                            vplsStore.updateVpls(vplsData);
                            runningOperations.remove(vplsName);
                        });
                log.debug("Applying operation: {}", operation);
                workerExecutor.execute(operationExecutor);
            });
        }
    }

    /**
     * Direction for Intent installation.
     */
    private enum Direction {
        ADD,
        REMOVE
    }

    /**
     * VPLS operation executor.
     * Installs, updates or removes Intents according to the given VPLS operation.
     */
    class VplsOperationExecutor implements Runnable {
        private static final String UNKNOWN_OP = "Unknown operation.";
        private static final String UNKNOWN_INTENT_DIR = "Unknown Intent install direction.";
        private static final int OPERATION_TIMEOUT = 10;
        private VplsOperation vplsOperation;
        private Consumer<VplsOperation> successConsumer;
        private Consumer<VplsOperationException> errorConsumer;
        private VplsOperationException error;

        public VplsOperationExecutor(VplsOperation vplsOperation) {
            this.vplsOperation = vplsOperation;
            this.error = null;
        }

        /**
         * Sets success consumer and error consumer for this executor.
         *
         * @param successConsumer the success consumer
         * @param errorConsumer the error consumer
         */
        public void setConsumers(Consumer<VplsOperation> successConsumer,
                                 Consumer<VplsOperationException> errorConsumer) {
            this.successConsumer = successConsumer;
            this.errorConsumer = errorConsumer;

        }

        @Override
        public void run() {
            switch (vplsOperation.op()) {
                case ADD:
                    installVplsIntents();
                    break;
                case REMOVE:
                    removeVplsIntents();
                    break;
                case UPDATE:
                    updateVplsIntents();
                    break;
                default:
                    this.error = new VplsOperationException(vplsOperation,
                                                            UNKNOWN_OP);
                    break;
            }

            if (this.error != null) {
                errorConsumer.accept(this.error);
            } else {
                successConsumer.accept(vplsOperation);
            }
        }

        /**
         * Updates Intents of the VPLS.
         */
        private void updateVplsIntents() {
            // check which part we need to update
            // if we update host only, we don't need to reinstall
            // every Intents
            Set<Intent> intentsToInstall = Sets.newHashSet();
            Set<Intent> intentsToUninstall = Sets.newHashSet();
            VplsData vplsData = vplsOperation.vpls();
            Set<Intent> currentIntents = getCurrentIntents();

            // Compares broadcast Intents
            Set<Intent> currentBrcIntents = currentIntents.stream()
                    .filter(intent -> intent instanceof SinglePointToMultiPointIntent)
                    .collect(Collectors.toSet());
            Set<Intent> targetBrcIntents = VplsIntentUtility.buildBrcIntents(vplsData, appId);
            if (!intentSetEquals(currentBrcIntents, targetBrcIntents)) {
                // If broadcast Intents changes, it means some network
                // interfaces or encapsulation constraint changed; Need to
                // reinstall all intents
                removeVplsIntents();
                installVplsIntents();
                return;
            }

            // Compares unicast Intents
            Set<Intent> currentUniIntents = currentIntents.stream()
                    .filter(intent -> intent instanceof MultiPointToSinglePointIntent)
                    .collect(Collectors.toSet());
            Set<Intent> targetUniIntents = VplsIntentUtility.buildUniIntents(vplsData,
                                                                             hostsFromVpls(),
                                                                             appId);

            // New unicast Intents to install
            targetUniIntents.forEach(intent -> {
                if (!currentUniIntents.contains(intent)) {
                    intentsToInstall.add(intent);
                }
            });

            // Old unicast Intents to remove
            currentUniIntents.forEach(intent -> {
                if (!targetUniIntents.contains(intent)) {
                    intentsToUninstall.add(intent);
                }
            });
            applyIntentsSync(intentsToUninstall, Direction.REMOVE);
            applyIntentsSync(intentsToInstall, Direction.ADD);
        }

        private Set<Host> hostsFromVpls() {
            VplsData vplsData = vplsOperation.vpls();
            Set<Interface> interfaces = vplsData.interfaces();
            return interfaces.stream()
                    .map(this::hostsFromInterface)
                    .flatMap(Collection::stream)
                    .collect(Collectors.toSet());
        }

        private Set<Host> hostsFromInterface(Interface iface) {
            return hostService.getConnectedHosts(iface.connectPoint())
                    .stream()
                    .filter(host -> host.vlan().equals(iface.vlan()))
                    .collect(Collectors.toSet());
        }

        /**
         * Applies Intents synchronously with a specific direction.
         *
         * @param intents the Intents
         * @param direction the direction
         */
        private void applyIntentsSync(Set<Intent> intents, Direction direction) {
            Set<Key> pendingIntentKeys = intents.stream()
                    .map(Intent::key).collect(Collectors.toSet());
            IntentCompleter completer;

            switch (direction) {
                case ADD:
                    completer = new IntentCompleter(pendingIntentKeys,
                                                    IntentEvent.Type.INSTALLED);
                    intentService.addListener(completer);
                    intents.forEach(intentService::submit);
                    break;
                case REMOVE:
                    completer = new IntentCompleter(pendingIntentKeys,
                                                    IntentEvent.Type.WITHDRAWN);
                    intentService.addListener(completer);
                    intents.forEach(intentService::withdraw);
                    break;
                default:
                    this.error = new VplsOperationException(this.vplsOperation,
                                                            UNKNOWN_INTENT_DIR);
                    return;
            }

            try {
                // Wait until Intent operation completed
                completer.complete();
            } catch (VplsOperationException e) {
                this.error = e;
            } finally {
                intentService.removeListener(completer);
            }
        }

        /**
         * Checks if two sets of Intents are equal.
         *
         * @param intentSet1 the first set of Intents
         * @param intentSet2 the second set of Intents
         * @return true if both set of Intents are equal; otherwise false
         */
        private boolean intentSetEquals(Set<Intent> intentSet1, Set<Intent> intentSet2) {
            if (intentSet1.size() != intentSet2.size()) {
                return false;
            }
            for (Intent intent1 : intentSet1) {
                if (intentSet2.stream()
                        .noneMatch(intent2 -> IntentUtils.intentsAreEqual(intent1, intent2))) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Retrieves installed Intents from IntentService which related to
         * specific VPLS.
         *
         * @return the Intents which related to the VPLS
         */
        private Set<Intent> getCurrentIntents() {
            VplsData vplsData = vplsOperation.vpls();
            String vplsName = vplsData.name();
            return Tools.stream(intentService.getIntents())
                    .filter(intent -> intent.key().toString().startsWith(vplsName))
                    .collect(Collectors.toSet());
        }

        /**
         * Generates unicast Intents and broadcast Intents for the VPLS.
         *
         * @return Intents for the VPLS
         */
        private Set<Intent> generateVplsIntents() {
            VplsData vplsData = vplsOperation.vpls();
            Set<Intent> brcIntents = VplsIntentUtility.buildBrcIntents(vplsData, appId);
            Set<Intent> uniIntent = VplsIntentUtility.buildUniIntents(vplsData, hostsFromVpls(), appId);

            return Stream.concat(brcIntents.stream(), uniIntent.stream())
                    .collect(Collectors.toSet());
        }

        /**
         * Removes all Intents from the VPLS.
         */
        private void removeVplsIntents() {
            Set<Intent> intentsToWithdraw = getCurrentIntents();
            applyIntentsSync(intentsToWithdraw, Direction.REMOVE);
            intentsToWithdraw.forEach(intentService::purge);
        }

        /**
         * Installs Intents of the VPLS.
         */
        private void installVplsIntents() {
            Set<Intent> intentsToInstall = generateVplsIntents();
            applyIntentsSync(intentsToInstall, Direction.ADD);
        }

        /**
         * Helper class which monitors if all Intent operations are completed.
         */
        class IntentCompleter implements IntentListener {
            private static final String INTENT_COMPILE_ERR = "Got {} from intent completer";
            private CompletableFuture<Void> completableFuture;
            private Set<Key> pendingIntentKeys;
            private IntentEvent.Type expectedEventType;

            /**
             * Initialize completer with given Intent keys and expect Intent
             * event type.
             *
             * @param pendingIntentKeys the Intent keys to wait
             * @param expectedEventType expect Intent event type
             */
            public IntentCompleter(Set<Key> pendingIntentKeys,
                                   IntentEvent.Type expectedEventType) {
                this.completableFuture = new CompletableFuture<>();
                this.pendingIntentKeys = Sets.newConcurrentHashSet(pendingIntentKeys);
                this.expectedEventType = expectedEventType;
            }

            @Override
            public void event(IntentEvent event) {
                Intent intent = event.subject();
                Key key = intent.key();
                if (!pendingIntentKeys.contains(key)) {
                    // ignore Intent events from other VPLS
                    return;
                }
                // Intent failed, throw an exception to completable future
                if (event.type() == IntentEvent.Type.CORRUPT ||
                        event.type() == IntentEvent.Type.FAILED) {
                    completableFuture.completeExceptionally(new IntentException(intent.toString()));
                    return;
                }
                // If event type matched to expected type, remove from pending
                if (event.type() == expectedEventType) {
                    pendingIntentKeys.remove(key);
                }
                if (pendingIntentKeys.isEmpty()) {
                    completableFuture.complete(null);
                }
            }

            /**
             * Waits until all pending Intents completed ot timeout.
             */
            public void complete() {
                // If no pending Intent keys, complete directly
                if (pendingIntentKeys.isEmpty()) {
                    return;
                }
                try {
                    completableFuture.get(OPERATION_TIMEOUT, TimeUnit.SECONDS);
                } catch (TimeoutException | InterruptedException |
                         ExecutionException | IntentException e) {
                    // TODO: handle errors more carefully
                    log.warn(INTENT_COMPILE_ERR, e.toString());
                    throw new VplsOperationException(vplsOperation, e.toString());
                }
            }
        }
    }

    /**
     * A listener for leadership events.
     * Only the leader can process VPLS operation in the ONOS cluster.
     */
    private class InternalLeadershipListener implements LeadershipEventListener {
        private static final String LEADER_CHANGE = "Change leader to {}";

        @Override
        public void event(LeadershipEvent event) {
            switch (event.type()) {
                case LEADER_CHANGED:
                case LEADER_AND_CANDIDATES_CHANGED:
                    isLeader = localNodeId.equals(event.subject().leaderNodeId());
                    if (isLeader) {
                        log.debug(LEADER_CHANGE, localNodeId);
                    }
                    break;
                default:
                    break;
            }
        }

        @Override
        public boolean isRelevant(LeadershipEvent event) {
            return event.subject().topic().equals(appId.name());
        }
    }
}

/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hdds.scm.container.replication;

import com.google.common.collect.ImmutableList;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.hadoop.hdds.client.RatisReplicationConfig;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.protocol.DatanodeDetails;
import org.apache.hadoop.hdds.protocol.MockDatanodeDetails;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos;
import org.apache.hadoop.hdds.protocol.proto.StorageContainerDatanodeProtocolProtos;
import org.apache.hadoop.hdds.protocol.proto.StorageContainerDatanodeProtocolProtos.ContainerReplicaProto.State;
import org.apache.hadoop.hdds.scm.PlacementPolicy;
import org.apache.hadoop.hdds.scm.container.ContainerInfo;
import org.apache.hadoop.hdds.scm.container.ContainerReplica;
import org.apache.hadoop.hdds.scm.container.replication.ContainerHealthResult.UnderReplicatedHealthResult;
import org.apache.hadoop.hdds.scm.container.replication.ReplicationManager.ReplicationManagerConfiguration;
import org.apache.hadoop.hdds.scm.node.NodeManager;
import org.apache.hadoop.hdds.scm.node.NodeStatus;
import org.apache.hadoop.hdds.scm.node.states.NodeNotFoundException;
import org.apache.hadoop.hdds.scm.pipeline.InsufficientDatanodesException;
import org.apache.hadoop.ozone.container.common.SCMTestUtils;
import org.apache.hadoop.ozone.protocol.commands.SCMCommand;
import org.apache.ratis.protocol.exceptions.NotLeaderException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.jupiter.api.Assertions;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.apache.hadoop.hdds.protocol.proto.HddsProtos.NodeOperationalState.DECOMMISSIONING;
import static org.apache.hadoop.hdds.protocol.proto.HddsProtos.NodeOperationalState.ENTERING_MAINTENANCE;
import static org.apache.hadoop.hdds.protocol.proto.HddsProtos.NodeOperationalState.IN_MAINTENANCE;
import static org.apache.hadoop.hdds.protocol.proto.HddsProtos.NodeOperationalState.IN_SERVICE;
import static org.apache.hadoop.hdds.protocol.proto.HddsProtos.ReplicationFactor.THREE;
import static org.apache.hadoop.hdds.scm.container.replication.ReplicationTestUtil.createContainer;
import static org.apache.hadoop.hdds.scm.container.replication.ReplicationTestUtil.createContainerInfo;
import static org.apache.hadoop.hdds.scm.container.replication.ReplicationTestUtil.createContainerReplica;
import static org.apache.hadoop.hdds.scm.container.replication.ReplicationTestUtil.createReplicas;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;

/**
 * Tests for {@link RatisUnderReplicationHandler}.
 */
public class TestRatisUnderReplicationHandler {
  private ContainerInfo container;
  private NodeManager nodeManager;
  private OzoneConfiguration conf;
  private static final RatisReplicationConfig RATIS_REPLICATION_CONFIG =
      RatisReplicationConfig.getInstance(THREE);
  private PlacementPolicy policy;
  private ReplicationManager replicationManager;
  private Set<Pair<DatanodeDetails, SCMCommand<?>>> commandsSent;
  private ReplicationManagerMetrics metrics;

  @Before
  public void setup() throws NodeNotFoundException,
      CommandTargetOverloadedException, NotLeaderException {
    container = ReplicationTestUtil.createContainer(
        HddsProtos.LifeCycleState.CLOSED, RATIS_REPLICATION_CONFIG);

    nodeManager = Mockito.mock(NodeManager.class);
    conf = SCMTestUtils.getConf();
    policy = ReplicationTestUtil
        .getSimpleTestPlacementPolicy(nodeManager, conf);
    replicationManager = Mockito.mock(ReplicationManager.class);
    OzoneConfiguration ozoneConfiguration = new OzoneConfiguration();
    ozoneConfiguration.setBoolean("hdds.scm.replication.push", true);
    Mockito.when(replicationManager.getConfig())
        .thenReturn(ozoneConfiguration.getObject(
            ReplicationManagerConfiguration.class));
    metrics = ReplicationManagerMetrics.create(replicationManager);
    Mockito.when(replicationManager.getMetrics()).thenReturn(metrics);

    /*
      Return NodeStatus with NodeOperationalState as specified in
      DatanodeDetails, and NodeState as HEALTHY.
    */
    Mockito.when(
        replicationManager.getNodeStatus(any(DatanodeDetails.class)))
        .thenAnswer(invocationOnMock -> {
          DatanodeDetails dn = invocationOnMock.getArgument(0);
          return new NodeStatus(dn.getPersistedOpState(),
              HddsProtos.NodeState.HEALTHY);
        });

    commandsSent = new HashSet<>();
    ReplicationTestUtil.mockRMSendThrottleReplicateCommand(
        replicationManager, commandsSent, new AtomicBoolean(false));
    ReplicationTestUtil.mockRMSendDatanodeCommand(replicationManager,
        commandsSent);
    ReplicationTestUtil.mockRMSendDeleteCommand(replicationManager,
        commandsSent);
  }

  /**
   * When the container is under replicated even though there's a pending
   * add, the handler should create replication commands.
   */
  @Test
  public void testUnderReplicatedWithMissingReplicasAndPendingAdd()
      throws IOException {
    Set<ContainerReplica> replicas
        = createReplicas(container.containerID(), State.CLOSED, 0);
    List<ContainerReplicaOp> pendingOps = ImmutableList.of(
        ContainerReplicaOp.create(ContainerReplicaOp.PendingOpType.ADD,
            MockDatanodeDetails.randomDatanodeDetails(), 0));

    testProcessing(replicas, pendingOps, getUnderReplicatedHealthResult(), 2,
        1);
  }

  /**
   * When the container is under replicated and unrecoverable (no replicas
   * exist), the handler will not create any commands.
   */
  @Test
  public void testUnderReplicatedAndUnrecoverable() throws IOException {
    testProcessing(Collections.emptySet(), Collections.emptyList(),
        getUnderReplicatedHealthResult(), 2, 0);
  }

  /**
   * The container is currently under replicated, but there's a pending add
   * that will make it sufficiently replicated. The handler should not create
   * any commands.
   */
  @Test
  public void testUnderReplicatedFixedByPendingAdd() throws IOException {
    Set<ContainerReplica> replicas
        = createReplicas(container.containerID(), State.CLOSED, 0, 0);
    List<ContainerReplicaOp> pendingOps = ImmutableList.of(
        ContainerReplicaOp.create(ContainerReplicaOp.PendingOpType.ADD,
            MockDatanodeDetails.randomDatanodeDetails(), 0));

    testProcessing(replicas, pendingOps, getUnderReplicatedHealthResult(), 2,
        0);
  }

  /**
   * The container is under-replicated because a DN is decommissioning. The
   * handler should create replication command.
   */
  @Test
  public void testUnderReplicatedBecauseOfDecommissioningReplica()
      throws IOException {
    Set<ContainerReplica> replicas = ReplicationTestUtil
        .createReplicas(Pair.of(DECOMMISSIONING, 0), Pair.of(IN_SERVICE, 0),
            Pair.of(IN_SERVICE, 0));

    testProcessing(replicas, Collections.emptyList(),
        getUnderReplicatedHealthResult(), 2, 1);
  }

  /**
   * The container is under-replicated because a DN is entering maintenance
   * and the remaining number of replicas (CLOSED or QUASI_CLOSED replicas on
   * HEALTHY datanodes) are less than the minimum healthy required.
   */
  @Test
  public void testUnderReplicatedBecauseOfMaintenanceReplica()
      throws IOException {
    Set<ContainerReplica> replicas = ReplicationTestUtil
        .createReplicas(Pair.of(ENTERING_MAINTENANCE, 0),
            Pair.of(IN_SERVICE, 0), Pair.of(IN_SERVICE, 0));

    testProcessing(replicas, Collections.emptyList(),
        getUnderReplicatedHealthResult(), 3, 1);
  }

  /**
   * The container is sufficiently replicated because we have the minimum
   * healthy replicas required for a DN to enter maintenance.
   */
  @Test
  public void testSufficientlyReplicatedDespiteMaintenanceReplica()
      throws IOException {
    Set<ContainerReplica> replicas = ReplicationTestUtil
        .createReplicas(Pair.of(ENTERING_MAINTENANCE, 0),
            Pair.of(IN_SERVICE, 0), Pair.of(IN_SERVICE, 0));

    testProcessing(replicas, Collections.emptyList(),
        getUnderReplicatedHealthResult(), 2, 0);
  }

  /**
   * The handler should throw an exception when the placement policy is unable
   * to choose new targets for replication.
   */
  @Test
  public void testNoTargetsFoundBecauseOfPlacementPolicy() {
    policy = ReplicationTestUtil.getNoNodesTestPlacementPolicy(nodeManager,
        conf);
    RatisUnderReplicationHandler handler =
        new RatisUnderReplicationHandler(policy, conf, replicationManager);

    Set<ContainerReplica> replicas
        = createReplicas(container.containerID(), State.CLOSED, 0, 0);

    Assert.assertThrows(IOException.class,
        () -> handler.processAndSendCommands(replicas,
            Collections.emptyList(), getUnderReplicatedHealthResult(), 2));
    Assert.assertEquals(0, commandsSent.size());
    Assert.assertEquals(0, metrics.getPartialReplicationTotal());
  }

  @Test
  public void testInsufficientTargetsFoundBecauseOfPlacementPolicy() {
    policy = ReplicationTestUtil.getInsufficientNodesTestPlacementPolicy(
        nodeManager, conf, 2);
    RatisUnderReplicationHandler handler =
        new RatisUnderReplicationHandler(policy, conf, replicationManager);

    // Only one replica is available, so we need to create 2 new ones.
    Set<ContainerReplica> replicas
        = createReplicas(container.containerID(), State.CLOSED, 0);

    Assert.assertThrows(InsufficientDatanodesException.class,
        () -> handler.processAndSendCommands(replicas,
            Collections.emptyList(), getUnderReplicatedHealthResult(), 2));
    // One command should be sent to the replication manager as we could only
    // fine one node rather than two.
    Assert.assertEquals(1, commandsSent.size());
    Assert.assertEquals(1, metrics.getPartialReplicationTotal());
  }

  @Test
  public void testNoTargetsFoundBecauseOfPlacementPolicyRemoveNone() {
    policy = ReplicationTestUtil.getNoNodesTestPlacementPolicy(nodeManager,
        conf);
    RatisUnderReplicationHandler handler =
        new RatisUnderReplicationHandler(policy, conf, replicationManager);

    Set<ContainerReplica> replicas
        = createReplicas(container.containerID(), State.CLOSED, 0);

    ContainerReplica shouldDelete = createContainerReplica(
        container.containerID(), 0, IN_SERVICE, State.UNHEALTHY);
    replicas.add(shouldDelete);

    Assert.assertThrows(IOException.class,
        () -> handler.processAndSendCommands(replicas,
            Collections.emptyList(), getUnderReplicatedHealthResult(), 2));
    // No commands send, as there are only 2 replicas available.
    Assert.assertEquals(0, commandsSent.size());
  }

  @Test
  public void testNoTargetsFoundBecauseOfPlacementPolicyNoneHealthy() {
    policy = ReplicationTestUtil.getNoNodesTestPlacementPolicy(nodeManager,
        conf);
    RatisUnderReplicationHandler handler =
        new RatisUnderReplicationHandler(policy, conf, replicationManager);

    // All replicas UNHEALTHY so we do nothing.
    Set<ContainerReplica> replicas
        = createReplicas(container.containerID(), State.UNHEALTHY, 0, 0);

    Assert.assertThrows(IOException.class,
        () -> handler.processAndSendCommands(replicas,
            Collections.emptyList(), getUnderReplicatedHealthResult(), 2));
    // No commands send, as no CLOSED replicas available.
    Assert.assertEquals(0, commandsSent.size());
  }

  @Test
  public void testNoTargetsFoundBecauseOfPlacementPolicyRemoveUnhealthy() {
    policy = ReplicationTestUtil.getNoNodesTestPlacementPolicy(nodeManager,
        conf);
    RatisUnderReplicationHandler handler =
        new RatisUnderReplicationHandler(policy, conf, replicationManager);

    Set<ContainerReplica> replicas
        = createReplicas(container.containerID(), State.CLOSED, 0, 0);

    ContainerReplica shouldDelete = createContainerReplica(
        container.containerID(), 0, IN_SERVICE, State.UNHEALTHY);
    replicas.add(shouldDelete);

    Assert.assertThrows(IOException.class,
        () -> handler.processAndSendCommands(replicas,
            Collections.emptyList(), getUnderReplicatedHealthResult(), 2));
    Assert.assertEquals(1, commandsSent.size());
    Pair<DatanodeDetails, SCMCommand<?>> cmd = commandsSent.iterator().next();
    Assert.assertEquals(shouldDelete.getDatanodeDetails(), cmd.getKey());
    Assert.assertEquals(StorageContainerDatanodeProtocolProtos.SCMCommandProto
        .Type.deleteContainerCommand, cmd.getValue().getType());
  }

  @Test
  public void testNoTargetsFoundBecauseOfPlacementPolicyPendingDelete() {
    policy = ReplicationTestUtil.getNoNodesTestPlacementPolicy(nodeManager,
        conf);
    RatisUnderReplicationHandler handler =
        new RatisUnderReplicationHandler(policy, conf, replicationManager);

    Set<ContainerReplica> replicas
        = createReplicas(container.containerID(), State.CLOSED, 0, 0);

    ContainerReplica shouldDelete = createContainerReplica(
        container.containerID(), 0, IN_SERVICE, State.UNHEALTHY);
    replicas.add(shouldDelete);

    List<ContainerReplicaOp> pending = Collections.singletonList(
        ContainerReplicaOp.create(ContainerReplicaOp.PendingOpType.DELETE,
        shouldDelete.getDatanodeDetails(), 0));

    Assert.assertThrows(IOException.class,
        () -> handler.processAndSendCommands(replicas,
            pending, getUnderReplicatedHealthResult(), 2));
    // No commands sent as we have a pending delete.
    Assert.assertEquals(0, commandsSent.size());
  }

  @Test
  public void testNoTargetsFoundRemoveQuasiClosedWithLowestSeq() {
    policy = ReplicationTestUtil.getNoNodesTestPlacementPolicy(nodeManager,
        conf);
    RatisUnderReplicationHandler handler =
        new RatisUnderReplicationHandler(policy, conf, replicationManager);

    long sequenceID = 10;
    container = ReplicationTestUtil.createContainerInfo(
        RatisReplicationConfig.getInstance(THREE),
        1, HddsProtos.LifeCycleState.CLOSED, sequenceID);

    Set<ContainerReplica> replicas
        = createReplicas(container.containerID(), State.CLOSED, 0, 0);

    // This quasi closed is newer than the other one below, so it should not
    // be removed.
    replicas.add(createContainerReplica(
        container.containerID(), 0, IN_SERVICE, State.QUASI_CLOSED,
        sequenceID - 2));
    // Unhealthy should be removed over the quasi-closed ones.
    ContainerReplica shouldDelete = createContainerReplica(
        container.containerID(), 0, IN_SERVICE, State.UNHEALTHY);
    replicas.add(shouldDelete);

    Assert.assertThrows(IOException.class,
        () -> handler.processAndSendCommands(replicas,
            Collections.emptyList(), getUnderReplicatedHealthResult(), 2));
    Assert.assertEquals(1, commandsSent.size());
    Pair<DatanodeDetails, SCMCommand<?>> cmd = commandsSent.iterator().next();
    Assert.assertEquals(shouldDelete.getDatanodeDetails(), cmd.getKey());
    Assert.assertEquals(StorageContainerDatanodeProtocolProtos.SCMCommandProto
        .Type.deleteContainerCommand, cmd.getValue().getType());
  }

  @Test
  public void testUnhealthyReplicasAreReplicatedWhenHealthyAreUnavailable()
      throws IOException {
    Set<ContainerReplica> replicas
        = createReplicas(container.containerID(), State.UNHEALTHY, 0);
    List<ContainerReplicaOp> pendingOps = ImmutableList.of(
        ContainerReplicaOp.create(ContainerReplicaOp.PendingOpType.ADD,
            MockDatanodeDetails.randomDatanodeDetails(), 0));

    testProcessing(replicas, pendingOps, getUnderReplicatedHealthResult(), 2,
        1);
  }

  @Test
  public void onlyHealthyReplicasShouldBeReplicatedWhenAvailable()
      throws IOException {
    Set<ContainerReplica> replicas
        = createReplicas(container.containerID(), State.UNHEALTHY, 0);
    ContainerReplica closedReplica = createContainerReplica(
        container.containerID(), 0, IN_SERVICE, State.CLOSED);
    replicas.add(closedReplica);

    Set<Pair<DatanodeDetails, SCMCommand<?>>> commands =
        testProcessing(replicas, Collections.emptyList(),
            getUnderReplicatedHealthResult(), 2, 2);
    commands.forEach(
        command -> Assert.assertEquals(closedReplica.getDatanodeDetails(),
            command.getKey()));
  }

  /**
   * Tests that a CLOSED RATIS container with 2 CLOSED replicas and 1
   * UNHEALTHY replica is correctly seen as under replicated. And, under
   * replication is fixed by sending a command to replicate either of the
   * CLOSED replicas.
   */
  @Test
  public void testUnderReplicationBecauseOfUnhealthyReplica()
      throws IOException {
    Set<ContainerReplica> replicas
        = createReplicas(container.containerID(), State.CLOSED, 0, 0);
    ContainerReplica unhealthyReplica = createContainerReplica(
        container.containerID(), 0, IN_SERVICE, State.UNHEALTHY);
    replicas.add(unhealthyReplica);

    Set<Pair<DatanodeDetails, SCMCommand<?>>> commands =
        testProcessing(replicas, Collections.emptyList(),
            getUnderReplicatedHealthResult(), 2, 1);
    commands.forEach(
        command -> Assert.assertNotEquals(unhealthyReplica.getDatanodeDetails(),
            command.getKey()));
  }

  @Test
  public void testOnlyHighestBcsidShouldBeASource() throws IOException {
    Set<ContainerReplica> replicas = new HashSet<>();
    replicas.add(createContainerReplica(container.containerID(), 0,
        IN_SERVICE, State.CLOSED, 1));
    ContainerReplica valid = createContainerReplica(
        container.containerID(), 0, IN_SERVICE, State.CLOSED, 2);
    replicas.add(valid);

    testProcessing(replicas, Collections.emptyList(),
        getUnderReplicatedHealthResult(), 2, 1);

    // Ensure that the replica with SEQ=2 is the only source sent
    Mockito.verify(replicationManager).sendThrottledReplicationCommand(
        any(ContainerInfo.class),
        Mockito.eq(Collections.singletonList(valid.getDatanodeDetails())),
        any(DatanodeDetails.class), anyInt());
  }

  @Test
  public void testCorrectUsedAndExcludedNodesPassed() throws IOException {
    PlacementPolicy mockPolicy = Mockito.mock(PlacementPolicy.class);
    Mockito.when(mockPolicy.chooseDatanodes(any(), any(), any(),
        anyInt(), anyLong(), anyLong()))
        .thenReturn(Collections.singletonList(
            MockDatanodeDetails.randomDatanodeDetails()));

    ArgumentCaptor<List<DatanodeDetails>> usedNodesCaptor =
        ArgumentCaptor.forClass(List.class);

    ArgumentCaptor<List<DatanodeDetails>> excludedNodesCaptor =
        ArgumentCaptor.forClass(List.class);

    RatisUnderReplicationHandler handler =
        new RatisUnderReplicationHandler(mockPolicy, conf, replicationManager);

    Set<ContainerReplica> replicas = new HashSet<>();
    ContainerReplica good = createContainerReplica(container.containerID(), 0,
        IN_SERVICE, State.CLOSED, 1);
    replicas.add(good);

    ContainerReplica unhealthy = createContainerReplica(
        container.containerID(), 0, IN_SERVICE, State.UNHEALTHY, 1);
    replicas.add(unhealthy);

    ContainerReplica decommissioning =
        createContainerReplica(container.containerID(), 0,
            DECOMMISSIONING, State.CLOSED, 1);
    replicas.add(decommissioning);

    ContainerReplica maintenance =
        createContainerReplica(container.containerID(), 0,
            IN_MAINTENANCE, State.CLOSED, 1);
    replicas.add(maintenance);

    List<ContainerReplicaOp> pendingOps = new ArrayList<>();
    DatanodeDetails pendingAdd = MockDatanodeDetails.randomDatanodeDetails();
    DatanodeDetails pendingRemove = MockDatanodeDetails.randomDatanodeDetails();
    pendingOps.add(ContainerReplicaOp.create(
        ContainerReplicaOp.PendingOpType.ADD, pendingAdd, 0));
    pendingOps.add(ContainerReplicaOp.create(
        ContainerReplicaOp.PendingOpType.DELETE, pendingRemove, 0));

    handler.processAndSendCommands(replicas, pendingOps,
        getUnderReplicatedHealthResult(), 2);


    Mockito.verify(mockPolicy, times(1)).chooseDatanodes(
        usedNodesCaptor.capture(), excludedNodesCaptor.capture(), any(),
        anyInt(), anyLong(), anyLong());

    List<DatanodeDetails> usedNodes = usedNodesCaptor.getValue();
    List<DatanodeDetails> excludedNodes = excludedNodesCaptor.getValue();

    Assertions.assertTrue(usedNodes.contains(good.getDatanodeDetails()));
    Assertions.assertTrue(usedNodes.contains(maintenance.getDatanodeDetails()));
    Assertions.assertTrue(usedNodes.contains(pendingAdd));

    Assertions.assertTrue(excludedNodes.contains(
        unhealthy.getDatanodeDetails()));
    Assertions.assertTrue(excludedNodes.contains(
        decommissioning.getDatanodeDetails()));
    Assertions.assertTrue(excludedNodes.contains(pendingRemove));
  }

  @Test
  public void testUnderReplicationDueToQuasiClosedReplicaWithWrongSequenceID()
      throws IOException {
    final long sequenceID = 20;
    container = ReplicationTestUtil.createContainerInfo(
        RATIS_REPLICATION_CONFIG, 1,
        HddsProtos.LifeCycleState.CLOSED, sequenceID);

    final Set<ContainerReplica> replicas = new HashSet<>(2);
    replicas.add(createContainerReplica(container.containerID(), 0,
        IN_SERVICE, State.CLOSED, sequenceID));

    final ContainerReplica quasiClosedReplica =
        createContainerReplica(container.containerID(), 0,
            IN_SERVICE, State.QUASI_CLOSED, sequenceID - 1);
    replicas.add(quasiClosedReplica);

    final Set<Pair<DatanodeDetails, SCMCommand<?>>> commands =
        testProcessing(replicas, Collections.emptyList(),
            getUnderReplicatedHealthResult(), 2, 2);
    commands.forEach(
        command -> Assert.assertNotEquals(
            quasiClosedReplica.getDatanodeDetails(),
            command.getKey()));
  }

  @Test
  public void testOnlyQuasiClosedReplicaWithWrongSequenceIdIsAvailable()
      throws IOException {
    final long sequenceID = 20;
    container = createContainerInfo(RATIS_REPLICATION_CONFIG, 1,
        HddsProtos.LifeCycleState.CLOSED, sequenceID);

    final Set<ContainerReplica> replicas = new HashSet<>(1);
    final ContainerReplica quasiClosedReplica =
        createContainerReplica(container.containerID(), 0,
            IN_SERVICE, State.QUASI_CLOSED, sequenceID - 1);
    replicas.add(quasiClosedReplica);

    final Set<Pair<DatanodeDetails, SCMCommand<?>>> commands =
        testProcessing(replicas, Collections.emptyList(),
            getUnderReplicatedHealthResult(), 2, 2);
    commands.forEach(
        command -> Assert.assertEquals(
            quasiClosedReplica.getDatanodeDetails(),
            command.getKey()));
  }

  @Test
  public void testOnlyClosedReplicasOfClosedContainersAreSources()
      throws IOException {
    container = createContainerInfo(RATIS_REPLICATION_CONFIG, 1,
        HddsProtos.LifeCycleState.CLOSED, 1);

    final Set<ContainerReplica> replicas = new HashSet<>(2);
    final ContainerReplica closedReplica =
        createContainerReplica(container.containerID(), 0, IN_SERVICE,
            State.CLOSED, 1);
    replicas.add(closedReplica);
    replicas.add(createContainerReplica(container.containerID(), 0,
            IN_SERVICE, State.QUASI_CLOSED, 1));

    final Set<Pair<DatanodeDetails, SCMCommand<?>>> commands =
        testProcessing(replicas, Collections.emptyList(),
            getUnderReplicatedHealthResult(), 2, 1);
    commands.forEach(
        command -> Assert.assertEquals(closedReplica.getDatanodeDetails(),
            command.getKey()));
  }

  @Test
  public void testQuasiClosedReplicasAreSourcesWhenOnlyTheyAreAvailable()
      throws IOException {
    container = createContainerInfo(RATIS_REPLICATION_CONFIG, 1,
        HddsProtos.LifeCycleState.CLOSED, 1);

    Set<ContainerReplica> replicas = new HashSet<>(1);
    replicas.add(createContainerReplica(container.containerID(), 0,
        IN_SERVICE, State.QUASI_CLOSED, 1));

    testProcessing(replicas, Collections.emptyList(),
        getUnderReplicatedHealthResult(), 2, 2);

    // test the same, but for a QUASI_CLOSED container
    container = createContainer(HddsProtos.LifeCycleState.QUASI_CLOSED,
        RATIS_REPLICATION_CONFIG);
    replicas = new HashSet<>(1);
    replicas.add(createContainerReplica(container.containerID(), 0,
        IN_SERVICE, State.QUASI_CLOSED, container.getSequenceId()));

    commandsSent.clear();
    testProcessing(replicas, Collections.emptyList(),
            getUnderReplicatedHealthResult(), 2, 2);
  }

  /**
   * Tests whether the specified expectNumCommands number of commands are
   * created by the handler.
   * @param replicas All replicas of the container
   * @param pendingOps Collection of pending ops
   * @param healthResult ContainerHealthResult that should be passed to the
   *                     handler
   * @param minHealthyForMaintenance the minimum number of healthy replicas
   *                                 required for a datanode to enter
   *                                 maintenance
   * @param expectNumCommands number of commands expected to be created by
   *                          the handler
   */
  private Set<Pair<DatanodeDetails, SCMCommand<?>>> testProcessing(
      Set<ContainerReplica> replicas, List<ContainerReplicaOp> pendingOps,
      ContainerHealthResult healthResult,
      int minHealthyForMaintenance, int expectNumCommands) throws IOException {
    RatisUnderReplicationHandler handler =
        new RatisUnderReplicationHandler(policy, conf, replicationManager);

    handler.processAndSendCommands(replicas, pendingOps,
            healthResult, minHealthyForMaintenance);
    Assert.assertEquals(expectNumCommands, commandsSent.size());
    return commandsSent;
  }

  private UnderReplicatedHealthResult getUnderReplicatedHealthResult() {
    UnderReplicatedHealthResult healthResult =
        Mockito.mock(UnderReplicatedHealthResult.class);
    Mockito.when(healthResult.getContainerInfo()).thenReturn(container);
    return healthResult;
  }
}

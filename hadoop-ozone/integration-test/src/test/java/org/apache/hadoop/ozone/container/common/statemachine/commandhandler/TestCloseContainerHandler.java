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
package org.apache.hadoop.ozone.container.common.statemachine.commandhandler;

import java.util.HashMap;

import org.apache.hadoop.hdds.HddsConfigKeys;
import org.apache.hadoop.hdds.client.ReplicationFactor;
import org.apache.hadoop.hdds.client.ReplicationType;
import org.apache.hadoop.hdds.client.StandaloneReplicationConfig;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.conf.StorageUnit;
import org.apache.hadoop.hdds.protocol.DatanodeDetails;
import org.apache.hadoop.hdds.scm.container.ContainerID;
import org.apache.hadoop.hdds.scm.container.ContainerInfo;
import org.apache.hadoop.hdds.scm.pipeline.Pipeline;
import org.apache.hadoop.ozone.MiniOzoneCluster;
import org.apache.hadoop.ozone.client.ObjectStore;
import org.apache.hadoop.ozone.client.OzoneClient;
import org.apache.hadoop.ozone.client.OzoneClientFactory;
import org.apache.hadoop.ozone.client.io.OzoneOutputStream;
import org.apache.hadoop.ozone.container.common.impl.ContainerData;
import org.apache.hadoop.ozone.om.helpers.OmKeyArgs;
import org.apache.hadoop.ozone.om.helpers.OmKeyLocationInfo;
import org.apache.hadoop.ozone.protocol.commands.CloseContainerCommand;
import org.apache.hadoop.ozone.protocol.commands.SCMCommand;
import org.apache.ozone.test.GenericTestUtils;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.hadoop.hdds.protocol.proto.HddsProtos.ReplicationFactor.ONE;
import static org.apache.hadoop.hdds.scm.ScmConfigKeys.OZONE_DATANODE_RATIS_VOLUME_FREE_SPACE_MIN;
import static org.apache.hadoop.hdds.scm.ScmConfigKeys.OZONE_SCM_CONTAINER_SIZE;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;
import org.apache.ozone.test.JUnit5AwareTimeout;

/**
 * Test to behaviour of the datanode when receive close container command.
 */
public class TestCloseContainerHandler {

  /**
    * Set a timeout for each test.
    */
  @Rule
  public TestRule timeout = new JUnit5AwareTimeout(Timeout.seconds(300));

  private MiniOzoneCluster cluster;
  private OzoneConfiguration conf;

  @Before
  public void setup() throws Exception {
    //setup a cluster (1G free space is enough for a unit test)
    conf = new OzoneConfiguration();
    conf.set(OZONE_SCM_CONTAINER_SIZE, "1GB");
    conf.setStorageSize(OZONE_DATANODE_RATIS_VOLUME_FREE_SPACE_MIN,
        0, StorageUnit.MB);
    conf.setBoolean(HddsConfigKeys.HDDS_SCM_SAFEMODE_PIPELINE_CREATION, false);
    cluster = MiniOzoneCluster.newBuilder(conf)
        .setNumDatanodes(1).build();
    cluster.waitForClusterToBeReady();
    cluster.waitForPipelineTobeReady(ONE, 30000);
  }

  @After
  public void teardown() {
    if (cluster != null) {
      cluster.shutdown();
    }
  }

  @Test
  public void test() throws Exception {
    cluster.waitForClusterToBeReady();

    //the easiest way to create an open container is creating a key
    try (OzoneClient client = OzoneClientFactory.getRpcClient(conf)) {
      ObjectStore objectStore = client.getObjectStore();
      objectStore.createVolume("test");
      objectStore.getVolume("test").createBucket("test");
      OzoneOutputStream key = objectStore.getVolume("test").getBucket("test")
          .createKey("test", 1024, ReplicationType.RATIS,
              ReplicationFactor.ONE, new HashMap<>());
      key.write("test".getBytes(UTF_8));
      key.close();
    }

    //get the name of a valid container
    OmKeyArgs keyArgs =
        new OmKeyArgs.Builder().setVolumeName("test").setBucketName("test")
            .setReplicationConfig(StandaloneReplicationConfig.getInstance(ONE))
            .setDataSize(1024)
            .setKeyName("test")
            .build();

    OmKeyLocationInfo omKeyLocationInfo =
        cluster.getOzoneManager().lookupKey(keyArgs).getKeyLocationVersions()
            .get(0).getBlocksLatestVersionOnly().get(0);

    ContainerID containerId = ContainerID.valueOf(
        omKeyLocationInfo.getContainerID());
    ContainerInfo container = cluster.getStorageContainerManager()
        .getContainerManager().getContainer(containerId);
    Pipeline pipeline = cluster.getStorageContainerManager()
        .getPipelineManager().getPipeline(container.getPipelineID());

    Assert.assertFalse(isContainerClosed(cluster, containerId.getId()));

    DatanodeDetails datanodeDetails =
        cluster.getHddsDatanodes().get(0).getDatanodeDetails();
    //send the order to close the container
    SCMCommand<?> command = new CloseContainerCommand(
        containerId.getId(), pipeline.getId());
    command.setTerm(
        cluster.getStorageContainerManager().getScmContext().getTermOfLeader());
    cluster.getStorageContainerManager().getScmNodeManager()
        .addDatanodeCommand(datanodeDetails.getUuid(), command);

    GenericTestUtils.waitFor(() ->
            isContainerClosed(cluster, containerId.getId()),
            500,
            5 * 1000);

    //double check if it's really closed (waitFor also throws an exception)
    Assert.assertTrue(isContainerClosed(cluster, containerId.getId()));
  }

  private static Boolean isContainerClosed(MiniOzoneCluster cluster,
      long containerID) {
    ContainerData containerData;
    containerData = cluster.getHddsDatanodes().get(0)
        .getDatanodeStateMachine().getContainer().getContainerSet()
        .getContainer(containerID).getContainerData();
    return !containerData.isOpen();
  }

}

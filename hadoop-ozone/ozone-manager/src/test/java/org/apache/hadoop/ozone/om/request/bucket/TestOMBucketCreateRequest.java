/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.hadoop.ozone.om.request.bucket;

import java.util.UUID;

import org.apache.hadoop.hdds.client.DefaultReplicationConfig;
import org.apache.hadoop.hdds.client.ECReplicationConfig;
import org.apache.hadoop.ozone.om.exceptions.OMException;
import org.apache.hadoop.ozone.om.helpers.BucketLayout;
import org.junit.Assert;
import org.junit.Test;

import org.apache.hadoop.ozone.om.OMMetadataManager;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.OMRequest;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.OMResponse;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.StorageTypeProto;
import org.apache.hadoop.ozone.om.helpers.OmBucketInfo;
import org.apache.hadoop.ozone.om.helpers.OmVolumeArgs;
import org.apache.hadoop.ozone.om.request.OMRequestTestUtils;
import org.apache.hadoop.ozone.om.response.OMClientResponse;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos;
import org.apache.hadoop.util.Time;

import static org.apache.hadoop.ozone.om.request.OMRequestTestUtils.newBucketInfoBuilder;
import static org.apache.hadoop.ozone.om.request.OMRequestTestUtils.newCreateBucketRequest;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.when;

/**
 * Tests OMBucketCreateRequest class, which handles CreateBucket request.
 */
public class TestOMBucketCreateRequest extends TestBucketRequest {

  @Test
  public void testPreExecute() throws Exception {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    doPreExecute(volumeName, bucketName);
  }

  @Test
  public void preExecuteRejectsInvalidBucketName() {
    // Verify invalid bucket name throws exception
    OMException omException = assertThrows(OMException.class,
        () -> doPreExecute("volume1", "b1"));
    assertEquals("Invalid bucket name: b1", omException.getMessage());
  }

  @Test
  public void testValidateAndUpdateCache() throws Exception {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();

    OMBucketCreateRequest omBucketCreateRequest = doPreExecute(volumeName,
        bucketName);

    doValidateAndUpdateCache(volumeName, bucketName,
        omBucketCreateRequest.getOmRequest());

  }

  @Test
  public void testValidateAndUpdateCacheWithNoVolume() throws Exception {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();

    OMRequest originalRequest = newCreateBucketRequest(
        newBucketInfoBuilder(bucketName, volumeName))
        .build();

    OMBucketCreateRequest omBucketCreateRequest =
        new OMBucketCreateRequest(originalRequest);

    String bucketKey = omMetadataManager.getBucketKey(volumeName, bucketName);

    // As we have not still called validateAndUpdateCache, get() should
    // return null.

    Assert.assertNull(omMetadataManager.getBucketTable().get(bucketKey));

    OMClientResponse omClientResponse =
        omBucketCreateRequest.validateAndUpdateCache(ozoneManager, 1,
            ozoneManagerDoubleBufferHelper);

    OMResponse omResponse = omClientResponse.getOMResponse();
    Assert.assertNotNull(omResponse.getCreateBucketResponse());
    Assert.assertEquals(OzoneManagerProtocolProtos.Status.VOLUME_NOT_FOUND,
        omResponse.getStatus());

    // As request is invalid bucket table should not have entry.
    Assert.assertNull(omMetadataManager.getBucketTable().get(bucketKey));
  }

  @Test
  public void testValidateAndUpdateCacheWithBucketAlreadyExists()
      throws Exception {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();

    OMBucketCreateRequest omBucketCreateRequest =
        doPreExecute(volumeName, bucketName);

    doValidateAndUpdateCache(volumeName, bucketName,
        omBucketCreateRequest.getOmRequest());

    // Try create same bucket again
    OMClientResponse omClientResponse =
        omBucketCreateRequest.validateAndUpdateCache(ozoneManager, 2,
            ozoneManagerDoubleBufferHelper);

    OMResponse omResponse = omClientResponse.getOMResponse();
    Assert.assertNotNull(omResponse.getCreateBucketResponse());
    Assert.assertEquals(OzoneManagerProtocolProtos.Status.BUCKET_ALREADY_EXISTS,
        omResponse.getStatus());
  }

  @Test
  public void preExecuteRejectsInvalidReplication() {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();

    OzoneManagerProtocolProtos.BucketInfo.Builder bucketInfo =
        newBucketInfoBuilder(bucketName, volumeName);

    ECReplicationConfig invalidReplication = new ECReplicationConfig(1, 2);
    bucketInfo.setDefaultReplicationConfig(
        new DefaultReplicationConfig(invalidReplication).toProto());

    OMException e = assertThrows(OMException.class,
        () -> doPreExecute(bucketInfo));

    Assert.assertEquals(OMException.ResultCodes.INVALID_REQUEST,
        e.getResult());
  }

  @Test
  public void testValidateAndUpdateCacheVerifyBucketLayout() throws Exception {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    String bucketKey = omMetadataManager.getBucketKey(volumeName, bucketName);

    OMBucketCreateRequest omBucketCreateRequest = doPreExecute(volumeName,
        bucketName);

    doValidateAndUpdateCache(volumeName, bucketName,
        omBucketCreateRequest.getOmRequest());

    Assert.assertEquals(BucketLayout.FILE_SYSTEM_OPTIMIZED,
        omMetadataManager.getBucketTable().get(bucketKey).getBucketLayout());
  }

  @Test
  public void testValidateAndUpdateCacheCrossSpaceQuota() throws Exception {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();

    // create a volume with less quota
    OmVolumeArgs omVolumeArgs =
        OmVolumeArgs.newBuilder().setCreationTime(Time.now())
            .setQuotaInBytes(100)
            .setVolume(volumeName).setAdminName(UUID.randomUUID().toString())
            .setOwnerName(UUID.randomUUID().toString()).build();
    OMRequestTestUtils.addVolumeToOM(omMetadataManager, omVolumeArgs);
    
    // create a bucket require higher bucket quota
    OzoneManagerProtocolProtos.BucketInfo bucketInfo =
        OzoneManagerProtocolProtos.BucketInfo.newBuilder()
            .setBucketName(bucketName)
            .setVolumeName(volumeName)
            .setIsVersionEnabled(false)
            .setQuotaInBytes(99999L)
            .setStorageType(StorageTypeProto.SSD)
            .addAllMetadata(OMRequestTestUtils.getMetadataList()).build();
    OzoneManagerProtocolProtos.CreateBucketRequest.Builder req =
        OzoneManagerProtocolProtos.CreateBucketRequest.newBuilder();
    req.setBucketInfo(bucketInfo);
    OMRequest originalRequest = OzoneManagerProtocolProtos.OMRequest
        .newBuilder()
        .setCreateBucketRequest(req)
        .setCmdType(OzoneManagerProtocolProtos.Type.CreateBucket)
        .setClientId(UUID.randomUUID().toString()).build();

    OMBucketCreateRequest omBucketCreateRequest =
        new OMBucketCreateRequest(originalRequest);

    OMRequest modifiedRequest = omBucketCreateRequest.preExecute(ozoneManager);
    verifyRequest(modifiedRequest, originalRequest);

    OMBucketCreateRequest testRequest =
        new OMBucketCreateRequest(modifiedRequest);
    OMClientResponse resp = testRequest.validateAndUpdateCache(
        ozoneManager, 1, ozoneManagerDoubleBufferHelper);

    Assert.assertEquals(resp.getOMResponse().getStatus().toString(),
        OMException.ResultCodes.QUOTA_EXCEEDED.toString());
  }

  @Test
  public void testValidateAndUpdateCacheBucketWithNoQuotaWhenVolumeQuotaSet()
      throws Exception {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();

    OMRequestTestUtils.addVolumeToDB(volumeName, omMetadataManager, 1000L);

    // create a bucket with no quota
    OMRequest originalRequest = newCreateBucketRequest(
        newBucketInfoBuilder(bucketName, volumeName))
        .build();
    OMBucketCreateRequest omBucketCreateRequest =
        new OMBucketCreateRequest(originalRequest);
    OMRequest modifiedRequest = omBucketCreateRequest.preExecute(ozoneManager);
    OMBucketCreateRequest testRequest =
        new OMBucketCreateRequest(modifiedRequest);
    OMClientResponse resp = testRequest.validateAndUpdateCache(
        ozoneManager, 1, ozoneManagerDoubleBufferHelper);

    Assert.assertEquals(resp.getOMResponse().getStatus().toString(),
        OMException.ResultCodes.QUOTA_ERROR.toString());
  }

  @Test
  public void 
        testAcceptS3CompliantBucketNameCreationRegardlessOfStrictS3Setting()
        throws Exception {
    String volumeName = UUID.randomUUID().toString();
    boolean[] omStrictS3Configs = {true, false};
    for (boolean isStrictS3 : omStrictS3Configs) {
      when(ozoneManager.isStrictS3()).thenReturn(isStrictS3);
      String bucketName = UUID.randomUUID().toString();
      acceptBucketCreationHelper(volumeName, bucketName);
    }
  }

  @Test
  public void testRejectNonS3CompliantBucketNameCreationWithStrictS3True()
        throws Exception {
    String volumeName = UUID.randomUUID().toString();
    String[] nonS3CompliantBucketName = 
        {"bucket_underscore", "_bucket___multi_underscore_", "bucket_"};
    when(ozoneManager.isStrictS3()).thenReturn(true);
    for (String bucketName : nonS3CompliantBucketName) {
      rejectBucketCreationHelper(volumeName, bucketName);
    }
  }

  @Test
  public void testAcceptNonS3CompliantBucketNameCreationWithStrictS3False()
        throws Exception {
    String volumeName = UUID.randomUUID().toString();
    String[] nonS3CompliantBucketName = 
        {"bucket_underscore", "_bucket___multi_underscore_", "bucket_"};
    when(ozoneManager.isStrictS3()).thenReturn(false);
    for (String bucketName : nonS3CompliantBucketName) {
      acceptBucketCreationHelper(volumeName, bucketName);
    }
  }

  private void acceptBucketCreationHelper(String volumeName, String bucketName)
        throws Exception {
    OMBucketCreateRequest omBucketCreateRequest = 
        doPreExecute(volumeName, bucketName);
    doValidateAndUpdateCache(volumeName, bucketName,
        omBucketCreateRequest.getOmRequest());
  }

  private void rejectBucketCreationHelper(String volumeName, 
        String bucketName) {
    Throwable e = assertThrows(OMException.class, () ->
        doPreExecute(volumeName, bucketName));
    Assert.assertEquals(e.getMessage(), "Invalid bucket name: " + bucketName);
  }

  protected OMBucketCreateRequest doPreExecute(String volumeName,
      String bucketName) throws Exception {
    return doPreExecute(newBucketInfoBuilder(bucketName, volumeName));
  }

  private OMBucketCreateRequest doPreExecute(
      OzoneManagerProtocolProtos.BucketInfo.Builder bucketInfo)
      throws Exception {

    addCreateVolumeToTable(bucketInfo.getVolumeName(), omMetadataManager);

    OMRequest originalRequest = newCreateBucketRequest(bucketInfo).build();

    OMBucketCreateRequest omBucketCreateRequest =
        new OMBucketCreateRequest(originalRequest);

    OMRequest modifiedRequest = omBucketCreateRequest.preExecute(ozoneManager);
    verifyRequest(modifiedRequest, originalRequest);
    return new OMBucketCreateRequest(modifiedRequest);
  }

  protected void doValidateAndUpdateCache(String volumeName, String bucketName,
      OMRequest modifiedRequest) throws Exception {
    String bucketKey = omMetadataManager.getBucketKey(volumeName, bucketName);

    // As we have not still called validateAndUpdateCache, get() should
    // return null.

    Assert.assertNull(omMetadataManager.getBucketTable().get(bucketKey));
    OMBucketCreateRequest omBucketCreateRequest =
        new OMBucketCreateRequest(modifiedRequest);


    OMClientResponse omClientResponse =
        omBucketCreateRequest.validateAndUpdateCache(ozoneManager, 1,
            ozoneManagerDoubleBufferHelper);

    // As now after validateAndUpdateCache it should add entry to cache, get
    // should return non null value.
    OmBucketInfo dbBucketInfo =
        omMetadataManager.getBucketTable().get(bucketKey);
    Assert.assertNotNull(omMetadataManager.getBucketTable().get(bucketKey));

    // verify table data with actual request data.
    OmBucketInfo bucketInfoFromProto = OmBucketInfo.getFromProtobuf(
        modifiedRequest.getCreateBucketRequest().getBucketInfo());

    Assert.assertEquals(bucketInfoFromProto.getCreationTime(),
        dbBucketInfo.getCreationTime());
    Assert.assertEquals(bucketInfoFromProto.getModificationTime(),
        dbBucketInfo.getModificationTime());
    Assert.assertEquals(bucketInfoFromProto.getAcls(),
        dbBucketInfo.getAcls());
    Assert.assertEquals(bucketInfoFromProto.getIsVersionEnabled(),
        dbBucketInfo.getIsVersionEnabled());
    Assert.assertEquals(bucketInfoFromProto.getStorageType(),
        dbBucketInfo.getStorageType());
    Assert.assertEquals(bucketInfoFromProto.getMetadata(),
        dbBucketInfo.getMetadata());
    Assert.assertEquals(bucketInfoFromProto.getEncryptionKeyInfo(),
        dbBucketInfo.getEncryptionKeyInfo());

    // verify OMResponse.
    verifySuccessCreateBucketResponse(omClientResponse.getOMResponse());

  }

  protected void verifyRequest(OMRequest modifiedOmRequest,
      OMRequest originalRequest) {
    OzoneManagerProtocolProtos.BucketInfo original =
        originalRequest.getCreateBucketRequest().getBucketInfo();
    OzoneManagerProtocolProtos.BucketInfo updated =
        modifiedOmRequest.getCreateBucketRequest().getBucketInfo();

    Assert.assertEquals(original.getBucketName(), updated.getBucketName());
    Assert.assertEquals(original.getVolumeName(), updated.getVolumeName());
    Assert.assertEquals(original.getIsVersionEnabled(),
        updated.getIsVersionEnabled());
    Assert.assertEquals(original.getStorageType(), updated.getStorageType());
    Assert.assertEquals(original.getMetadataList(), updated.getMetadataList());
    Assert.assertNotEquals(original.getCreationTime(),
        updated.getCreationTime());
  }

  public static void verifySuccessCreateBucketResponse(OMResponse omResponse) {
    Assert.assertNotNull(omResponse.getCreateBucketResponse());
    Assert.assertEquals(OzoneManagerProtocolProtos.Type.CreateBucket,
        omResponse.getCmdType());
    Assert.assertEquals(OzoneManagerProtocolProtos.Status.OK,
        omResponse.getStatus());
  }

  public static void addCreateVolumeToTable(String volumeName,
      OMMetadataManager omMetadataManager) throws Exception {
    OmVolumeArgs omVolumeArgs =
        OmVolumeArgs.newBuilder().setCreationTime(Time.now())
            .setVolume(volumeName).setAdminName(UUID.randomUUID().toString())
            .setOwnerName(UUID.randomUUID().toString()).build();
    OMRequestTestUtils.addVolumeToOM(omMetadataManager, omVolumeArgs);
  }
}

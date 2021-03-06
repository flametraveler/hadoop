/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.ozone.om;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.RandomStringUtils;
import org.apache.hadoop.conf.StorageUnit;
import org.apache.hadoop.hdds.HddsConfigKeys;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos.ReplicationFactor;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos.ReplicationType;
import org.apache.hadoop.hdds.scm.TestUtils;
import org.apache.hadoop.hdds.scm.container.MockNodeManager;
import org.apache.hadoop.hdds.scm.container.common.helpers.ExcludeList;
import org.apache.hadoop.hdds.scm.exceptions.SCMException;
import org.apache.hadoop.hdds.scm.exceptions.SCMException.ResultCodes;
import org.apache.hadoop.hdds.scm.node.NodeManager;
import org.apache.hadoop.hdds.scm.protocol.ScmBlockLocationProtocol;
import org.apache.hadoop.hdds.scm.server.SCMConfigurator;
import org.apache.hadoop.hdds.scm.server.StorageContainerManager;
import org.apache.hadoop.ozone.om.exceptions.OMException;
import org.apache.hadoop.ozone.om.helpers.*;
import org.apache.hadoop.test.GenericTestUtils;
import org.apache.hadoop.test.LambdaTestUtils;

import org.junit.After;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.AfterClass;
import org.junit.Test;
import org.mockito.Mockito;

import static org.apache.hadoop.ozone.OzoneConfigKeys.*;

/**
 * Test class for @{@link KeyManagerImpl}.
 */
public class TestKeyManagerImpl {

  private static KeyManagerImpl keyManager;
  private static VolumeManagerImpl volumeManager;
  private static BucketManagerImpl bucketManager;
  private static StorageContainerManager scm;
  private static ScmBlockLocationProtocol mockScmBlockLocationProtocol;
  private static OzoneConfiguration conf;
  private static OMMetadataManager metadataManager;
  private static File dir;
  private static long scmBlockSize;
  private static final String KEY_NAME = "key1";
  private static final String BUCKET_NAME = "bucket1";
  private static final String VOLUME_NAME = "vol1";

  @BeforeClass
  public static void setUp() throws Exception {
    conf = new OzoneConfiguration();
    dir = GenericTestUtils.getRandomizedTestDir();
    conf.set(HddsConfigKeys.OZONE_METADATA_DIRS, dir.toString());
    mockScmBlockLocationProtocol = Mockito.mock(ScmBlockLocationProtocol.class);
    metadataManager = new OmMetadataManagerImpl(conf);
    volumeManager = new VolumeManagerImpl(metadataManager, conf);
    bucketManager = new BucketManagerImpl(metadataManager);
    NodeManager nodeManager = new MockNodeManager(true, 10);
    SCMConfigurator configurator = new SCMConfigurator();
    configurator.setScmNodeManager(nodeManager);
    scm = TestUtils.getScm(conf, configurator);
    scm.start();
    scm.exitSafeMode();
    scmBlockSize = (long) conf
        .getStorageSize(OZONE_SCM_BLOCK_SIZE, OZONE_SCM_BLOCK_SIZE_DEFAULT,
            StorageUnit.BYTES);
    conf.setLong(OZONE_KEY_PREALLOCATION_BLOCKS_MAX, 10);

    keyManager =
        new KeyManagerImpl(scm.getBlockProtocolServer(), metadataManager, conf,
            "om1", null);
    Mockito.when(mockScmBlockLocationProtocol
        .allocateBlock(Mockito.anyLong(), Mockito.anyInt(),
            Mockito.any(ReplicationType.class),
            Mockito.any(ReplicationFactor.class), Mockito.anyString(),
            Mockito.any(ExcludeList.class))).thenThrow(
        new SCMException("SafeModePrecheck failed for allocateBlock",
            ResultCodes.SAFE_MODE_EXCEPTION));
    createVolume(VOLUME_NAME);
    createBucket(VOLUME_NAME, BUCKET_NAME);
  }

  @AfterClass
  public static void cleanup() throws Exception {
    scm.stop();
    scm.join();
    metadataManager.stop();
    keyManager.stop();
    FileUtils.deleteDirectory(dir);
  }

  @After
  public void cleanupTest() throws IOException {
    List<OzoneFileStatus> fileStatuses = keyManager
        .listStatus(createBuilder().setKeyName("").build(), true, "", 100000);
    for (OzoneFileStatus fileStatus : fileStatuses) {
      if (fileStatus.isFile()) {
        keyManager.deleteKey(
            createKeyArgs(fileStatus.getPath().toString().substring(1)));
      } else {
        keyManager.deleteKey(createKeyArgs(OzoneFSUtils
            .addTrailingSlashIfNeeded(
                fileStatus.getPath().toString().substring(1))));
      }
    }
  }

  private static void createBucket(String volumeName, String bucketName)
      throws IOException {
    OmBucketInfo bucketInfo = OmBucketInfo.newBuilder()
        .setVolumeName(volumeName)
        .setBucketName(bucketName)
        .build();
    bucketManager.createBucket(bucketInfo);
  }

  private static void createVolume(String volumeName) throws IOException {
    OmVolumeArgs volumeArgs = OmVolumeArgs.newBuilder()
        .setVolume(volumeName)
        .setAdminName("bilbo")
        .setOwnerName("bilbo")
        .build();
    volumeManager.createVolume(volumeArgs);
  }

  @Test
  public void allocateBlockFailureInSafeMode() throws Exception {
    KeyManager keyManager1 = new KeyManagerImpl(mockScmBlockLocationProtocol,
        metadataManager, conf, "om1", null);
    OmKeyArgs keyArgs = createBuilder()
        .setKeyName(KEY_NAME)
        .build();
    OpenKeySession keySession = keyManager1.openKey(keyArgs);
    LambdaTestUtils.intercept(OMException.class,
        "SafeModePrecheck failed for allocateBlock", () -> {
          keyManager1
              .allocateBlock(keyArgs, keySession.getId(), new ExcludeList());
        });
  }

  @Test
  public void openKeyFailureInSafeMode() throws Exception {
    KeyManager keyManager1 = new KeyManagerImpl(mockScmBlockLocationProtocol,
        metadataManager, conf, "om1", null);
    OmKeyArgs keyArgs = createBuilder()
        .setKeyName(KEY_NAME)
        .setDataSize(1000)
        .build();
    LambdaTestUtils.intercept(OMException.class,
        "SafeModePrecheck failed for allocateBlock", () -> {
          keyManager1.openKey(keyArgs);
        });
  }

  @Test
  public void openKeyWithMultipleBlocks() throws IOException {
    OmKeyArgs keyArgs = createBuilder()
        .setKeyName(UUID.randomUUID().toString())
        .setDataSize(scmBlockSize * 10)
        .build();
    OpenKeySession keySession = keyManager.openKey(keyArgs);
    OmKeyInfo keyInfo = keySession.getKeyInfo();
    Assert.assertEquals(10,
        keyInfo.getLatestVersionLocations().getLocationList().size());
  }

  @Test
  public void testCreateDirectory() throws IOException {
    // Create directory where the parent directory does not exist
    String keyName = RandomStringUtils.randomAlphabetic(5);
    OmKeyArgs keyArgs = createBuilder()
        .setKeyName(keyName)
        .build();
    for (int i =0; i< 5; i++) {
      keyName += "/" + RandomStringUtils.randomAlphabetic(5);
    }
    keyManager.createDirectory(keyArgs);
    Path path = Paths.get(keyName);
    while (path != null) {
      // verify parent directories are created
      Assert.assertTrue(keyManager.getFileStatus(keyArgs).isDirectory());
      path = path.getParent();
    }

    // make sure create directory fails where parent is a file
    keyName = RandomStringUtils.randomAlphabetic(5);
    keyArgs = createBuilder()
        .setKeyName(keyName)
        .build();
    OpenKeySession keySession = keyManager.openKey(keyArgs);
    keyArgs.setLocationInfoList(
        keySession.getKeyInfo().getLatestVersionLocations().getLocationList());
    keyManager.commitKey(keyArgs, keySession.getId());
    for (int i =0; i< 5; i++) {
      keyName += "/" + RandomStringUtils.randomAlphabetic(5);
    }
    try {
      keyManager.createDirectory(keyArgs);
      Assert.fail("Creation should fail for directory.");
    } catch (OMException e) {
      Assert.assertEquals(e.getResult(),
          OMException.ResultCodes.FILE_ALREADY_EXISTS);
    }

    // create directory for root directory
    keyName = "";
    keyArgs = createBuilder()
        .setKeyName(keyName)
        .build();
    keyManager.createDirectory(keyArgs);
    Assert.assertTrue(keyManager.getFileStatus(keyArgs).isDirectory());

    // create directory where parent is root
    keyName = RandomStringUtils.randomAlphabetic(5);
    keyArgs = createBuilder()
        .setKeyName(keyName)
        .build();
    keyManager.createDirectory(keyArgs);
    Assert.assertTrue(keyManager.getFileStatus(keyArgs).isDirectory());
  }

  @Test
  public void testOpenFile() throws IOException {
    // create key
    String keyName = RandomStringUtils.randomAlphabetic(5);
    OmKeyArgs keyArgs = createBuilder()
        .setKeyName(keyName)
        .build();
    OpenKeySession keySession = keyManager.createFile(keyArgs, false, false);
    keyArgs.setLocationInfoList(
        keySession.getKeyInfo().getLatestVersionLocations().getLocationList());
    keyManager.commitKey(keyArgs, keySession.getId());

    // try to open created key with overWrite flag set to false
    try {
      keyManager.createFile(keyArgs, false, false);
      Assert.fail("Open key should fail for non overwrite create");
    } catch (OMException ex) {
      if (ex.getResult() != OMException.ResultCodes.FILE_ALREADY_EXISTS) {
        throw ex;
      }
    }

    // create file should pass with overwrite flag set to true
    keyManager.createFile(keyArgs, true, false);

    // try to create a file where parent directories do not exist and
    // recursive flag is set to false
    keyName = RandomStringUtils.randomAlphabetic(5);
    for (int i =0; i< 5; i++) {
      keyName += "/" + RandomStringUtils.randomAlphabetic(5);
    }
    keyArgs = createBuilder()
        .setKeyName(keyName)
        .build();
    try {
      keyManager.createFile(keyArgs, false, false);
      Assert.fail("Open file should fail for non recursive write");
    } catch (OMException ex) {
      if (ex.getResult() != OMException.ResultCodes.DIRECTORY_NOT_FOUND) {
        throw ex;
      }
    }

    // file create should pass when recursive flag is set to true
    keySession = keyManager.createFile(keyArgs, false, true);
    keyArgs.setLocationInfoList(
        keySession.getKeyInfo().getLatestVersionLocations().getLocationList());
    keyManager.commitKey(keyArgs, keySession.getId());
    Assert.assertTrue(keyManager
        .getFileStatus(keyArgs).isFile());

    // try creating a file over a directory
    keyArgs = createBuilder()
        .setKeyName("")
        .build();
    try {
      keyManager.createFile(keyArgs, true, true);
      Assert.fail("Open file should fail for non recursive write");
    } catch (OMException ex) {
      if (ex.getResult() != OMException.ResultCodes.NOT_A_FILE) {
        throw ex;
      }
    }
  }

  @Test
  public void testLookupFile() throws IOException {
    String keyName = RandomStringUtils.randomAlphabetic(5);
    OmKeyArgs keyArgs = createBuilder()
        .setKeyName(keyName)
        .build();

    // lookup for a non-existent file
    try {
      keyManager.lookupFile(keyArgs);
      Assert.fail("Lookup file should fail for non existent file");
    } catch (OMException ex) {
      if (ex.getResult() != OMException.ResultCodes.FILE_NOT_FOUND) {
        throw ex;
      }
    }

    // create a file
    OpenKeySession keySession = keyManager.createFile(keyArgs, false, false);
    keyArgs.setLocationInfoList(
        keySession.getKeyInfo().getLatestVersionLocations().getLocationList());
    keyManager.commitKey(keyArgs, keySession.getId());
    Assert.assertEquals(keyManager.lookupFile(keyArgs).getKeyName(), keyName);

    // lookup for created file
    keyArgs = createBuilder()
        .setKeyName("")
        .build();
    try {
      keyManager.lookupFile(keyArgs);
      Assert.fail("Lookup file should fail for a directory");
    } catch (OMException ex) {
      if (ex.getResult() != OMException.ResultCodes.NOT_A_FILE) {
        throw ex;
      }
    }
  }

  private OmKeyArgs createKeyArgs(String toKeyName) {
    return createBuilder().setKeyName(toKeyName).build();
  }

  @Test
  public void testListStatus() throws IOException {
    String superDir = RandomStringUtils.randomAlphabetic(5);

    int numDirectories = 5;
    int numFiles = 5;
    // set of directory descendants of root
    Set<String> directorySet = new TreeSet<>();
    // set of file descendants of root
    Set<String> fileSet = new TreeSet<>();
    createDepthTwoDirectory(superDir, numDirectories, numFiles, directorySet,
        fileSet);
    // set of all descendants of root
    Set<String> children = new TreeSet<>(directorySet);
    children.addAll(fileSet);
    // number of entries in the filesystem
    int numEntries = directorySet.size() + fileSet.size();

    OmKeyArgs rootDirArgs = createKeyArgs("");
    List<OzoneFileStatus> fileStatuses =
        keyManager.listStatus(rootDirArgs, true, "", 100);
    // verify the number of status returned is same as number of entries
    Assert.assertEquals(numEntries, fileStatuses.size());

    fileStatuses = keyManager.listStatus(rootDirArgs, false, "", 100);
    // the number of immediate children of root is 1
    Assert.assertEquals(1, fileStatuses.size());

    // if startKey is the first descendant of the root then listStatus should
    // return all the entries.
    String startKey = children.iterator().next();
    fileStatuses = keyManager.listStatus(rootDirArgs, true,
        startKey.substring(0, startKey.length() - 1), 100);
    Assert.assertEquals(numEntries, fileStatuses.size());

    for (String directory : directorySet) {
      // verify status list received for each directory with recursive flag set
      // to false
      OmKeyArgs dirArgs = createKeyArgs(directory);
      fileStatuses = keyManager.listStatus(dirArgs, false, "", 100);
      verifyFileStatus(directory, fileStatuses, directorySet, fileSet, false);

      // verify status list received for each directory with recursive flag set
      // to true
      fileStatuses = keyManager.listStatus(dirArgs, true, "", 100);
      verifyFileStatus(directory, fileStatuses, directorySet, fileSet, true);

      // verify list status call with using the startKey parameter and
      // recursive flag set to false. After every call to listStatus use the
      // latest received file status as the startKey until no more entries are
      // left to list.
      List<OzoneFileStatus> tempFileStatus = null;
      Set<OzoneFileStatus> tmpStatusSet = new HashSet<>();
      do {
        tempFileStatus = keyManager.listStatus(dirArgs, false,
            tempFileStatus != null ? OzoneFSUtils.pathToKey(
                tempFileStatus.get(tempFileStatus.size() - 1).getPath()) : null,
            2);
        tmpStatusSet.addAll(tempFileStatus);
      } while (tempFileStatus.size() == 2);
      verifyFileStatus(directory, new ArrayList<>(tmpStatusSet), directorySet,
          fileSet, false);

      // verify list status call with using the startKey parameter and
      // recursive flag set to true. After every call to listStatus use the
      // latest received file status as the startKey until no more entries are
      // left to list.
      tempFileStatus = null;
      tmpStatusSet = new HashSet<>();
      do {
        tempFileStatus = keyManager.listStatus(dirArgs, true,
            tempFileStatus != null ? OzoneFSUtils.pathToKey(
                tempFileStatus.get(tempFileStatus.size() - 1).getPath()) : null,
            2);
        tmpStatusSet.addAll(tempFileStatus);
      } while (tempFileStatus.size() == 2);
      verifyFileStatus(directory, new ArrayList<>(tmpStatusSet), directorySet,
          fileSet, true);
    }
  }

  /**
   * Creates a depth two directory.
   *
   * @param superDir       Super directory to create
   * @param numDirectories number of directory children
   * @param numFiles       number of file children
   * @param directorySet   set of descendant directories for the super directory
   * @param fileSet        set of descendant files for the super directory
   */
  private void createDepthTwoDirectory(String superDir, int numDirectories,
      int numFiles, Set<String> directorySet, Set<String> fileSet)
      throws IOException {
    // create super directory
    OmKeyArgs superDirArgs = createKeyArgs(superDir);
    keyManager.createDirectory(superDirArgs);
    directorySet.add(superDir);

    // add directory children to super directory
    Set<String> childDirectories =
        createDirectories(superDir, new HashMap<>(), numDirectories);
    directorySet.addAll(childDirectories);
    // add file to super directory
    fileSet.addAll(createFiles(superDir, new HashMap<>(), numFiles));

    // for each child directory create files and directories
    for (String child : childDirectories) {
      fileSet.addAll(createFiles(child, new HashMap<>(), numFiles));
      directorySet
          .addAll(createDirectories(child, new HashMap<>(), numDirectories));
    }
  }

  private void verifyFileStatus(String directory,
      List<OzoneFileStatus> fileStatuses, Set<String> directorySet,
      Set<String> fileSet, boolean recursive) {

    for (OzoneFileStatus fileStatus : fileStatuses) {
      String keyName = OzoneFSUtils.pathToKey(fileStatus.getPath());
      String parent = Paths.get(keyName).getParent().toString();
      if (!recursive) {
        // if recursive is false, verify all the statuses have the input
        // directory as parent
        Assert.assertEquals(parent, directory);
      }
      // verify filestatus is present in directory or file set accordingly
      if (fileStatus.isDirectory()) {
        Assert.assertTrue(directorySet.contains(keyName));
      } else {
        Assert.assertTrue(fileSet.contains(keyName));
      }
    }

    // count the number of entries which should be present in the directory
    int numEntries = 0;
    Set<String> entrySet = new TreeSet<>(directorySet);
    entrySet.addAll(fileSet);
    for (String entry : entrySet) {
      if (OzoneFSUtils.getParent(entry)
          .startsWith(OzoneFSUtils.addTrailingSlashIfNeeded(directory))) {
        if (recursive) {
          numEntries++;
        } else if (OzoneFSUtils.getParent(entry)
            .equals(OzoneFSUtils.addTrailingSlashIfNeeded(directory))) {
          numEntries++;
        }
      }
    }
    // verify the number of entries match the status list size
    Assert.assertEquals(fileStatuses.size(), numEntries);
  }

  private Set<String> createDirectories(String parent,
      Map<String, List<String>> directoryMap, int numDirectories)
      throws IOException {
    Set<String> keyNames = new TreeSet<>();
    for (int i = 0; i < numDirectories; i++) {
      String keyName = parent + "/" + RandomStringUtils.randomAlphabetic(5);
      OmKeyArgs keyArgs = createBuilder().setKeyName(keyName).build();
      keyManager.createDirectory(keyArgs);
      keyNames.add(keyName);
    }
    directoryMap.put(parent, new ArrayList<>(keyNames));
    return keyNames;
  }

  private List<String> createFiles(String parent,
      Map<String, List<String>> fileMap, int numFiles) throws IOException {
    List<String> keyNames = new ArrayList<>();
    for (int i = 0; i < numFiles; i++) {
      String keyName = parent + "/" + RandomStringUtils.randomAlphabetic(5);
      OmKeyArgs keyArgs = createBuilder().setKeyName(keyName).build();
      OpenKeySession keySession = keyManager.createFile(keyArgs, false, false);
      keyArgs.setLocationInfoList(
          keySession.getKeyInfo().getLatestVersionLocations()
              .getLocationList());
      keyManager.commitKey(keyArgs, keySession.getId());
      keyNames.add(keyName);
    }
    fileMap.put(parent, keyNames);
    return keyNames;
  }

  private OmKeyArgs.Builder createBuilder() {
    return new OmKeyArgs.Builder()
        .setBucketName(BUCKET_NAME)
        .setFactor(ReplicationFactor.ONE)
        .setDataSize(0)
        .setType(ReplicationType.STAND_ALONE)
        .setVolumeName(VOLUME_NAME);
  }
}
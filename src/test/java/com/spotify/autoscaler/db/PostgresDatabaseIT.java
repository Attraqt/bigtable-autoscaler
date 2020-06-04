/*-
 * -\-\-
 * bigtable-autoscaler
 * --
 * Copyright (C) 2018 Spotify AB
 * --
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * -/-/-
 */

package com.spotify.autoscaler.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.testcontainers.shaded.com.google.common.collect.ImmutableList;

public class PostgresDatabaseIT {

  private PostgresDatabase db;
  private final String projectId = "test-project";
  private final String instanceId = "test-instance";
  private final String clusterId = "test-cluster";

  private BigtableCluster testCluster() {
    return new BigtableClusterBuilder()
        .projectId(projectId)
        .instanceId(instanceId)
        .clusterId(clusterId)
        .minNodes(10)
        .maxNodes(100)
        .cpuTarget(0.8)
        .storageTarget(0.7)
        .overloadStep(10)
        .enabled(true)
        .errorCode(Optional.of(ErrorCode.OK))
        .build();
  }

  private BigtableCluster testClusterForReconcile() {
    return new BigtableClusterBuilder()
        .projectId(projectId)
        .instanceId(instanceId)
        .clusterId(clusterId)
        .minNodes(10)
        .maxNodes(100)
        .cpuTarget(0.8)
        .enabled(true)
        .build();
  }

  private BigtableCluster anotherTestCluster() {
    return new BigtableClusterBuilder()
        .projectId("another-project")
        .instanceId("another-instance")
        .clusterId("another-cluster")
        .minNodes(10)
        .maxNodes(100)
        .cpuTarget(0.8)
        .storageTarget(0.7)
        .overloadStep(10)
        .enabled(true)
        .errorCode(Optional.of(ErrorCode.OK))
        .minNodesOverride(10)
        .build();
  }

  @Before
  public void setup() {
    // insert a test cluster
    db = PostgresDatabaseTest.getPostgresDatabase();
  }

  @After
  public void tearDown() {
    db.getBigtableClusters()
        .forEach(
            cluster ->
                db.deleteBigtableCluster(
                    cluster.projectId(), cluster.instanceId(), cluster.clusterId()));
    db.close();
  }

  @Test
  public void testSetupAndConnection() {
    assertEquals(0, db.getBigtableClusters().size());
  }

  @Test
  public void setLastChangeTest() {
    db.insertBigtableCluster(testCluster());

    final Instant now = Instant.now();
    db.setLastChange(projectId, instanceId, clusterId, now);
    final Optional<BigtableCluster> btcluster =
        db.getBigtableCluster(projectId, instanceId, clusterId);
    assertTrue(btcluster.isPresent());
    final Optional<Instant> alsoNow = btcluster.get().lastChange();
    assertTrue(alsoNow.isPresent());
    assertEquals(now, alsoNow.get());
  }

  @Test
  public void insert() {
    db.insertBigtableCluster(testCluster());
    assertEquals(ImmutableList.of(testCluster()), db.getBigtableClusters());
  }

  @Test
  public void update() {
    db.insertBigtableCluster(BigtableClusterBuilder.from(testCluster()).minNodes(5).build());
    assertNotEquals(ImmutableList.of(testCluster()), db.getBigtableClusters());
    db.updateBigtableCluster(testCluster());
    assertEquals(ImmutableList.of(testCluster()), db.getBigtableClusters());
  }

  @Test
  public void enable() {
    db.insertBigtableCluster(BigtableClusterBuilder.from(testCluster()).enabled(false).build());
    assertNotEquals(ImmutableList.of(testCluster()), db.getBigtableClusters());
    db.updateBigtableCluster(testCluster());
    assertEquals(ImmutableList.of(testCluster()), db.getBigtableClusters());
  }

  @Test
  public void delete() {
    db.insertBigtableCluster(testCluster());
    db.insertBigtableCluster(anotherTestCluster());
    db.deleteBigtableCluster(projectId, instanceId, clusterId);
    assertEquals(1, db.getBigtableClusters().size());
  }

  @Test
  public void getCandidateClusters() {
    final BigtableCluster c1 =
        BigtableClusterBuilder.from(testCluster())
            .clusterId("c1")
            .lastCheck(Instant.ofEpochSecond(1000))
            .build();
    final BigtableCluster c2 = BigtableClusterBuilder.from(testCluster()).clusterId("c2").build();
    final BigtableCluster c3 =
        BigtableClusterBuilder.from(testCluster())
            .clusterId("c3")
            .lastCheck(Instant.now().minus(Duration.ofSeconds(3)))
            .build();
    final BigtableCluster c4 =
        BigtableClusterBuilder.from(testCluster())
            .clusterId("c4")
            .lastCheck(Instant.ofEpochSecond(500))
            .build();
    final BigtableCluster c5 =
        BigtableClusterBuilder.from(testCluster()).clusterId("c5").enabled(false).build();

    for (final BigtableCluster cluster : Arrays.asList(c1, c2, c3, c4, c5)) {
      db.insertBigtableCluster(cluster);

      cluster
          .lastCheck()
          .ifPresent(
              lastCheck ->
                  db.setLastCheck(
                      cluster.projectId(), cluster.instanceId(), cluster.clusterId(), lastCheck));
    }

    // Verify that the order is as expected
    final List<BigtableCluster> clusters = db.getCandidateClusters();
    assertEquals(3, clusters.size());
    assertEquals(c2, clusters.get(0));
    assertEquals(c4, clusters.get(1));
    assertEquals(c1, clusters.get(2));
  }

  @Test
  public void updateLastCheckedSetIfMatches() {
    final BigtableCluster c1 =
        BigtableClusterBuilder.from(testCluster()).lastCheck(Instant.ofEpochSecond(1000)).build();
    db.insertBigtableCluster(testCluster()); // This doesn't set lastCheck!
    db.setLastCheck(c1.projectId(), c1.instanceId(), c1.clusterId(), c1.lastCheck().get());

    assertTrue(db.updateLastChecked(c1));

    // Verify that the database object actually got updated
    final BigtableCluster updated =
        db.getBigtableCluster(c1.projectId(), c1.instanceId(), c1.clusterId()).get();
    assertTrue(updated.lastCheck().get().isAfter(c1.lastCheck().get()));
  }

  @Test
  public void updateLastCheckedSetIfFirstTime() {
    final BigtableCluster c1 = BigtableClusterBuilder.from(testCluster()).build();
    db.insertBigtableCluster(testCluster()); // This doesn't set lastCheck!

    // Slight sanity check here
    final BigtableCluster notUpdated =
        db.getBigtableCluster(c1.projectId(), c1.instanceId(), c1.clusterId()).get();
    assertFalse(notUpdated.lastCheck().isPresent());

    assertTrue(db.updateLastChecked(c1));

    // Verify that the database object actually got updated
    final BigtableCluster updated =
        db.getBigtableCluster(c1.projectId(), c1.instanceId(), c1.clusterId()).get();
    assertTrue(updated.lastCheck().isPresent());
  }

  @Test
  public void updateLastCheckedNoSetIfNotMatches() {
    final BigtableCluster c1 =
        BigtableClusterBuilder.from(testCluster()).lastCheck(Instant.ofEpochSecond(1000)).build();
    db.insertBigtableCluster(testCluster()); // This doesn't set lastCheck!
    db.setLastCheck(c1.projectId(), c1.instanceId(), c1.clusterId(), Instant.ofEpochSecond(2000));

    assertFalse(db.updateLastChecked(c1));

    // Verify that the database object did not get updated
    final BigtableCluster updated =
        db.getBigtableCluster(c1.projectId(), c1.instanceId(), c1.clusterId()).get();
    assertEquals(Instant.ofEpochSecond(2000), updated.lastCheck().get());
  }

  @Test
  public void updateLastCheckedNoSetIfFirstTimeButNoMatch() {
    final BigtableCluster c1 = BigtableClusterBuilder.from(testCluster()).build();
    db.insertBigtableCluster(testCluster()); // This doesn't set lastCheck!
    db.setLastCheck(c1.projectId(), c1.instanceId(), c1.clusterId(), Instant.ofEpochSecond(2000));

    assertFalse(db.updateLastChecked(c1));

    // Verify that the database object did not get updated
    final BigtableCluster updated =
        db.getBigtableCluster(c1.projectId(), c1.instanceId(), c1.clusterId()).get();
    assertEquals(Instant.ofEpochSecond(2000), updated.lastCheck().get());
  }

  @Test
  public void testInsertedAndRetrievedClustersAreEquivalent() {

    final BigtableCluster cluster =
        BigtableClusterBuilder.from(testCluster()).overloadStep(Optional.ofNullable(null)).build();

    db.insertBigtableCluster(cluster);
    db.insertBigtableCluster(anotherTestCluster());

    final BigtableCluster retrievedCluster =
        db.getBigtableCluster(cluster.projectId(), cluster.instanceId(), cluster.clusterId())
            .orElseThrow(() -> new RuntimeException("Inserted cluster not present!!"));

    assertEquals(cluster, retrievedCluster);
  }

  @Test
  public void testSetMinNodesOverride() {
    db.insertBigtableCluster(testCluster());
    db.setMinNodesOverride(
        testCluster().projectId(), testCluster().instanceId(), testCluster().clusterId(), 42);

    final BigtableCluster retrievedCluster =
        db.getBigtableCluster(
                testCluster().projectId(), testCluster().instanceId(), testCluster().clusterId())
            .orElseThrow(() -> new RuntimeException("Inserted cluster not present!!"));

    assertEquals(42, retrievedCluster.minNodesOverride());
  }

  @Test
  public void testReconcileNewCluster() {
    db.reconcileBigtableCluster(testClusterForReconcile());
    final BigtableCluster retrievedCluster =
        db.getBigtableCluster(
            testCluster().projectId(), testCluster().instanceId(), testCluster().clusterId())
            .orElseThrow(() -> new RuntimeException("Inserted cluster not present!!"));

    assertEquals(0.7, retrievedCluster.storageTarget(), 0.0001);
    assertTrue(retrievedCluster.overloadStep().isEmpty());
    assertTrue(retrievedCluster.extraEnabledAlgorithms().isEmpty());
    assertEquals(10, retrievedCluster.minNodes());
    assertEquals(100, retrievedCluster.maxNodes());
  }

  @Test
  public void testReconcileExistingCluster() {
    db.insertBigtableCluster(BigtableClusterBuilder.from(testCluster())
        .minNodes(15)
        .maxNodes(50)
        .storageTarget(0.6)
        .overloadStep(5)
        .extraEnabledAlgorithms("any")
        .build());
    db.reconcileBigtableCluster(testCluster());
    final BigtableCluster retrievedCluster =
        db.getBigtableCluster(
            testCluster().projectId(), testCluster().instanceId(), testCluster().clusterId())
            .orElseThrow(() -> new RuntimeException("Inserted cluster not present!!"));

    assertEquals(0.6, retrievedCluster.storageTarget(), 0.0001);
    assertEquals(5, retrievedCluster.overloadStep().get().intValue());
    assertEquals("any", retrievedCluster.extraEnabledAlgorithms().get());
    assertEquals(10, retrievedCluster.minNodes());
    assertEquals(100, retrievedCluster.maxNodes());
  }

}

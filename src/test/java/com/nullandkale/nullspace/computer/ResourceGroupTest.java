package com.nullandkale.nullspace.computer;

import static org.junit.jupiter.api.Assertions.*;

import com.nullandkale.nullspace.server.ResourceGroup;
import java.util.*;
import org.junit.jupiter.api.Test;

class ResourceGroupTest {
  @Test
  void partialConstructionRollsBackInReverseOrderOnce() {
    var actions = new ArrayList<Integer>();
    var group = new ResourceGroup();
    group.defer(() -> actions.add(1));
    group.defer(() -> actions.add(2));
    group.rollback(new IllegalArgumentException("construction"));
    group.close();
    assertEquals(List.of(2, 1), actions);
    assertEquals(0, group.size());
  }

  @Test
  void failingCleanupCannotLeakRemainingResources() {
    var actions = new ArrayList<Integer>();
    var group = new ResourceGroup();
    group.defer(() -> actions.add(1));
    group.defer(
        () -> {
          throw new IllegalStateException("close");
        });
    group.defer(() -> actions.add(3));
    var failure = new IllegalArgumentException("construction");
    group.rollback(failure);
    assertEquals(List.of(3, 1), actions);
    assertEquals(1, failure.getSuppressed().length);
    assertEquals(0, group.size());
  }

  @Test
  void errorsDuringCleanupStillReleaseEverythingAndPreserveTheOriginalFailure() {
    int baseline = ResourceGroup.liveEntries();
    var actions = new ArrayList<Integer>();
    var group = new ResourceGroup();
    group.defer(() -> actions.add(1));
    group.defer(
        () -> {
          throw new AssertionError("driver cleanup error");
        });
    group.defer(() -> actions.add(3));
    var original = new IllegalStateException("construction failure");
    group.rollback(original);
    group.close();
    assertEquals(List.of(3, 1), actions);
    assertInstanceOf(AssertionError.class, original.getSuppressed()[0]);
    assertEquals(baseline, ResourceGroup.liveEntries());
  }

  @Test
  void repeatedCleanupFailuresKeepTheirIdentityWithoutSelfSuppression() {
    var group = new ResourceGroup();
    var repeated = new IllegalStateException("same failure");
    group.defer(
        () -> {
          throw repeated;
        });
    group.defer(
        () -> {
          throw repeated;
        });
    assertSame(repeated, assertThrows(IllegalStateException.class, group::close));
    assertEquals(0, group.size());
    group.close();
    assertThrows(IllegalStateException.class, () -> group.defer(() -> {}));
  }
}

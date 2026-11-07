package com.nullandkale.nullspace.model.computer;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.*;

class VirtualFilesTest {
  private static final String CWD = "/home/crew";

  @Test
  void pathsNormalizeRelativeAbsoluteAndDotSegments() {
    assertEquals("/home/crew/a", VirtualFiles.path(CWD, "a"));
    assertEquals("/home/crew/a", VirtualFiles.path(CWD, "./a"));
    assertEquals("/home/a", VirtualFiles.path(CWD, "../a"));
    assertEquals("/etc/motd", VirtualFiles.path(CWD, "/etc/motd"));
    assertEquals("/etc/motd", VirtualFiles.path(CWD, "/etc//motd/"));
    assertEquals("/", VirtualFiles.path(CWD, "/"));
    assertEquals(
        CWD, VirtualFiles.path(CWD, ""), "An empty name resolves to the working directory");
  }

  @Test
  void parentSegmentsCannotClimbAboveTheRoot() {
    assertEquals("/", VirtualFiles.path(CWD, "../../../../../.."));
    assertEquals("/etc", VirtualFiles.path(CWD, "../../../../etc"));
    assertEquals("/x", VirtualFiles.path("/", "../x"));
  }

  @Test
  void pathsRejectUnsafeOrOversizedNames() {
    for (String bad :
        new String[] {"a b", "a\\b", "a\0b", "a:b", "é", "a*", "x".repeat(257), "a/b c", "~"})
      assertThrows(IllegalArgumentException.class, () -> VirtualFiles.path(CWD, bad), bad);
    VirtualFiles.path(CWD, "x".repeat(256));
    VirtualFiles.path(CWD, "dot.name-with_chars.9");
  }

  @Test
  void writesAreLimitedToHomeAndEtcAndNeedAnExistingParent() {
    var f = new VirtualFiles();
    f.write("/home/crew/a", "x");
    f.write("/home/top", "x");
    f.write("/etc/conf", "x");
    for (String path : new String[] {"/proc/x", "/dev/ship/x", "/tmp/x", "/x", "/home", "/etc"})
      assertThrows(IllegalArgumentException.class, () -> f.write(path, "x"), path);
    assertThrows(IllegalArgumentException.class, () -> f.write("/home/crew/missing/a", "x"));
    assertThrows(
        IllegalArgumentException.class,
        () -> f.write("/home/crew", "x"),
        "A directory is not a file");
  }

  @Test
  void fileSizeBoundaryIsExact() {
    var f = new VirtualFiles();
    f.write("/home/crew/full", "x".repeat(VirtualFiles.MAX_FILE));
    assertThrows(
        IllegalArgumentException.class,
        () -> f.write("/home/crew/over", "x".repeat(VirtualFiles.MAX_FILE + 1)));
    assertThrows(IllegalArgumentException.class, () -> f.read("/home/crew/over"));
  }

  @Test
  void revisionChangesOnlyWhenContentActuallyChanges() {
    var f = new VirtualFiles();
    long start = f.revision();
    f.write("/home/crew/a", "one");
    long afterWrite = f.revision();
    assertTrue(afterWrite > start);
    f.write("/home/crew/a", "one");
    assertEquals(afterWrite, f.revision());
    f.write("/home/crew/a", "two");
    assertTrue(f.revision() > afterWrite);
    long afterEdit = f.revision();
    f.mkdir("/home/crew/d");
    assertTrue(f.revision() > afterEdit);
    long afterMkdir = f.revision();
    f.mkdir("/home/crew/d");
    assertEquals(afterMkdir, f.revision(), "Re-creating a directory is a no-op");
  }

  @Test
  void mkdirRequiresAParentAndRefusesFilesAndTheDirectoryLimit() {
    var f = new VirtualFiles();
    f.write("/home/crew/file", "x");
    assertThrows(IllegalArgumentException.class, () -> f.mkdir("/home/crew/file"));
    assertThrows(IllegalArgumentException.class, () -> f.mkdir("/home/crew/a/b"));
    f.mkdir("/home/crew/a");
    f.mkdir("/home/crew/a/b");
    int created = 0;
    try {
      for (int i = 0; i < 100; i++, created++) f.mkdir("/home/crew/d" + i);
    } catch (IllegalArgumentException limit) {
      assertTrue(limit.getMessage().contains("limit"));
    }
    assertTrue(created > 0 && created < 100);
    assertEquals(64, f.directories().size());
  }

  @Test
  void removeProtectsSystemAndNonEmptyDirectories() {
    var f = new VirtualFiles();
    f.mkdir("/home/crew/empty");
    f.mkdir("/home/crew/full");
    f.write("/home/crew/full/a", "x");
    f.remove("/home/crew/empty");
    assertFalse(f.directory("/home/crew/empty"));
    assertThrows(IllegalArgumentException.class, () -> f.remove("/home/crew/full"));
    for (String protectedPath : new String[] {"/", "/home", "/etc", "/dev", "/dev/ship", "/proc"})
      assertThrows(IllegalArgumentException.class, () -> f.remove(protectedPath), protectedPath);
    assertThrows(IllegalArgumentException.class, () -> f.remove("/home/crew/nothing"));
    f.remove("/home/crew/full/a");
    f.remove("/home/crew/full");
    assertFalse(f.directory("/home/crew/full"));
  }

  @Test
  void removingAFileDoesNotRemoveSiblingsWithTheSamePrefix() {
    var f = new VirtualFiles();
    f.write("/home/crew/ab", "1");
    f.write("/home/crew/abc", "2");
    f.remove("/home/crew/ab");
    assertEquals("2", f.read("/home/crew/abc"));
  }

  @Test
  void listShowsSortedImmediateChildrenWithDirectoriesMarked() {
    var f = new VirtualFiles();
    f.mkdir("/home/crew/sub");
    f.mkdir("/home/crew/sub/deeper");
    f.write("/home/crew/b", "x");
    f.write("/home/crew/a", "x");
    f.write("/home/crew/sub/inner", "x");
    assertEquals(List.of("a", "b", "sub/"), f.list("/home/crew"));
    assertEquals(List.of("deeper/", "inner"), f.list("/home/crew/sub"));
    assertTrue(f.list("/").contains("home/"));
    assertEquals(List.of("/home/crew/a"), f.list("/home/crew/a"), "Listing a file names the file");
  }

  @Test
  void replaceWithCopiesTheOtherDiskAndClearRestoresDefaults() {
    var source = new VirtualFiles();
    source.mkdir("/home/crew/box");
    source.write("/home/crew/box/a", "payload");
    var target = new VirtualFiles();
    target.write("/home/crew/old", "gone");
    long before = target.revision();
    target.replaceWith(source);
    assertTrue(target.revision() > before);
    assertEquals("payload", target.read("/home/crew/box/a"));
    assertThrows(IllegalArgumentException.class, () -> target.read("/home/crew/old"));
    source.write("/home/crew/later", "x");
    assertThrows(
        IllegalArgumentException.class,
        () -> target.read("/home/crew/later"),
        "replaceWith copies instead of aliasing");
    target.clear();
    assertTrue(target.files().isEmpty());
    assertEquals(7, target.directories().size());
  }

  @Test
  void snapshotsAreDefensiveCopies() {
    var f = new VirtualFiles();
    f.write("/home/crew/a", "x");
    assertThrows(UnsupportedOperationException.class, () -> f.files().put("/home/crew/b", "y"));
    assertThrows(UnsupportedOperationException.class, () -> f.directories().add("/x"));
  }

  @Test
  void theDefaultWorkingDirectoryCannotBeRemoved() {
    var f = new VirtualFiles();
    for (String file : f.files().keySet()) if (file.startsWith("/home/crew/")) f.remove(file);
    assertThrows(IllegalArgumentException.class, () -> f.remove("/home/crew"));
  }

  @Test
  void listingAMissingPathIsAnError() {
    assertThrows(IllegalArgumentException.class, () -> new VirtualFiles().list("/home/crew/nope"));
  }

  @Test
  void mkdirHonoursTheSameWritableRootsAsWrite() {
    assertThrows(IllegalArgumentException.class, () -> new VirtualFiles().mkdir("/proc/x"));
  }
}

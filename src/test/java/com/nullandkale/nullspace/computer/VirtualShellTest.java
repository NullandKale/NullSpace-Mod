package com.nullandkale.nullspace.computer;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.util.*;
import org.junit.jupiter.api.*;

/** Shell commands, process table and disk-image validation of {@link VirtualComputer}. */
class VirtualShellTest {
  private static VirtualComputer machine() {
    return new VirtualComputer(
        (name, args) -> {
          throw new AssertionError("Unexpected ship action: " + name);
        });
  }

  private static String sh(VirtualComputer c, String command) {
    return c.shell(command, c.variables);
  }

  private static IllegalArgumentException fails(VirtualComputer c, String command) {
    return assertThrows(IllegalArgumentException.class, () -> sh(c, command), command);
  }

  // --- Known defects -------------------------------------------------------------------------

  @Test
  void movingAFileOntoItselfKeepsIt() {
    var c = machine();
    c.files.write("/home/crew/a", "precious");
    try {
      sh(c, "mv a a");
    } catch (IllegalArgumentException refused) {
      // Refusing is also acceptable.
    }
    assertEquals("precious", c.files.read("/home/crew/a"));
  }

  @Test
  void movingThroughAnEquivalentPathKeepsTheFileAndDoesNotChangeTheDisk() {
    var c = machine();
    c.files.write("/home/crew/a", "precious");
    long revision = c.files.revision();
    for (String target : List.of("./a", "/home/crew/a", "../crew/a")) {
      sh(c, "mv a " + target);
      assertEquals("precious", c.files.read("/home/crew/a"));
      assertEquals(revision, c.files.revision());
    }
    fails(c, "mv missing missing");
  }

  @Test
  void invalidImageFieldsNeverChangeTheRunningComputer() {
    var c = machine();
    c.files.write("/home/crew/keep", "safe");
    var original = image(c);
    for (String field : List.of("version", "clock", "files", "cwd")) {
      for (JsonElement bad :
          List.of(JsonNull.INSTANCE, new JsonArray(), new JsonObject(), new JsonPrimitive(true))) {
        if (field.equals("files") && bad.isJsonObject()) continue; // An empty file map is valid.
        var corrupted = original.deepCopy();
        corrupted.add(field, bad);
        var error =
            assertThrows(
                IllegalArgumentException.class,
                () -> c.restore(corrupted.toString()),
                field + "=" + bad);
        assertNotNull(error.getMessage());
        assertEquals(original, image(c), "Failed restore must be atomic: " + field);
      }
    }
    for (String value : List.of("1.5", "9223372036854775808")) {
      var corrupted = original.deepCopy();
      corrupted.add("clock", JsonParser.parseString(value));
      assertThrows(IllegalArgumentException.class, () -> c.restore(corrupted.toString()));
      assertEquals(original, image(c));
    }
  }

  @Test
  void headWithAnUnsupportedFlagExplainsUsage() {
    var c = machine();
    var error = fails(c, "echo a | head -n 2");
    assertFalse(error.getMessage().contains("For input string"), error.getMessage());
  }

  @Test
  void imagesMissingRequiredFieldsAreRejectedAsInvalid() {
    var c = machine();
    for (String image : new String[] {"{}", "{\"version\":1}", "{\"version\":1,\"clock\":0}"}) {
      var error = assertThrows(RuntimeException.class, () -> c.restore(image), image);
      assertInstanceOf(IllegalArgumentException.class, error, image);
      assertNotNull(error.getMessage(), image);
    }
  }

  // --- File commands -------------------------------------------------------------------------

  @Test
  void copyKeepsTheSourceAndOverwritesTheTarget() {
    var c = machine();
    c.files.write("/home/crew/a", "one");
    c.files.write("/home/crew/b", "two");
    sh(c, "cp a b");
    assertEquals("one", c.files.read("/home/crew/b"));
    assertEquals("one", c.files.read("/home/crew/a"));
    fails(c, "cp missing x");
    fails(c, "cp a");
    fails(c, "cp a b c");
  }

  @Test
  void moveRelocatesAndRemovesTheSource() {
    var c = machine();
    c.files.write("/home/crew/a", "one");
    sh(c, "mv a moved");
    assertEquals("one", c.files.read("/home/crew/moved"));
    assertThrows(IllegalArgumentException.class, () -> c.files.read("/home/crew/a"));
  }

  @Test
  void directoriesCanBeCreatedEnteredAndRemoved() {
    var c = machine();
    sh(c, "mkdir work");
    sh(c, "cd work");
    assertEquals("/home/crew/work", sh(c, "pwd"));
    sh(c, "echo hi > note");
    assertEquals("note", sh(c, "ls"));
    sh(c, "cd ..");
    fails(c, "rm work");
    sh(c, "rm work/note");
    sh(c, "rm work");
    fails(c, "cd work");
    fails(c, "cd air.py");
  }

  @Test
  void catReadsFilesAndPassesThroughStandardInput() {
    var c = machine();
    c.files.write("/home/crew/a", "line1\nline2");
    assertEquals("line1\nline2", sh(c, "cat a"));
    assertEquals("hello", sh(c, "echo hello | cat"));
    fails(c, "cat nothing");
    fails(c, "cat a b");
  }

  @Test
  void redirectionWritesAppendsAndValidatesItsShape() {
    var c = machine();
    sh(c, "echo one > out");
    assertEquals("one\n", c.files.read("/home/crew/out"));
    sh(c, "echo two >> out");
    assertEquals("one\ntwo\n", c.files.read("/home/crew/out"));
    sh(c, "echo three > out");
    assertEquals("three\n", c.files.read("/home/crew/out"), "> truncates");
    fails(c, "echo a > x > y");
    fails(c, "echo a > x y");
    fails(c, "echo a >");
    fails(c, "echo a > /proc/x");
    fails(c, "echo a > ../../../etc/../proc/x");
  }

  @Test
  void pipelinesFilterAndCount() {
    var c = machine();
    c.files.write("/home/crew/log", "alpha\nbeta\nalphabet\ngamma");
    assertEquals("alpha\nalphabet", sh(c, "cat log | grep alpha"));
    assertEquals("2", sh(c, "cat log | grep alpha | wc"));
    assertEquals("alpha\nbeta", sh(c, "cat log | head 2"));
    assertEquals("", sh(c, "cat log | head 0"));
    assertEquals("", sh(c, "cat log | head -5"), "Negative counts clamp to zero");
    assertEquals(4, sh(c, "cat log | head 99").lines().count());
    fails(c, "echo a |");
    fails(c, "| echo a");
    fails(c, "echo a || echo b");
    fails(c, "cat log | grep");
  }

  @Test
  void headDefaultsToTenLines() {
    var c = machine();
    c.files.write("/home/crew/many", String.join("\n", Collections.nCopies(25, "row")));
    assertEquals(10, sh(c, "cat many | head").lines().count());
  }

  @Test
  void quotingProtectsWhitespaceAndExpansionRules() {
    var c = machine();
    sh(c, "name=crew");
    assertEquals("a  b", sh(c, "echo 'a  b'"));
    assertEquals("crew", sh(c, "echo $name"));
    assertEquals("crew!", sh(c, "echo ${name}!"));
    assertEquals("$name", sh(c, "echo '$name'"));
    assertEquals("crew", sh(c, "echo \"$name\""));
    assertEquals("a b", sh(c, "echo a\\ b"));
    fails(c, "echo 'oops");
  }

  @Test
  void shellVariablesStoreStringsAndAreCapped() {
    var c = machine();
    sh(c, "x=5");
    assertEquals("5", c.variables.get("x"));
    sh(c, "x=6");
    assertEquals("6", c.variables.get("x"));
    for (int i = c.variables.size(); i < 128; i++) sh(c, "v" + i + "=1");
    assertEquals(128, c.variables.size());
    assertTrue(fails(c, "overflow=1").getMessage().contains("limit"));
    sh(c, "x=7");
    assertEquals("7", c.variables.get("x"), "Existing names stay writable at the cap");
  }

  @Test
  void unknownAndMalformedCommandsAreGuestErrors() {
    var c = machine();
    assertTrue(fails(c, "rmdir x").getMessage().contains("Unknown virtual command"));
    fails(c, "python -c 'x'");
    fails(c, "curl http://x");
    fails(c, "cd");
    fails(c, "mkdir");
    assertEquals("", sh(c, ""));
    assertEquals("", sh(c, "   "));
  }

  @Test
  void expandedCommandsAreBoundedBeforeExecution() {
    var c = machine();
    c.variables.put("big", "a".repeat(8000));
    assertTrue(fails(c, "echo $big $big $big $big $big").getMessage().contains("limit"));
  }

  @Test
  void helpAndShipUsageNeedNoDevices() {
    var c = machine();
    assertTrue(sh(c, "help").contains("pwd"));
    assertTrue(sh(c, "ship").contains("status"));
  }

  @Test
  void shipCommandsForwardTheirWordsToTheDevice() {
    var seen = new ArrayList<String>();
    var c =
        new VirtualComputer(
            (name, args) -> {
              seen.add(name + args);
              return 42.0;
            });
    assertEquals("42", c.shell("ship power air 2", c.variables));
    assertEquals(List.of("power[air, 2]"), seen);
  }

  // --- Processes -----------------------------------------------------------------------------

  @Test
  void scriptsSelectTheirLanguageFromTheCommandAndExtension() {
    var c = machine();
    c.files.write("/home/crew/p.py", "x = 1\n");
    c.files.write("/home/crew/s.sh", "x=1\n");
    assertEquals("[1] detached", sh(c, "run --detach p.py"));
    assertEquals(ScriptLanguage.Syntax.PYTHON, c.processes.get(1).syntax());
    sh(c, "run --detach s.sh");
    assertEquals(ScriptLanguage.Syntax.BASH, c.processes.get(2).syntax());
    sh(c, "python --detach p.py");
    assertEquals(ScriptLanguage.Syntax.PYTHON, c.processes.get(3).syntax());
    sh(c, "bash --detach s.sh");
    assertEquals(ScriptLanguage.Syntax.BASH, c.processes.get(4).syntax());
  }

  @Test
  void atMostEightProcessesMayExist() {
    var c = machine();
    c.files.write("/home/crew/loop.py", "while 1:\n    x = 1\n");
    for (int i = 0; i < 8; i++) sh(c, "run --detach loop.py");
    assertTrue(fails(c, "run --detach loop.py").getMessage().contains("eight"));
    sh(c, "kill 3");
    c.tick(true, true);
    assertEquals(7, c.processes.size());
    sh(c, "run --detach loop.py");
  }

  @Test
  void aScriptThatDoesNotCompileCreatesNoProcess() {
    var c = machine();
    c.files.write("/home/crew/bad.py", "if x\n    y = 1\n");
    fails(c, "python bad.py");
    assertTrue(c.processes.isEmpty());
    fails(c, "python missing.py");
    fails(c, "python");
    fails(c, "python a b");
  }

  @Test
  void processIdsAreNeverReused() {
    var c = machine();
    c.files.write("/home/crew/once.py", "x = 1\n");
    sh(c, "run --detach once.py");
    c.tick(true, true);
    c.tick(true, true);
    assertTrue(c.processes.isEmpty());
    sh(c, "run --detach once.py");
    assertTrue(c.processes.containsKey(2));
  }

  @Test
  void psListsStateAndKillStopsOnlyTheNamedProcess() {
    var c = machine();
    assertEquals("No running processes", sh(c, "ps"));
    c.files.write("/home/crew/loop.py", "while 1:\n    x = 1\n");
    sh(c, "run --detach loop.py");
    sh(c, "run --detach loop.py");
    String listing = sh(c, "ps");
    assertTrue(listing.contains("1 PYTHON DETACHED"), listing);
    assertTrue(listing.contains("2 PYTHON DETACHED"), listing);
    assertEquals("Stopped 1", sh(c, "kill 1"));
    c.tick(true, true);
    assertEquals(Set.of(2), c.processes.keySet());
    fails(c, "kill 99");
    fails(c, "kill");
    fails(c, "kill one");
  }

  @Test
  void aFailingScriptReportsOnTheConsoleAndLeavesTheTable() {
    var c = machine();
    c.files.write("/home/crew/boom.py", "x = 1 / 0\n");
    sh(c, "run --detach boom.py");
    c.tick(true, true);
    assertTrue(c.processes.isEmpty());
    assertTrue(c.display.snapshot().contains("Division by zero"));
  }

  @Test
  void consoleInputRunsCommandsAndRejectsHugeChunks() {
    var c = machine();
    c.input("echo visible\r");
    assertTrue(c.display.snapshot().contains("visible"));
    assertThrows(IllegalArgumentException.class, () -> c.input("x".repeat(2049)));
    c.input("x".repeat(2048));
    c.input("\u0003");
    c.input("rmdir nothing\r");
    assertTrue(c.display.snapshot().contains("Unknown virtual command"));
  }

  @Test
  void controlCStopsTheForegroundJobAndReturnsToThePrompt() {
    var c = machine();
    c.files.write("/home/crew/loop.py", "while 1:\n    x = 1\n");
    c.input("python loop.py\r");
    assertEquals(1, c.processes.size());
    c.input("\u0003");
    c.tick(true, true);
    assertTrue(c.processes.isEmpty());
    c.input("echo back\r");
    assertTrue(c.display.snapshot().contains("back"));
  }

  @Test
  void typingWhileAForegroundJobRunsIsDiscarded() {
    var c = machine();
    c.files.write("/home/crew/loop.py", "while 1:\n    x = 1\n");
    c.input("python loop.py\r");
    c.input("echo ignored\r");
    assertFalse(c.display.snapshot().contains("ignored"));
  }

  // --- Disk images ---------------------------------------------------------------------------

  private static JsonObject image(VirtualComputer c) {
    return JsonParser.parseString(c.save()).getAsJsonObject();
  }

  @Test
  void imagesRoundTripFilesDirectoriesAndWorkingDirectory() {
    var c = machine();
    sh(c, "mkdir work");
    sh(c, "cd work");
    sh(c, "echo kept > note");
    var restored = machine();
    restored.restore(c.save());
    assertEquals("kept\n", restored.files.read("/home/crew/work/note"));
    assertEquals("/home/crew/work", sh(restored, "pwd"));
  }

  @Test
  void restoreRejectsStructurallyInvalidImagesWithoutTouchingTheLiveDisk() {
    var c = machine();
    c.files.write("/home/crew/keep", "safe");
    var good = image(c);

    var future = good.deepCopy();
    future.addProperty("version", 2);
    var wrongAbi = good.deepCopy();
    wrongAbi.addProperty("compilerAbi", 999);
    var outsideWritable = good.deepCopy();
    outsideWritable.getAsJsonObject("files").addProperty("/proc/evil", "x");
    var tooLarge = good.deepCopy();
    tooLarge.getAsJsonObject("files").addProperty("/home/crew/huge", "x".repeat(16385));
    var badSyntax = good.deepCopy();
    var job = new JsonObject();
    job.addProperty("source", "x = 1\n");
    job.addProperty("syntax", "RUBY");
    job.addProperty("pc", 0);
    job.addProperty("wake", 0);
    job.add("variables", new JsonObject());
    var jobs = new JsonArray();
    jobs.add(job);
    badSyntax.add("jobs", jobs);

    for (var bad : List.of(future, wrongAbi, outsideWritable, tooLarge, badSyntax))
      assertThrows(RuntimeException.class, () -> c.restore(bad.toString()), bad.toString());
    assertThrows(RuntimeException.class, () -> c.restore("not json"));
    assertThrows(RuntimeException.class, () -> c.restore("x".repeat(8 * 1024 * 1024 + 1)));
    assertEquals("safe", c.files.read("/home/crew/keep"));
  }

  @Test
  void restoreNormalizesTraversalKeysIntoTheVirtualTree() {
    var c = machine();
    var data = image(c);
    data.getAsJsonObject("files").addProperty("/home/crew/../crew/./moved", "inside");
    var restored = machine();
    restored.restore(data.toString());
    assertEquals("inside", restored.files.read("/home/crew/moved"));
  }

  @Test
  void restoreFallsBackWhenTheSavedWorkingDirectoryNoLongerExists() {
    var c = machine();
    var data = image(c);
    data.addProperty("cwd", "/home/crew/gone");
    var restored = machine();
    restored.restore(data.toString());
    assertEquals("/home/crew", sh(restored, "pwd"));
  }

  @Test
  void savedImagesOmitForegroundJobsAndFinishedJobs() {
    var c = machine();
    c.files.write("/home/crew/loop.py", "while 1:\n    x = 1\n");
    sh(c, "run --detach loop.py");
    sh(c, "run loop.py");
    assertEquals(1, image(c).getAsJsonArray("jobs").size());
    var restored = machine();
    restored.restore(c.save());
    assertEquals(1, restored.processes.size());
    assertTrue(restored.processes.values().iterator().next().job().detached);
  }

  @Test
  void restoredJobsKeepTheirVariablesAndProgramCounter() {
    var c = machine();
    c.files.write("/home/crew/count.py", "n = 0\nwhile n < 1000:\n    n = n + 1\n    sleep(1)\n");
    sh(c, "run --detach count.py");
    for (int i = 0; i < 100; i++) c.tick(true, true, 8);
    var original = c.processes.get(1).job();
    assertTrue(original.pc > 0);
    var restored = machine();
    restored.restore(c.save());
    var job = restored.processes.values().iterator().next().job();
    assertEquals(original.pc, job.pc);
    assertEquals(original.variables.get("n"), job.variables.get("n"));
    assertEquals(original.wake, job.wake);
  }

  @Test
  void restoreRejectsOutOfRangeProgramCountersAndOversizedVariables() {
    var c = machine();
    c.files.write("/home/crew/loop.py", "while 1:\n    x = 1\n");
    sh(c, "run --detach loop.py");
    var good = image(c);
    var negative = good.deepCopy();
    negative.getAsJsonArray("jobs").get(0).getAsJsonObject().addProperty("pc", -1);
    var beyond = good.deepCopy();
    beyond.getAsJsonArray("jobs").get(0).getAsJsonObject().addProperty("pc", 9999);
    var huge = good.deepCopy();
    huge.getAsJsonArray("jobs")
        .get(0)
        .getAsJsonObject()
        .getAsJsonObject("variables")
        .addProperty("v", "x".repeat(9000));
    for (var bad : List.of(negative, beyond, huge))
      assertThrows(RuntimeException.class, () -> machine().restore(bad.toString()), bad.toString());
  }

  @Test
  void identicalStateReusesTheSavedImageInstance() {
    var c = machine();
    String first = c.save();
    assertSame(first, c.save());
    c.files.write("/home/crew/new", "x");
    assertNotSame(first, c.save());
  }
}

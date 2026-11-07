package com.nullandkale.nullspace.model.computer;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class ComputerTest {
  private static final ScriptLanguage.Host HOST =
      new ScriptLanguage.Host() {
        public Object call(String n, List<Object> a) {
          throw new AssertionError("Unexpected native call: " + n);
        }

        public String shell(String c, Map<String, Object> v) {
          return "";
        }
      };

  @Test
  void largeValidDisksAndDetachedProgramsRestoreTogether() {
    var c = new VirtualComputer((n, a) -> "ok");
    for (String file : c.files.files().keySet()) c.files.remove(file);
    String body = "\nwhile True:\n    sleep(1)\n";
    String source = "#" + "x".repeat(16384 - 1 - body.length()) + body;
    c.files.write("/home/crew/large.py", source);
    for (int i = 0; i < 7; i++) c.files.write("/home/crew/data" + i, "y".repeat(16384));
    for (int i = 0; i < 8; i++) c.run("/home/crew/large.py", ScriptLanguage.Syntax.PYTHON, true);
    String image = c.save();
    assertTrue(image.length() > 262144);
    var restored = new VirtualComputer((n, a) -> "ok");
    restored.restore(image);
    assertEquals(8, restored.processes.size());
    assertEquals(source, restored.files.read("/home/crew/large.py"));
    assertEquals(16384, restored.files.read("/home/crew/data6").length());
  }

  @Test
  void pythonLoopsBranchesAndArithmetic() {
    var j =
        new ScriptLanguage.Job(
            ScriptLanguage.compile(
                "x = 0\n"
                    + "for i in range(6):\n"
                    + "    if i < 3:\n"
                    + "        x = x + i\n"
                    + "    else:\n"
                    + "        x = x + 10\n"
                    + "result = x * 2\n",
                ScriptLanguage.Syntax.PYTHON));
    j.step(HOST, 0, 256);
    assertTrue(j.done, j.failure);
    assertEquals("", j.failure);
    assertEquals(66.0, j.variables.get("result"));
  }

  @Test
  void runtimeValueNestingCannotOverflowTheHostStack() {
    var c = new VirtualComputer((n, a) -> "ok");
    c.files.write("/home/crew/deep.py", "x = []\nwhile True:\n    x = [x]\n");
    int id = c.run("/home/crew/deep.py", ScriptLanguage.Syntax.PYTHON, true);
    var job = c.processes.get(id).job();
    for (int i = 0; i < 10 && !job.done; i++) c.tick(false, true);
    assertTrue(job.done);
    assertTrue(job.failure.contains("nesting"));
  }

  @Test
  void concatenationAndExpansionHaveAggregateMemoryLimits() {
    var c = new VirtualComputer((n, a) -> "ok");
    assertThrows(
        IllegalArgumentException.class,
        () -> ScriptLanguage.evaluate("x+x+x", Map.of("x", "a".repeat(4000)), c));
    assertThrows(
        IllegalArgumentException.class,
        () -> c.shell("echo $x $x $x $x $x", Map.of("x", "a".repeat(8192))));
  }

  @Test
  void allEightBusyJobsReceiveInstructions() {
    var c = new VirtualComputer((n, a) -> "ok");
    c.files.write("/home/crew/busy.py", "x = 0\nwhile True:\n    x = x + 1\n");
    for (int i = 0; i < 8; i++) c.run("/home/crew/busy.py", ScriptLanguage.Syntax.PYTHON, true);
    for (int i = 0; i < 5; i++) c.tick(false, true);
    assertEquals(8, c.processes.size());
    for (var p : c.processes.values())
      assertTrue(ScriptLanguage.number(p.job().variables.get("x")) > 0);
  }

  @Test
  void batchedKeyboardInputKeepsTheFinalConsoleOutput() {
    var c = new VirtualComputer((n, a) -> "ok");
    c.input("echo " + "a".repeat(300) + "\r");
    assertTrue(
        java.util.stream.IntStream.range(0, 25)
            .anyMatch(row -> c.display.row(row).startsWith("aaaaaaaa")));
  }

  @Test
  void bashCommandsWriteTheirOutputToTheConsole() {
    var c = new VirtualComputer((n, a) -> "native");
    c.files.write("/home/crew/out.sh", "echo script_output");
    c.run("/home/crew/out.sh", ScriptLanguage.Syntax.BASH, false);
    c.tick(true, true);
    assertTrue(
        java.util.stream.IntStream.range(0, 25)
            .anyMatch(row -> c.display.row(row).contains("script_output")));
  }

  @Test
  void bashLoopsShareTheSameExecutionModel() {
    var j =
        new ScriptLanguage.Job(
            ScriptLanguage.compile(
                "i=0\nsum=0\nwhile [ \"$i\" -lt 6 ]; do\nsum=$((sum+i))\ni=$((i+1))\ndone\n",
                ScriptLanguage.Syntax.BASH));
    j.step(HOST, 0, 256);
    assertTrue(j.done, j.failure);
    assertEquals(15.0, j.variables.get("sum"));
  }

  @Test
  void infiniteLoopsYieldAndSleepResumes() {
    var j =
        new ScriptLanguage.Job(
            ScriptLanguage.compile("while True:\n    sleep(1)\n", ScriptLanguage.Syntax.PYTHON));
    j.step(HOST, 0, 256);
    assertFalse(j.done);
    assertEquals(20, j.wake);
    int pc = j.pc;
    assertEquals(0, j.step(HOST, 19, 256));
    assertEquals(pc, j.pc);
    j.step(HOST, 20, 256);
    assertEquals(40, j.wake);
    var busy =
        new ScriptLanguage.Job(
            ScriptLanguage.compile("while True:\n    x = 1\n", ScriptLanguage.Syntax.PYTHON));
    assertEquals(64, busy.step(HOST, 0, 64));
    assertFalse(busy.done);
  }

  @Test
  void conditionsShortCircuitNativeSideEffects() {
    assertEquals(false, ScriptLanguage.evaluate("False and ship.fire()", Map.of(), HOST));
    assertEquals(true, ScriptLanguage.evaluate("True or ship.fire()", Map.of(), HOST));
  }

  @Test
  void nativeUnavailableRetriesTheSameInstruction() {
    var j =
        new ScriptLanguage.Job(
            ScriptLanguage.compile("x = ship.status()", ScriptLanguage.Syntax.PYTHON));
    var available = new boolean[] {false};
    var h =
        new ScriptLanguage.Host() {
          public Object call(String n, List<Object> a) {
            if (!available[0]) throw new ScriptLanguage.Unavailable("unloaded");
            return 7.0;
          }

          public String shell(String c, Map<String, Object> v) {
            return "";
          }
        };
    j.step(h, 0, 10);
    assertEquals(0, j.pc);
    assertFalse(j.done);
    assertEquals("unloaded", j.waiting);
    available[0] = true;
    j.step(h, 20, 10);
    assertTrue(j.done);
    assertEquals(7.0, j.variables.get("x"));
  }

  @Test
  void virtualPathsCannotReachTheHost() {
    var f = new VirtualFiles();
    assertEquals("/etc/x", VirtualFiles.path("/home/crew", "../../../../etc/x"));
    assertThrows(IllegalArgumentException.class, () -> VirtualFiles.path("/", "C:\\Windows\\file"));
    assertThrows(IllegalArgumentException.class, () -> f.write("/dev/file", "x"));
    assertThrows(IllegalArgumentException.class, () -> f.write("/home/crew/x", "x".repeat(16385)));
  }

  @Test
  void shellFilesPipesAndQuotedOperators() {
    var c = new VirtualComputer((n, a) -> "native");
    assertEquals("", c.shell("echo 'hello\nworld' > greeting", Map.of()));
    assertEquals("hello", c.shell("cat greeting | grep hello", Map.of()));
    assertEquals("|", c.shell("echo '|'", Map.of()));
    assertEquals("hello | ship fire", c.shell("echo $x", Map.of("x", "hello | ship fire")));
    assertEquals("2", c.shell("cat greeting | wc", Map.of()));
  }

  @Test
  void noHostCommandsOrPythonImports() {
    var c =
        new VirtualComputer(
            (n, a) -> {
              throw new IllegalArgumentException("Unknown capability");
            });
    assertThrows(IllegalArgumentException.class, () -> c.shell("cmd /c anything", Map.of()));
    assertThrows(IllegalArgumentException.class, () -> c.shell("curl example.com", Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> ScriptLanguage.evaluate("__import__('os')", Map.of(), c));
    assertThrows(
        IllegalArgumentException.class,
        () -> ScriptLanguage.evaluate("open('/etc/passwd')", Map.of(), c));
  }

  @Test
  void detachedJobsPersistButForegroundJobsDoNot() {
    var c = new VirtualComputer((n, a) -> "ok");
    c.files.write("/home/crew/test.py", "i = 0\nwhile True:\n    i = i + 1\n    sleep(1)\n");
    c.run("/home/crew/test.py", ScriptLanguage.Syntax.PYTHON, true);
    c.run("/home/crew/test.py", ScriptLanguage.Syntax.PYTHON, false);
    c.tick(true, true);
    String image = c.save();
    var restored = new VirtualComputer((n, a) -> "ok");
    restored.restore(image);
    assertEquals(1, restored.processes.size());
    var job = restored.processes.values().iterator().next().job();
    assertEquals(1.0, job.variables.get("i"));
    for (int i = 0; i < 25; i++) restored.tick(false, true);
    assertEquals(2.0, job.variables.get("i"));
  }

  @Test
  void unpoweredAndUnattendedForegroundJobsPause() {
    var c = new VirtualComputer((n, a) -> "ok");
    c.files.write("/home/crew/test.py", "x = 7");
    int id = c.run("/home/crew/test.py", ScriptLanguage.Syntax.PYTHON, false);
    c.tick(false, true);
    assertEquals(0, c.processes.get(id).job().pc);
    c.tick(true, false);
    assertEquals(0, c.processes.get(id).job().pc);
    c.tick(true, true);
    assertFalse(c.processes.containsKey(id));
  }

  @Test
  void pythonRequiresBodiesAndTopLevelIndentationIsRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ScriptLanguage.compile("if True:\nx = 1", ScriptLanguage.Syntax.PYTHON));
    assertThrows(
        IllegalArgumentException.class,
        () -> ScriptLanguage.compile("if True:", ScriptLanguage.Syntax.PYTHON));
    assertThrows(
        IllegalArgumentException.class,
        () -> ScriptLanguage.compile("    x = 1", ScriptLanguage.Syntax.PYTHON));
  }

  @Test
  void singleQuotesPreserveDollarSigns() {
    var c = new VirtualComputer((n, a) -> "ok");
    assertEquals("$name", c.shell("echo '$name'", Map.of("name", "expanded")));
    assertEquals("expanded", c.shell("echo \"$name\"", Map.of("name", "expanded")));
  }

  @Test
  void malformedCodeGivesLineNumbers() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ScriptLanguage.compile("if True\n    x = 1", ScriptLanguage.Syntax.PYTHON));
    var j =
        new ScriptLanguage.Job(ScriptLanguage.compile("x = 1 / 0", ScriptLanguage.Syntax.PYTHON));
    j.step(HOST, 0, 8);
    assertTrue(j.failure.startsWith("Line 1: Division by zero"));
  }
}

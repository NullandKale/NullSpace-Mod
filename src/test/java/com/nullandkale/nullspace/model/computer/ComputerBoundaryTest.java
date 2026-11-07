package com.nullandkale.nullspace.model.computer;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.util.*;
import org.junit.jupiter.api.*;

class ComputerBoundaryTest {
  @Test
  void aSingleInstructionBudgetCannotStarveAnyOfEightJobs() {
    var c = new VirtualComputer((n, a) -> 0);
    c.files.write("/home/crew/busy.py", "x = 0\nwhile True:\n    x = x + 1\n");
    for (int i = 0; i < 8; i++) c.run("/home/crew/busy.py", ScriptLanguage.Syntax.PYTHON, true);
    for (int i = 0; i < 64; i++) assertTrue(c.tick(false, true, 1) <= 1);
    for (var p : c.processes.values())
      assertTrue(ScriptLanguage.number(p.job().variables.get("x")) > 0);
  }

  @Test
  void sleepingAndUnattendedJobsCannotBlockReadyJobs() {
    var c = new VirtualComputer((n, a) -> 0);
    c.files.write("/home/crew/sleep.py", "sleep(50)\n");
    c.files.write("/home/crew/ready.py", "x = 42\nwhile True:\n    x = x + 1\n");
    for (int i = 0; i < 7; i++) c.run("/home/crew/sleep.py", ScriptLanguage.Syntax.PYTHON, true);
    int id = c.run("/home/crew/ready.py", ScriptLanguage.Syntax.PYTHON, true);
    for (int i = 0; i < 64; i++) c.tick(false, true, 1);
    assertTrue(ScriptLanguage.number(c.processes.get(id).job().variables.get("x")) > 42);
  }

  @Test
  void compiledExpressionsReuseStructureButReadCurrentVariables() {
    var p = ScriptLanguage.compile("x = y + 1", ScriptLanguage.Syntax.PYTHON);
    var expr = p.expressions().get(0);
    var c = new VirtualComputer((n, a) -> 0);
    assertEquals(3.0, expr.evaluate(Map.of("y", 2), c));
    assertEquals(9.0, expr.evaluate(Map.of("y", 8), c));
    assertSame(expr, p.expressions().get(0));
  }

  @Test
  void shortCircuitSkipsUndefinedVariablesDivisionAndNativeCalls() {
    var c =
        new VirtualComputer(
            (n, a) -> {
              throw new AssertionError("Native call");
            });
    assertEquals(
        false, ScriptLanguage.evaluate("False and (missing / 0 > ship.fire())", Map.of(), c));
    assertEquals(true, ScriptLanguage.evaluate("True or (1 / 0 > missing)", Map.of(), c));
  }

  @Test
  void literalNoneCanAppearInGuestLists() {
    var c = new VirtualComputer((n, a) -> 0);
    assertEquals(Arrays.asList(null, 1.0), ScriptLanguage.evaluate("[None, 1]", Map.of(), c));
  }

  @Test
  void incompatibleCompilerCannotResumeTheWrongInstruction() {
    var c = new VirtualComputer((n, a) -> 0);
    var image = JsonParser.parseString(c.save()).getAsJsonObject();
    image.addProperty("compilerAbi", 999);
    assertThrows(IllegalArgumentException.class, () -> c.restore(image.toString()));
  }

  @Test
  void imagesWithoutACompilerAbiAreRejected() {
    var c = new VirtualComputer((n, a) -> 0);
    c.files.write("/home/crew/job.py", "x = 1\nsleep(10)\nx = 2");
    c.run("/home/crew/job.py", ScriptLanguage.Syntax.PYTHON, true);
    c.tick(false, true);
    var image = JsonParser.parseString(c.save()).getAsJsonObject();
    image.remove("compilerAbi");
    var restored = new VirtualComputer((n, a) -> 0);
    assertThrows(IllegalArgumentException.class, () -> restored.restore(image.toString()));
  }

  @Test
  void unchangedDisksReuseTheImageButActualEditsInvalidateIt() {
    var c = new VirtualComputer((n, a) -> 0);
    String first = c.save();
    c.tick(false, true);
    assertSame(first, c.save());
    c.files.write("/home/crew/new", "new");
    String second = c.save();
    assertNotSame(first, second);
    c.files.write("/home/crew/new", "new");
    assertSame(second, c.save());
    c.files.remove("/home/crew/new");
    assertNotSame(second, c.save());
  }
}

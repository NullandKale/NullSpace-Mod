package com.nullandkale.nullspace.model.computer;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.*;

class CompiledExpressionTest {
  private record Call(String name, List<Object> args) {}

  private static final class Recorder implements ScriptLanguage.Host {
    final List<Call> calls = new ArrayList<>();

    public Object call(String name, List<Object> args) {
      calls.add(new Call(name, args));
      return "called";
    }

    public String shell(String command, Map<String, Object> vars) {
      throw new AssertionError("expressions never reach the shell");
    }
  }

  private static Object eval(String source) {
    return eval(source, Map.of());
  }

  private static Object eval(String source, Map<String, Object> vars) {
    return CompiledExpression.compile(source).evaluate(vars, new Recorder());
  }

  private static void rejects(String source) {
    assertThrows(
        IllegalArgumentException.class,
        () -> CompiledExpression.compile(source).evaluate(Map.of(), new Recorder()),
        source);
  }

  @Test
  void arithmeticFollowsPrecedenceAndLeftAssociativity() {
    assertEquals(7.0, eval("1 + 2 * 3"));
    assertEquals(9.0, eval("(1 + 2) * 3"));
    assertEquals(3.0, eval("10 - 4 - 3"));
    assertEquals(2.0, eval("2 * 3 % 4"));
    assertEquals(1.0, eval("8 / 4 / 2"));
    assertEquals(-6.0, eval("-2 * 3"));
    assertEquals(3.0, eval("- -3"));
  }

  @Test
  void floorDivisionAndModuloUseJavaSemantics() {
    assertEquals(3.0, eval("7 // 2"));
    assertEquals(-4.0, eval("-7 // 2"));
    assertEquals(1.0, eval("7 % 3"));
    assertEquals(-1.0, eval("-7 % 3"), "Modulo keeps the dividend's sign, unlike Python");
  }

  @Test
  void comparisonsMixIntegersAndDecimals() {
    assertEquals(true, eval("1 == 1.0"));
    assertEquals(true, eval("1 != 2"));
    assertEquals(true, eval("2 >= 2"));
    assertEquals(false, eval("2 < 2"));
    assertEquals(true, eval("'a' == 'a'"));
    assertEquals(false, eval("'a' == 'b'"));
    assertEquals(false, eval("'1' == 1"), "Strings never equal numbers");
  }

  @Test
  void logicalOperatorsReturnBooleansAndNotBindsToItsOperand() {
    assertEquals(true, eval("1 < 2 and 2 < 3"));
    assertEquals(false, eval("1 > 2 or 3 < 2"));
    assertEquals(true, eval("not 0"));
    assertEquals(false, eval("!1"));
    assertEquals(true, eval("1 or 0 and 0"), "and binds tighter than or");
  }

  @Test
  void literalsCoverBooleansNoneAndStrings() {
    assertEquals(true, eval("True"));
    assertEquals(true, eval("true"));
    assertEquals(false, eval("False"));
    assertNull(eval("None"));
    assertNull(eval("null"));
    assertEquals("it's", eval("\"it's\""));
    assertEquals("say \"hi\"", eval("'say \"hi\"'"));
    assertEquals("a\tb", eval("\"a\\tb\""));
    assertEquals("a\\tb", eval("'a\\tb'"), "Single quotes keep the backslash");
    assertEquals("", eval("''"));
  }

  @Test
  void stringConcatenationCoercesTheOtherOperand() {
    assertEquals("abcd", eval("'ab' + 'cd'"));
    assertEquals("a1", eval("'a' + 1"));
    assertEquals("1a", eval("1 + 'a'"));
    assertEquals("x3.5", eval("'x' + 3.5"));
  }

  @Test
  void listsEvaluateElementsInOrder() {
    assertEquals(List.of(), eval("[]"));
    assertEquals(List.of(1.0, "a", true), eval("[1, 'a', True]"));
    assertEquals(List.of(List.of(1.0), List.of()), eval("[[1], []]"));
    rejects("[1,]");
    rejects("[1 2]");
    rejects("[1");
  }

  @Test
  void variablesReadTheCurrentBindingAndMissingOnesFail() {
    assertEquals(5.0, eval("x + 2", Map.of("x", 3.0)));
    assertEquals(6.0, eval("x * 2", Map.of("x", "3")));
    assertNull(eval("x", new HashMap<>(Collections.singletonMap("x", null))));
    rejects("x");
  }

  @Test
  void callsForwardEvaluatedArgumentsAndDottedNames() {
    var host = new Recorder();
    var result = CompiledExpression.compile("ship.power('air', 1 + 1)").evaluate(Map.of(), host);
    assertEquals("called", result);
    assertEquals(List.of(new Call("ship.power", List.of("air", 2.0))), host.calls);
    CompiledExpression.compile("f()").evaluate(Map.of(), host);
    assertEquals(List.of(), host.calls.getLast().args);
  }

  @Test
  void argumentListsAreImmutableSoHostsCannotCorruptTheProgram() {
    var host =
        new ScriptLanguage.Host() {
          public Object call(String name, List<Object> args) {
            args.add("extra");
            return null;
          }

          public String shell(String c, Map<String, Object> v) {
            return "";
          }
        };
    assertThrows(
        UnsupportedOperationException.class,
        () -> CompiledExpression.compile("f(1)").evaluate(Map.of(), host));
  }

  @Test
  void divisionAndModuloByZeroAreGuestErrors() {
    for (String source : new String[] {"1 / 0", "1 // 0", "1 % 0", "1 / (2 - 2)"})
      assertTrue(
          assertThrows(IllegalArgumentException.class, () -> eval(source))
              .getMessage()
              .contains("Division by zero"),
          source);
  }

  @Test
  void numericOverflowIsAGuestErrorNotInfinity() {
    String huge = "1" + "0".repeat(200);
    assertThrows(IllegalArgumentException.class, () -> eval(huge + " * " + huge));
    assertThrows(IllegalArgumentException.class, () -> eval("9".repeat(400)));
    assertEquals(1e200, eval(huge));
  }

  @Test
  void malformedSourceIsRejectedWithGuestErrors() {
    for (String source :
        new String[] {
          "",
          "1 +",
          "(1",
          "1)",
          "1 2",
          "1 @ 2",
          "'unterminated",
          "f(",
          "f(1,",
          "1 +* 2",
          "a b",
          "1e5",
          "#"
        }) rejects(source);
  }

  @Test
  void nestingAndSizeAreBounded() {
    CompiledExpression.compile("(".repeat(40) + "1" + ")".repeat(40));
    rejects("(".repeat(60) + "1" + ")".repeat(60));
    rejects("- ".repeat(60) + "1");
    rejects("1" + " + 1".repeat(600));
  }

  @Test
  void compilationIsIndependentOfLaterVariableChanges() {
    var program = CompiledExpression.compile("x + 1");
    assertEquals(2.0, program.evaluate(Map.of("x", 1.0), new Recorder()));
    assertEquals(11.0, program.evaluate(Map.of("x", 10.0), new Recorder()));
  }

  @Test
  void andOrDoNotEvaluateTheirRightSideWhenDecided() {
    var host = new Recorder();
    assertEquals(false, CompiledExpression.compile("0 and f()").evaluate(Map.of(), host));
    assertEquals(true, CompiledExpression.compile("1 or f()").evaluate(Map.of(), host));
    assertTrue(host.calls.isEmpty());
    CompiledExpression.compile("1 and f()").evaluate(Map.of(), host);
    assertEquals(1, host.calls.size());
  }

  @Test
  void notAppliesToTheWholeComparison() {
    assertEquals(true, eval("not 1 == 2"));
    assertEquals(false, eval("not 1 < 2"));
    assertEquals(true, eval("not 1 == 2 and 3 == 3"));
    assertEquals(false, eval("not not 1 == 2"));
  }

  @Test
  void longQuotedTokensAndMalformedTailsStayInsideTheGuestErrorBoundary() {
    for (String quote : List.of("'", "\"")) {
      String contents = "a".repeat(8000);
      assertEquals(contents, eval(quote + contents + quote));
      assertThrows(IllegalArgumentException.class, () -> eval(quote + contents));
      assertThrows(IllegalArgumentException.class, () -> eval(quote + contents + quote + " ?"));
    }
    assertThrows(IllegalArgumentException.class, () -> eval(" ".repeat(16000) + "?"));
  }
}

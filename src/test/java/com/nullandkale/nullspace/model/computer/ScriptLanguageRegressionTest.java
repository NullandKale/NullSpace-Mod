package com.nullandkale.nullspace.model.computer;

import java.util.*;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compiler and interpreter behavior that the end-to-end computer tests do not pin down. Previously
 * disabled cases now prevent regressions in nested branches and bounded token parsing.
 */
class ScriptLanguageRegressionTest
{
    private static final ScriptLanguage.Syntax PY = ScriptLanguage.Syntax.PYTHON;
    private static final ScriptLanguage.Syntax SH = ScriptLanguage.Syntax.BASH;

    private static final ScriptLanguage.Host HOST = new ScriptLanguage.Host()
    {
        public Object call(String name, List<Object> args)
        {
            if (name.equals("print"))
                return null;
            throw new AssertionError("Unexpected native call: " + name);
        }

        public String shell(String command, Map<String, Object> vars)
        {
            return "";
        }
    };

    private static ScriptLanguage.Job run(String source, ScriptLanguage.Syntax syntax)
    {
        var job = new ScriptLanguage.Job(ScriptLanguage.compile(source, syntax));
        job.step(HOST, 0, 4096);
        return job;
    }

    private static IllegalArgumentException compileError(String source, ScriptLanguage.Syntax s)
    {
        return assertThrows(IllegalArgumentException.class, () -> ScriptLanguage.compile(source, s));
    }

    // --- Known defects -------------------------------------------------------------------------

    @Test
    void elseAfterANestedIfBelongsToTheOuterIf()
    {
        var job = run("a = 0\n" + "b = 1\n" + "if a:\n" + "    if b:\n" + "        x = 1\n" + "else:\n" + "    x = 2\n", PY);
        assertEquals("", job.failure);
        assertEquals(2.0, job.variables.get("x"));
    }

    @Test
    void elseAfterANestedLoopBelongsToTheOuterIf()
    {
        var job = run("a = 0\n" + "n = 0\n" + "if a:\n" + "    while n < 3:\n" + "        n = n + 1\n" + "else:\n" + "    x = 2\n", PY);
        assertEquals("", job.failure);
        assertEquals(2.0, job.variables.get("x"));
        assertEquals(0.0, job.variables.get("n"));
    }

    @Test
    void innerAndOuterElseBranchesBothResolve()
    {
        var job = run("a = 1\n" + "b = 0\n" + "if a:\n" + "    if b:\n" + "        x = 1\n" + "    else:\n" + "        x = 2\n" + "else:\n" + "    x = 3\n", PY);
        assertEquals("", job.failure);
        assertEquals(2.0, job.variables.get("x"));
    }

    @Test
    void longStringLiteralsNeverThrowAnError()
    {
        // StackOverflowError is not a RuntimeException, so it escapes every guest-error handler.
        for (int length : new int[]{1_000, 4_000, 16_000})
        {
            String source = "x = '" + "a".repeat(length) + "'\n";
            try
            {
                ScriptLanguage.compile(source, PY);
            }
            catch (IllegalArgumentException rejected)
            {
                // A clean guest-visible rejection is acceptable; a host Error is not.
            }
        }
    }

    @Test
    void longStringLiteralsInExpressionsNeverThrowAnError()
    {
        try
        {
            CompiledExpression.compile("'" + "a".repeat(8_000) + "'");
        }
        catch (IllegalArgumentException rejected)
        {
            // acceptable
        }
    }

    // --- Python syntax -------------------------------------------------------------------------

    @Test
    void pythonRangeLoopsHandleZeroNestedAndVariableBounds()
    {
        var job = run("n = 3\n" + "count = 0\n" + "for i in range(n):\n" + "    for j in range(n):\n" + "        count = count + 1\n" + "for k in range(0):\n" + "    count = count + 100\n", PY);
        assertEquals("", job.failure);
        assertTrue(job.done);
        assertEquals(9.0, job.variables.get("count"));
    }

    @Test
    void pythonElseIsRunOnlyWhenTheConditionIsFalse()
    {
        var job = run("t = 1\n" + "f = 0\n" + "if t:\n" + "    a = 1\n" + "else:\n" + "    a = 2\n" + "if f:\n" + "    b = 1\n" + "else:\n" + "    b = 2\n", PY);
        assertEquals(1.0, job.variables.get("a"));
        assertEquals(2.0, job.variables.get("b"));
    }

    @Test
    void pythonWhileLoopsAndDedentsCloseTheirBlocks()
    {
        var job = run("i = 0\n" + "total = 0\n" + "while i < 4:\n" + "    if i % 2 == 0:\n" + "        total = total + i\n" + "    i = i + 1\n" + "done = 1\n", PY);
        assertEquals(2.0, job.variables.get("total"));
        assertEquals(1.0, job.variables.get("done"));
    }

    @Test
    void pythonRejectsTabsStrayIndentationAndUnsupportedControlFlow()
    {
        assertTrue(compileError("if 1:\n\tx = 1\n", PY).getMessage().contains("spaces"));
        assertTrue(compileError("x = 1\n  y = 2\n", PY).getMessage().contains("indentation"));
        assertTrue(compileError("while 1:\n    break\n", PY).getMessage().contains("Line 2"));
        assertTrue(compileError("while 1:\n    continue\n", PY).getMessage().contains("Line 2"));
        assertTrue(compileError("else:\n    x = 1\n", PY).getMessage().contains("else"));
        assertTrue(compileError("if 1\n    x = 1\n", PY).getMessage().contains("':'"));
    }

    @Test
    void pythonRangeAcceptsOnlyASingleBound()
    {
        assertTrue(compileError("for i in range(1, 5):\n    x = i\n", PY).getMessage().contains("Line"));
    }

    @Test
    void compilerBoundsSourceSizeBlockDepthAndProgramLength()
    {
        assertThrows(IllegalArgumentException.class, () -> ScriptLanguage.compile("#".repeat(16385), PY));
        ScriptLanguage.compile("#".repeat(16384), PY);

        var deep = new StringBuilder();
        for (int i = 0; i < 40; i++)
            deep.append("  ".repeat(i)).append("if 1:\n");
        deep.append("  ".repeat(40)).append("x = 1\n");
        assertTrue(compileError(deep.toString(), PY).getMessage().contains("too complex"));

        var long_ = new StringBuilder();
        for (int i = 0; i < 2100; i++)
            long_.append("x = 1\n");
        assertTrue(compileError(long_.toString(), PY).getMessage().contains("too complex"));
    }

    @Test
    void expressionErrorsNameTheirSourceLine()
    {
        var error = compileError("x = 1\ny = 2 +\n", PY);
        assertTrue(error.getMessage().startsWith("Line 2:"), error.getMessage());
    }

    // --- Bash syntax ---------------------------------------------------------------------------

    @Test
    void bashIfElseAndWhileExecute()
    {
        var job = run("n=0\n" + "total=0\n" + "while [ $n -lt 4 ]; do\n" + "  total=$((total + n))\n" + "  n=$((n + 1))\n" + "done\n" + "if [ $total -eq 6 ]; then\n" + "  verdict=yes\n" + "else\n" + "  verdict=no\n" + "fi\n", SH);
        assertEquals("", job.failure);
        assertEquals(6.0, job.variables.get("total"));
        assertEquals("yes", job.variables.get("verdict"));
    }

    @Test
    void bashRejectsMismatchedAndUnclosedBlocks()
    {
        assertTrue(compileError("if 1; then\n  x=1\ndone\n", SH).getMessage().contains("Mismatched"));
        assertTrue(compileError("while 1; do\n  x=1\nfi\n", SH).getMessage().contains("Mismatched"));
        assertTrue(compileError("if 1; then\n  x=1\n", SH).getMessage().contains("Unclosed"));
        assertTrue(compileError("fi\n", SH).getMessage().contains("Mismatched"));
    }

    @Test
    void bashBareWordsBecomeStringsAndVariablesCopy()
    {
        var job = run("name=crew\ncopy=$name\nquoted=\"two words\"\n", SH);
        assertEquals("crew", job.variables.get("name"));
        assertEquals("crew", job.variables.get("copy"));
        assertEquals("two words", job.variables.get("quoted"));
    }

    @Test
    void bashSleepSchedulesAWakeInsteadOfBlocking()
    {
        var job = new ScriptLanguage.Job(ScriptLanguage.compile("sleep 2\nx=1\n", SH));
        job.step(HOST, 10, 100);
        assertEquals(50, job.wake);
        assertFalse(job.variables.containsKey("x"));
        job.step(HOST, 49, 100);
        assertFalse(job.variables.containsKey("x"));
        job.step(HOST, 50, 100);
        assertEquals(1.0, job.variables.get("x"));
    }

    @Test
    void sleepDurationIsClampedToTheSupportedRange()
    {
        var job = new ScriptLanguage.Job(ScriptLanguage.compile("sleep 0\n", SH));
        job.step(HOST, 100, 10);
        assertEquals(101, job.wake, "A zero-second sleep still yields for one tick");
        var long_ = new ScriptLanguage.Job(ScriptLanguage.compile("sleep 99999999\n", SH));
        long_.step(HOST, 0, 10);
        assertEquals(72000, long_.wake);
        var bad = new ScriptLanguage.Job(ScriptLanguage.compile("sleep\n", SH));
        bad.step(HOST, 0, 10);
        assertTrue(bad.failure.contains("sleep <seconds>"));
    }

    // --- Interpreter ---------------------------------------------------------------------------

    @Test
    void runtimeFailuresStopTheJobWithTheirLine()
    {
        var job = run("x = 1\ny = x / 0\nz = 3\n", PY);
        assertTrue(job.done);
        assertTrue(job.failure.startsWith("Line 2:"), job.failure);
        assertTrue(job.failure.contains("Division by zero"));
        assertFalse(job.variables.containsKey("z"));
    }

    @Test
    void unknownVariablesFailAtRuntimeNotCompileTime()
    {
        var job = run("x = missing + 1\n", PY);
        assertTrue(job.failure.contains("Unknown variable: missing"), job.failure);
    }

    @Test
    void unavailableDevicesRetryWithoutAdvancingOrFailing()
    {
        int[] attempts = {0};
        var host = new ScriptLanguage.Host()
        {
            public Object call(String name, List<Object> args)
            {
                if (++attempts[0] < 3)
                    throw new ScriptLanguage.Unavailable("busy", 5);
                return 7.0;
            }

            public String shell(String c, Map<String, Object> v)
            {
                return "";
            }
        };
        var job = new ScriptLanguage.Job(ScriptLanguage.compile("x = dev()\n", PY));
        job.step(host, 0, 10);
        assertEquals("busy", job.waiting);
        assertEquals(5, job.wake);
        assertEquals(0, job.pc);
        job.step(host, 4, 10);
        assertEquals(1, attempts[0], "Not retried before the wake tick");
        job.step(host, 5, 10);
        job.step(host, 10, 10);
        assertTrue(job.done);
        assertEquals(7.0, job.variables.get("x"));
        assertEquals("", job.failure);
    }

    @Test
    void unavailableRetryDelaysAreBounded()
    {
        assertThrows(IllegalArgumentException.class, () -> new ScriptLanguage.Unavailable("x", 0));
        assertThrows(IllegalArgumentException.class, () -> new ScriptLanguage.Unavailable("x", 72001));
        assertEquals(72000, new ScriptLanguage.Unavailable("x", 72000).retryTicks);
    }

    @Test
    void stepNeverExceedsItsBudgetEvenInsideAnInfiniteLoop()
    {
        var job = new ScriptLanguage.Job(ScriptLanguage.compile("while 1:\n    x = 1\n", PY));
        assertEquals(37, job.step(HOST, 0, 37));
        assertFalse(job.done);
        assertEquals(0, job.step(HOST, 0, 0));
    }

    @Test
    void aJobStopsExactlyWhenItsProgramEnds()
    {
        var job = new ScriptLanguage.Job(ScriptLanguage.compile("x = 1\n", PY));
        assertEquals(1, job.step(HOST, 0, 100), "Only real instructions are charged");
        assertTrue(job.done);
        assertEquals(0, job.step(HOST, 1, 100), "A finished job consumes no budget");
    }

    // --- Helpers -------------------------------------------------------------------------------

    @Test
    void textFormatsWholeNumbersWithoutAFraction()
    {
        assertEquals("3", ScriptLanguage.text(3.0));
        assertEquals("3.5", ScriptLanguage.text(3.5));
        assertEquals("-2", ScriptLanguage.text(-2.0));
        assertEquals("", ScriptLanguage.text(null));
        assertEquals("true", ScriptLanguage.text(true));
    }

    @Test
    void truthFollowsTheDocumentedFalsyValues()
    {
        for (Object falsy : new Object[]{null, false, 0.0, 0, ""})
            assertFalse(ScriptLanguage.truth(falsy), String.valueOf(falsy));
        for (Object truthy : new Object[]{true, 1.0, -1, "0", "x"})
            assertTrue(ScriptLanguage.truth(truthy), String.valueOf(truthy));
    }

    @Test
    void numberParsesNumericTextAndRejectsEverythingElse()
    {
        assertEquals(4.5, ScriptLanguage.number("4.5"));
        assertEquals(3.0, ScriptLanguage.number(3));
        assertThrows(IllegalArgumentException.class, () -> ScriptLanguage.number("four"));
        assertThrows(IllegalArgumentException.class, () -> ScriptLanguage.number(null));
    }

    @Test
    void expansionSubstitutesBracedAndBareNamesAndBlanksUnknownOnes()
    {
        var vars = Map.<String, Object>of("a", "1", "bc", 2.0);
        assertEquals("1-2-", ScriptLanguage.expand("$a-${bc}-$missing", vars));
        assertEquals("1x", ScriptLanguage.expand("${a}x", vars));
        assertEquals("$", ScriptLanguage.expand("$", vars));
        assertEquals("$1", ScriptLanguage.expand("$1", vars), "Digits do not start a name");
    }

    @Test
    void expansionHonoursTheLiteralDollarMarkerAndStripsIt()
    {
        var vars = Map.<String, Object>of("a", "1");
        assertEquals("$a", ScriptLanguage.expand("\u0002$a", vars));
    }

    @Test
    void expansionOfTheReplacementTextIsNotRecursive()
    {
        var vars = Map.<String, Object>of("a", "$b", "b", "boom");
        assertEquals("$b", ScriptLanguage.expand("$a", vars));
        assertEquals("a\\b", ScriptLanguage.expand("$c", Map.of("c", "a\\b")));
    }

    @Test
    void wordsSplitsQuotesEscapesAndOperators()
    {
        assertEquals(List.of("a", "b c", "d"), ScriptLanguage.words("a 'b c' \"d\""));
        assertEquals(List.of("a b"), ScriptLanguage.words("a\\ b"));
        assertEquals(List.of("a\nb"), ScriptLanguage.words("\"a\\nb\""));
        assertEquals(List.of("a\\nb"), ScriptLanguage.words("'a\\nb'"), "Single quotes keep backslashes");
        assertEquals(List.of(""), ScriptLanguage.words("''"));
        assertEquals(List.of("a", "\u0001|", "b", "\u0001>", "f"), ScriptLanguage.words("a|b>f"));
        assertEquals(List.of("a", "\u0001>>", "f"), ScriptLanguage.words("a >> f"));
    }

    @Test
    void wordsRejectsUnterminatedInputAndRunawayArgumentCounts()
    {
        assertThrows(IllegalArgumentException.class, () -> ScriptLanguage.words("echo 'x"));
        assertThrows(IllegalArgumentException.class, () -> ScriptLanguage.words("echo \"x"));
        assertThrows(IllegalArgumentException.class, () -> ScriptLanguage.words("echo x\\"));
        assertEquals(256, ScriptLanguage.words("a ".repeat(256)).size());
        assertThrows(IllegalArgumentException.class, () -> ScriptLanguage.words("a ".repeat(257)));
    }

    @Test
    void shellModeMarksSingleQuotedDollarsAsLiteral()
    {
        assertEquals(List.of("\u0002$x"), ScriptLanguage.words("'$x'", true));
        assertEquals(List.of("$x"), ScriptLanguage.words("\"$x\"", true));
    }

    // --- Guest value validation ----------------------------------------------------------------

    private static Object nested(int depth)
    {
        Object value = "leaf";
        for (int i = 0; i < depth; i++)
            value = List.of(value);
        return value;
    }

    @Test
    void guestValuesAreBoundedInDepthWidthAndSize()
    {
        ScriptLanguage.checkValue(nested(31), 1 << 20);
        assertThrows(IllegalArgumentException.class, () -> ScriptLanguage.checkValue(nested(33), 1 << 20));

        ScriptLanguage.checkValue(new ArrayList<>(Collections.nCopies(1024, 1.0)), 1 << 20);
        assertThrows(IllegalArgumentException.class, () -> ScriptLanguage.checkValue(new ArrayList<>(Collections.nCopies(1025, 1.0)), 1 << 20));

        var map = new HashMap<String, Object>();
        for (int i = 0; i < 128; i++)
            map.put("k" + i, 1.0);
        ScriptLanguage.checkValue(map, 1 << 20);
        map.put("overflow", 1.0);
        assertThrows(IllegalArgumentException.class, () -> ScriptLanguage.checkValue(map, 1 << 20));

        assertThrows(IllegalArgumentException.class, () -> ScriptLanguage.checkValue("x".repeat(101), 100));
        ScriptLanguage.checkValue("x".repeat(100), 100);
    }

    @Test
    void guestValuesRejectNonFiniteNumbersForeignObjectsAndNonStringKeys()
    {
        assertThrows(IllegalArgumentException.class, () -> ScriptLanguage.checkValue(Double.NaN, 100));
        assertThrows(IllegalArgumentException.class, () -> ScriptLanguage.checkValue(Double.POSITIVE_INFINITY, 100));
        assertThrows(IllegalArgumentException.class, () -> ScriptLanguage.checkValue(new Object(), 100));
        assertThrows(IllegalArgumentException.class, () -> ScriptLanguage.checkValue(Map.of(1, "x"), 100));
        ScriptLanguage.checkValue(null, 100);
        ScriptLanguage.checkValue(true, 100);
    }
}

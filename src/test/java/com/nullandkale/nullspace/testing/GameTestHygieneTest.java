package com.nullandkale.nullspace.testing;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Keeps the native GameTests from failing at random. The suite runs about ninety tests at once on one server in one world, and each of these patterns passes when a
 * test runs alone and fails now and then in the full run, which costs a whole release check to find, one failure at a time. Each rule scans the sources of
 * {@code src/gametest/java}; a failure here names the test and what to use instead. The rules and the helpers they point to are described in
 * {@code docs/development/test-hygiene.md}.
 *
 * <ul>
 * <li>A registered pad outlives its test, and {@code PadComputers.register} refuses, and says why only in the text it returns, when an earlier test's pad has the
 * name or overlaps the ring: use {@code TestPads.register}, which removes pads left by finished tests and throws on a refusal.</li>
 * <li>A test that launches or lands a ship takes the first free orbit slot, and one that sets the shared failure or write hooks, turns saving off or recovers every
 * journal changes what the tests beside it see: it needs its own environment ({@code @GameTest(environment = "nullspace:name")}), which runs it alone.</li>
 * <li>Entities and chunks load on other threads and a test server never sleeps between ticks, so a fixed number of ticks is a few milliseconds: a test file that
 * launches, lands or recovers ships waits with {@code TestWaits.afterLoaded}, which also waits for the chunks the test holds to have their entities, and holds
 * them with {@code TestWaits.hold} so that it knows which they are.</li>
 * </ul>
 */
class GameTestHygieneTest
{
    private static final Path TESTS = Path.of("src/gametest/java");
    private static final Path ENVIRONMENTS = Path.of("src/gametest/resources/data/nullspace/test_environment");

    /** Run alone in a crash and restart harness (a separate JVM for each stage): they return at once unless a crash mode is set. */
    private static final Set<String> CRASH_HARNESS = Set.of("CrashRecoveryGameTest", "ShipYardCrashGameTest");
    /** Development fixtures and the helpers themselves, not tests: a fixture keeps registrations whose computers are gone, so a generated world keeps its pads, and builds worlds for performance runs. */
    private static final Set<String> FIXTURES_AND_HELPERS = Set.of("PerfWorld", "DevStoryFixture", "TestPads");
    /** Ticks a test that waits for loading must have beyond its fixed waits (a wait for loading pauses 100 ms a tick, so this is about thirty seconds). */
    private static final int LOADING_TICKS = 300;
    /** Calls and hooks that change what the tests running beside this one see. */
    private static final List<String> SHARED = List.of("TravelService.transfer(", "TransferRecovery.recover(", "failureInjector", "writeObserver", ".noSave");

    private static final Pattern TEST = Pattern.compile("@GameTest\\(([^)]*)\\)\\s*(?:@\\w+(?:\\([^)]*\\))?\\s*)*public\\s+(?:static\\s+)?void\\s+(\\w+)\\s*\\(");

    record Source(String name, String text)
    {}

    record TestMethod(String name, String arguments, String body)
    {}

    static List<Source> sources() throws IOException
    {
        try (var files = Files.walk(TESTS))
        {
            var out = new ArrayList<Source>();
            for (var file : files.filter(f -> f.toString().endsWith(".java")).sorted().toList())
            {
                String name = file.getFileName().toString().replace(".java", "");
                out.add(new Source(name, withoutComments(Files.readString(file))));
            }
            return out;
        }
    }

    static String withoutComments(String text)
    {
        return text.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//[^\\n]*", "");
    }

    static List<TestMethod> tests(String text)
    {
        var out = new ArrayList<TestMethod>();
        var matcher = TEST.matcher(text);
        while (matcher.find())
        {
            int open = text.indexOf('{', matcher.end());
            int depth = 0, at = open;
            for (; at < text.length(); at++)
            {
                if (text.charAt(at) == '{')
                    depth++;
                else if (text.charAt(at) == '}' && --depth == 0)
                    break;
            }
            out.add(new TestMethod(matcher.group(2), matcher.group(1), text.substring(open, Math.min(at + 1, text.length()))));
        }
        return out;
    }

    /** A statement that calls {@code PadComputers.register} and throws its answer away. */
    static List<String> droppedRegistrations(Source source)
    {
        if (FIXTURES_AND_HELPERS.contains(source.name()))
            return List.of();
        var out = new ArrayList<String>();
        var matcher = Pattern.compile("(?m)^\\s*PadComputers\\.register\\(").matcher(source.text());
        while (matcher.find())
            out.add(source.name() + ": drops the result of PadComputers.register (at character " + matcher.start() + "); use TestPads.register");
        return out;
    }

    /** A test in the shared environment that launches or lands a ship, sets a shared hook, turns saving off or recovers every journal. */
    static List<String> sharedWithoutEnvironment(Source source)
    {
        if (CRASH_HARNESS.contains(source.name()))
            return List.of();
        var out = new ArrayList<String>();
        for (var test : tests(source.text()))
        {
            if (test.arguments().contains("environment"))
                continue;
            for (var token : SHARED)
                if (test.body().contains(token))
                {
                    out.add(source.name() + "." + test.name() + ": uses " + token + " without its own environment; add environment = \"nullspace:<name>\" and a file for it in test_environment");
                    break;
                }
        }
        return out;
    }

    /** A file that launches, lands or recovers ships and still waits a fixed number of ticks. */
    static List<String> fixedWaits(Source source)
    {
        if (CRASH_HARNESS.contains(source.name()) || source.name().equals("TestWaits"))
            return List.of();
        if (!source.text().contains("TravelService.transfer(") && !source.text().contains("TransferRecovery.recover("))
            return List.of();
        var out = new ArrayList<String>();
        var matcher = Pattern.compile("\\.runAfterDelay\\(").matcher(source.text());
        while (matcher.find())
            out.add(source.name() + ": waits with runAfterDelay (at character " + matcher.start() + ") in a file that launches, lands or recovers ships; use TestWaits.afterLoaded");
        return out;
    }

    /** A file that launches, lands or recovers ships and forces a chunk loaded without telling {@code TestWaits}, so that the wait cannot know to wait for it. */
    static List<String> untrackedHolds(Source source)
    {
        if (CRASH_HARNESS.contains(source.name()) || FIXTURES_AND_HELPERS.contains(source.name()) || source.name().equals("TestWaits"))
            return List.of();
        if (!source.text().contains("TravelService.transfer(") && !source.text().contains("TransferRecovery.recover("))
            return List.of();
        var out = new ArrayList<String>();
        var matcher = Pattern.compile("\\.setChunkForced\\([^;]*,\\s*true\\)").matcher(source.text());
        while (matcher.find())
            out.add(source.name() + ": forces a chunk loaded with setChunkForced (at character " + matcher.start() + ") in a file that launches, lands or recovers ships; use TestWaits.hold");
        return out;
    }

    /**
     * A test in a file that holds chunks and waits for them to load whose tick limit leaves no room for the loading. A wait for loading pauses 100 ms a tick, so
     * 300 ticks is about thirty seconds on top of the fixed waits; a limit of a hundred ticks ran out on a busy machine before the entities were loaded.
     */
    static List<String> tightBudgets(Source source)
    {
        if (!source.text().contains("TestWaits.hold(") || CRASH_HARNESS.contains(source.name()) || FIXTURES_AND_HELPERS.contains(source.name()))
            return List.of();
        var out = new ArrayList<String>();
        for (var test : tests(source.text()))
        {
            if (!test.body().contains("TestWaits.afterLoaded("))
                continue;
            int fixed = 0;
            var waits = Pattern.compile("TestWaits\\.after(?:Loaded|Ticks)\\(\\s*\\w+\\s*,\\s*(\\d+)\\s*,").matcher(test.body());
            while (waits.find())
                fixed += Integer.parseInt(waits.group(1));
            var limit = Pattern.compile("maxTicks\\s*=\\s*(\\d+)").matcher(test.arguments());
            int budget = limit.find() ? Integer.parseInt(limit.group(1)) : 100;
            if (budget < fixed + LOADING_TICKS)
                out.add(source.name() + "." + test.name() + ": maxTicks = " + budget + " leaves less than " + LOADING_TICKS + " ticks for loading beyond its " + fixed + " ticks of waits; use at least " + (fixed + LOADING_TICKS));
        }
        return out;
    }

    /**
     * A callback that schedules itself again with {@code runAfterDelay(n, name[0])}. The framework keeps its scheduled callbacks in a map keyed by the Runnable and
     * removes the entry of the one it has just run, so a callback that hands itself back is dropped and a poll silently stops after its first look. Schedule a new
     * lambda each time: {@code runAfterDelay(n, () -> name[0].run())}.
     */
    static List<String> selfSchedulingCallbacks(Source source)
    {
        var out = new ArrayList<String>();
        var assignment = Pattern.compile("(\\w+)\\[0\\]\\s*=\\s*\\(\\)\\s*->").matcher(source.text());
        while (assignment.find())
        {
            int open = source.text().indexOf('{', assignment.end());
            if (open < 0 || !source.text().substring(assignment.end(), open).isBlank())
                continue;
            int depth = 0, at = open;
            for (; at < source.text().length(); at++)
            {
                if (source.text().charAt(at) == '{')
                    depth++;
                else if (source.text().charAt(at) == '}' && --depth == 0)
                    break;
            }
            String body = source.text().substring(open, Math.min(at + 1, source.text().length()));
            if (Pattern.compile("runAfterDelay\\([^;]*,\\s*" + Pattern.quote(assignment.group(1)) + "\\[0\\]\\s*\\)").matcher(body).find())
                out.add(source.name() + ": " + assignment.group(1) + "[0] schedules itself again; use runAfterDelay(n, () -> " + assignment.group(1) + "[0].run()), a new callback each time");
        }
        return out;
    }

    /** An environment that a test names and that has no file: the test would not start. */
    static List<String> missingEnvironments(Source source)
    {
        var out = new ArrayList<String>();
        var matcher = Pattern.compile("environment\\s*=\\s*\"nullspace:([a-z0-9_]+)\"").matcher(source.text());
        while (matcher.find())
            if (!Files.exists(ENVIRONMENTS.resolve(matcher.group(1) + ".json")))
                out.add(source.name() + ": the environment nullspace:" + matcher.group(1) + " has no file in test_environment");
        return out;
    }

    private static void none(List<String> problems)
    {
        assertTrue(problems.isEmpty(), () -> "\n" + String.join("\n", problems) + "\n(see docs/development/test-hygiene.md)");
    }

    @Test
    void registrationResultsAreNeverDropped() throws IOException
    {
        none(sources().stream().flatMap(s -> droppedRegistrations(s).stream()).collect(Collectors.toList()));
    }

    @Test
    void testsThatChangeWhatTheirNeighboursSeeRunInTheirOwnEnvironment() throws IOException
    {
        none(sources().stream().flatMap(s -> sharedWithoutEnvironment(s).stream()).collect(Collectors.toList()));
    }

    @Test
    void shipLaunchingTestsWaitForLoadingNotForTicks() throws IOException
    {
        none(sources().stream().flatMap(s -> fixedWaits(s).stream()).collect(Collectors.toList()));
    }

    @Test
    void shipLaunchingTestsTellTheWaitWhichChunksTheyHold() throws IOException
    {
        none(sources().stream().flatMap(s -> untrackedHolds(s).stream()).collect(Collectors.toList()));
    }

    @Test
    void testsThatWaitForLoadingHaveTicksToSpareForIt() throws IOException
    {
        none(sources().stream().flatMap(s -> tightBudgets(s).stream()).collect(Collectors.toList()));
    }

    @Test
    void pollingCallbacksNeverRescheduleThemselves() throws IOException
    {
        none(sources().stream().flatMap(s -> selfSchedulingCallbacks(s).stream()).collect(Collectors.toList()));
    }

    @Test
    void everyNamedEnvironmentHasItsFile() throws IOException
    {
        none(sources().stream().flatMap(s -> missingEnvironments(s).stream()).collect(Collectors.toList()));
    }

    // The rules above are only worth their name while they find what they are for.

    @Test
    void theRulesFindWhatTheyAreFor()
    {
        var dropped = new Source("SomeGameTest", withoutComments("void f() {\n    PadComputers.register(level, at, pad, \"x\");\n}"));
        assertEquals(1, droppedRegistrations(dropped).size());
        var used = new Source("SomeGameTest", withoutComments("void f() {\n    var text = PadComputers.register(level, at, pad, \"x\");\n}"));
        assertEquals(0, droppedRegistrations(used).size());
        var helper = new Source("TestPads", withoutComments("PadComputers.register(level, at, pad, \"x\");"));
        assertEquals(0, droppedRegistrations(helper).size());

        var shared = new Source("SomeGameTest", withoutComments("@GameTest(maxTicks = 100)\npublic void launches(GameTestHelper ctx)\n{\n    TravelService.transfer(a, b, c, d);\n}"));
        assertEquals(1, sharedWithoutEnvironment(shared).size());
        var isolated = new Source("SomeGameTest", withoutComments("@GameTest(environment = \"nullspace:x\", maxTicks = 100)\npublic void launches(GameTestHelper ctx)\n{\n    TravelService.transfer(a, b, c, d);\n}"));
        assertEquals(0, sharedWithoutEnvironment(isolated).size());
        var commented = new Source("SomeGameTest", withoutComments("@GameTest(maxTicks = 100)\npublic void quiet(GameTestHelper ctx)\n{\n    // TravelService.transfer(a, b, c, d) is not called here\n}"));
        assertEquals(0, sharedWithoutEnvironment(commented).size());
        var hooked = new Source("SomeGameTest", withoutComments("@GameTest(maxTicks = 100)\npublic void hooks(GameTestHelper ctx)\n{\n    TravelService.failureInjector = p -> {};\n}"));
        assertEquals(1, sharedWithoutEnvironment(hooked).size());

        var waits = new Source("SomeGameTest", withoutComments("void f() {\n    TravelService.transfer(a, b, c, d);\n    ctx.runAfterDelay(10, () -> {});\n}"));
        assertEquals(1, fixedWaits(waits).size());
        var polls = new Source("SomeGameTest", withoutComments("void f() {\n    TravelService.transfer(a, b, c, d);\n    TestWaits.afterLoaded(ctx, 10, () -> {});\n}"));
        assertEquals(0, fixedWaits(polls).size());
        var unrelated = new Source("SomeGameTest", withoutComments("void f() {\n    ctx.runAfterDelay(10, () -> {});\n}"));
        assertEquals(0, fixedWaits(unrelated).size());

        var forced = new Source("SomeGameTest", withoutComments("void f() {\n    TravelService.transfer(a, b, c, d);\n    level.setChunkForced(x, z, true);\n}"));
        assertEquals(1, untrackedHolds(forced).size());
        var held = new Source("SomeGameTest", withoutComments("void f() {\n    TravelService.transfer(a, b, c, d);\n    TestWaits.hold(ctx, level, x, z);\n    level.setChunkForced(x, z, false);\n}"));
        assertEquals(0, untrackedHolds(held).size());
        var elsewhere = new Source("SomeGameTest", withoutComments("void f() {\n    level.setChunkForced(x, z, true);\n}"));
        assertEquals(0, untrackedHolds(elsewhere).size());

        String tight = "@GameTest(maxTicks = 100)\npublic void waits(GameTestHelper ctx)\n{\n    TestWaits.hold(ctx, level, 1, 1);\n    TestWaits.afterLoaded(ctx, 10, () -> {});\n}";
        assertEquals(1, tightBudgets(new Source("SomeGameTest", withoutComments(tight))).size());
        assertEquals(0, tightBudgets(new Source("SomeGameTest", withoutComments(tight.replace("maxTicks = 100", "maxTicks = 310")))).size());
        assertEquals(1, tightBudgets(new Source("SomeGameTest", withoutComments(tight.replace("(maxTicks = 100)", "(environment = \"nullspace:x\")")))).size());
        var noHolds = tight.replace("    TestWaits.hold(ctx, level, 1, 1);\n", "");
        assertEquals(0, tightBudgets(new Source("SomeGameTest", withoutComments(noHolds))).size());

        var itself = new Source("SomeGameTest", withoutComments("void f() {\n    Runnable[] poll = {null};\n    poll[0] = () ->\n    {\n        if (!ready())\n        {\n            ctx.runAfterDelay(5, poll[0]);\n            return;\n        }\n    };\n    ctx.runAfterDelay(5, poll[0]);\n}"));
        assertEquals(1, selfSchedulingCallbacks(itself).size());
        var fresh = new Source("SomeGameTest", withoutComments("void f() {\n    Runnable[] poll = {null};\n    poll[0] = () ->\n    {\n        if (!ready())\n        {\n            ctx.runAfterDelay(5, () -> poll[0].run());\n            return;\n        }\n    };\n    ctx.runAfterDelay(5, poll[0]);\n}"));
        assertEquals(0, selfSchedulingCallbacks(fresh).size());
    }

    @Test
    void theScanSeesTheTests() throws IOException
    {
        var all = sources();
        long methods = all.stream().mapToLong(s -> tests(s.text()).size()).sum();
        assertTrue(all.size() > 100 && methods > 200, "The scan found " + all.size() + " files and " + methods + " tests: the source folder or the test pattern moved");
    }
}

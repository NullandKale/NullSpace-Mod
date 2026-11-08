package com.nullandkale.nullspace.model.computer;

import java.util.*;
import java.util.stream.*;

import com.google.gson.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

class ComputerResilienceTest
{
    private static VirtualComputer machine()
    {
        return new VirtualComputer((name, args) ->
        {
            throw new AssertionError("Unexpected ship action: " + name);
        });
    }

    @TestFactory
    Stream<DynamicTest> hostEscapeAndSyntaxInjectionStayInGuest()
    {
        return Stream.of("C:\\Windows\\system32", "\\\\server\\share", "file:///etc/passwd", "http://localhost", "../../bad\0name", "$(whoami)", "`whoami`", "a:b", "x\ny").map(path -> DynamicTest.dynamicTest("reject guest path " + path.replace('\0', '?'), () -> assertThrows(IllegalArgumentException.class, () -> VirtualFiles.path("/home/crew", path))));
    }

    @Test
    void diskQuotaFailureIsAtomicAndOverwritesReclaimSpace()
    {
        var f = new VirtualFiles();
        for (int i = 0; i < 8; i++)
            f.write("/home/crew/f" + i, "x".repeat(VirtualFiles.MAX_FILE));
        var before = f.files();
        assertThrows(IllegalArgumentException.class, () -> f.write("/home/crew/f8", "x"));
        assertEquals(before, f.files());
        f.write("/home/crew/f0", "x");
        f.write("/home/crew/f8", "y".repeat(VirtualFiles.MAX_FILE - 1));
        assertEquals(VirtualFiles.MAX_BYTES, f.files().values().stream().mapToInt(String::length).sum());
        assertThrows(UnsupportedOperationException.class, () -> f.files().clear());
    }

    @Test
    void fileCountLimitStillAllowsUpdatingAnExistingFile()
    {
        var f = new VirtualFiles();
        for (int i = 0; i < VirtualFiles.MAX_FILES; i++)
            f.write("/home/crew/f" + i, "x");
        assertThrows(IllegalArgumentException.class, () -> f.write("/home/crew/overflow", "x"));
        f.write("/home/crew/f0", "updated");
        assertEquals("updated", f.read("/home/crew/f0"));
    }

    @Test
    void failedMoveKeepsTheSourceAndExistingTarget()
    {
        var c = machine();
        c.files.write("/home/crew/a", "precious");
        assertThrows(IllegalArgumentException.class, () -> c.shell("mv a /dev/ship/a", Map.of()));
        assertEquals("precious", c.files.read("/home/crew/a"));
    }

    @Test
    void guestDirectoriesAndWorkingDirectorySurviveRestore()
    {
        var c = machine();
        c.shell("mkdir logs", Map.of());
        c.shell("cd logs", Map.of());
        c.shell("echo flight > log", Map.of());
        var restored = machine();
        restored.restore(c.save());
        assertEquals("/home/crew/logs", restored.shell("pwd", Map.of()));
        assertEquals("flight\n", restored.shell("cat log", Map.of()));
    }

    @Test
    void sleepingDetachedJobResumesAtTheSameDeadlineWithoutRepeatingActions()
    {
        var calls = new ArrayList<String>();
        var c = new VirtualComputer((name, args) ->
        {
            calls.add(name);
            return 0;
        });
        c.files.write("/home/crew/task.py", "ship.status()\nsleep(2)\nship.rooms()\n");
        c.run("/home/crew/task.py", ScriptLanguage.Syntax.PYTHON, true);
        c.tick(false, true);
        var restored = new VirtualComputer((name, args) ->
        {
            calls.add(name);
            return 0;
        });
        restored.restore(c.save());
        for (int i = 0; i < 39; i++)
            restored.tick(false, true);
        assertEquals(List.of("status"), calls);
        restored.tick(false, true);
        assertEquals(List.of("status", "rooms"), calls);
        assertTrue(restored.processes.isEmpty());
    }

    @Test
    void foregroundInterruptDoesNotKillDetachedAutomation()
    {
        var c = machine();
        c.files.write("/home/crew/busy.py", "while True:\n    sleep(1)\n");
        int detached = c.run("/home/crew/busy.py", ScriptLanguage.Syntax.PYTHON, true);
        int foreground = c.run("/home/crew/busy.py", ScriptLanguage.Syntax.PYTHON, false);
        c.input("\3");
        c.tick(true, true);
        assertTrue(c.processes.containsKey(detached));
        assertFalse(c.processes.containsKey(foreground));
    }

    @Test
    void editorCancelLeavesOldFileAndSaveReplacesIt()
    {
        var c = machine();
        c.files.write("/home/crew/config", "old");
        c.input("edit config\rdiscard\r\3");
        assertEquals("old", c.files.read("/home/crew/config"));
        c.input("edit config\rnew\r.\r");
        assertEquals("new\n", c.files.read("/home/crew/config"));
    }

    @Test
    void zeroInstructionBudgetDoesNotRunNativeActions()
    {
        var c = machine();
        c.files.write("/home/crew/never.py", "ship.fire()\n");
        int id = c.run("/home/crew/never.py", ScriptLanguage.Syntax.PYTHON, true);
        for (int i = 0; i < 50; i++)
            assertEquals(0, c.tick(false, true, 0));
        assertEquals(0, c.processes.get(id).job().pc);
    }

    @Test
    void restoredJobsReplaceExistingJobsInsteadOfDuplicatingThem()
    {
        var c = machine();
        c.files.write("/home/crew/busy.py", "while True:\n    sleep(1)\n");
        c.run("/home/crew/busy.py", ScriptLanguage.Syntax.PYTHON, true);
        String image = c.save();
        c.restore(image);
        c.restore(image);
        assertEquals(1, c.processes.size());
    }

    @Test
    void rejectedRestoreCannotDestroyAWorkingDisk()
    {
        var c = machine();
        c.files.write("/home/crew/valuable", "keep me");
        var corrupt = JsonParser.parseString(c.save()).getAsJsonObject();
        corrupt.add("files", new JsonObject());
        corrupt.getAsJsonObject("files").addProperty("/dev/host", "invalid destination");
        var before = c.files.files();
        assertThrows(IllegalArgumentException.class, () -> c.restore(corrupt.toString()));
        assertEquals(before, c.files.files(), "Validate the whole image before replacing live state");
    }

    @TestFactory
    Stream<DynamicTest> malformedImagesAreRejected()
    {
        return Stream.of("version", "pc", "syntax", "variables").map(field -> DynamicTest.dynamicTest("reject saved " + field, () ->
        {
            var c = machine();
            c.files.write("/home/crew/task.py", "while True:\n    sleep(1)\n");
            c.run("/home/crew/task.py", ScriptLanguage.Syntax.PYTHON, true);
            var root = JsonParser.parseString(c.save()).getAsJsonObject();
            var job = root.getAsJsonArray("jobs").get(0).getAsJsonObject();
            switch (field)
            {
                case "version" -> root.addProperty("version", 999);
                case "pc" -> job.addProperty("pc", Integer.MAX_VALUE);
                case "syntax" -> job.addProperty("syntax", "JAVASCRIPT");
                case "variables" -> job.getAsJsonObject("variables").addProperty("oversized", "x".repeat(8193));
            }
            assertThrows(RuntimeException.class, () -> machine().restore(root.toString()));
        }));
    }
}

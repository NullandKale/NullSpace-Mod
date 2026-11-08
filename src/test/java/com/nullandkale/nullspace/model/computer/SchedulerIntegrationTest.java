package com.nullandkale.nullspace.model.computer;

import java.util.*;
import java.util.stream.*;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

class SchedulerIntegrationTest
{
    @Test
    void worldWaitRemainsVisibleUntilTheDelayedInstructionCanRetry()
    {
        int[] calls = {0};
        var computer = new VirtualComputer((n, a) ->
        {
            if (++calls[0] == 1)
                throw new ScriptLanguage.Unavailable("Hull unloaded");
            return 1;
        });
        computer.files.write("/home/crew/wait.py", "ship.status()\nwhile True:\n    sleep(1)\n");
        int id = computer.run("/home/crew/wait.py", ScriptLanguage.Syntax.PYTHON, true);
        computer.tick(false, true);
        for (int i = 0; i < 19; i++)
        {
            computer.tick(false, true);
            assertEquals("Hull unloaded", computer.processes.get(id).job().waiting);
            assertEquals(1, calls[0]);
        }
        computer.tick(false, true);
        assertEquals(2, calls[0]);
        assertEquals("", computer.processes.get(id).job().waiting);
    }

    @TestFactory
    Stream<DynamicTest> tinyServerBudgetsEventuallyReachEveryMachine()
    {
        return IntStream.of(1, 2, 7, 31).mapToObj(budget -> DynamicTest.dynamicTest("server budget " + budget, () ->
        {
            var scheduler = new FairScheduler<VirtualComputer>();
            var computers = new ArrayList<VirtualComputer>();
            for (int i = 0; i < 32; i++)
            {
                var c = new VirtualComputer((n, a) -> 0);
                c.files.write("/home/crew/busy.py", "x = 0\nwhile True:\n    x = x + 1\n");
                c.run("/home/crew/busy.py", ScriptLanguage.Syntax.PYTHON, true);
                computers.add(c);
            }
            for (int tick = 0; tick < 256; tick++)
            {
                int remaining = computers.size(), available = budget;
                for (var c : scheduler.order(computers))
                    available -= c.tick(false, true, FairScheduler.quantum(available, remaining--, 256));
                assertTrue(available >= 0 && available <= budget, "Aggregate execution cannot exceed the server budget");
            }
            for (var c : computers)
                assertTrue(ScriptLanguage.number(c.processes.values().iterator().next().job().variables.get("x")) > 0, "Every machine makes progress");
        }));
    }

    @Test
    void membershipChangesCannotDuplicateOrLoseScheduledComputers()
    {
        var scheduler = new FairScheduler<Integer>();
        var members = new ArrayList<Integer>();
        for (int i = 0; i < 40; i++)
            members.add(i);
        var random = new Random(8123);
        for (int tick = 0; tick < 1000; tick++)
        {
            if (tick % 3 == 0 && !members.isEmpty())
                members.remove(random.nextInt(members.size()));
            if (tick % 2 == 0)
                members.add(1000 + tick);
            var order = scheduler.order(members);
            assertEquals(members.size(), order.size());
            assertEquals(new HashSet<>(members), new HashSet<>(order));
            assertEquals(members.size(), new HashSet<>(order).size());
        }
        assertTrue(scheduler.order(List.of()).isEmpty());
    }

    @Test
    void aBudgetYieldRetriesNextTickWithoutRepeatingCompletedInstructions()
    {
        int[] calls = {0};
        var computer = new VirtualComputer((n, a) ->
        {
            if (++calls[0] == 1)
                throw new ScriptLanguage.Unavailable("budget", 1);
            return 7;
        });
        computer.files.write("/home/crew/retry.py", "before = 1\nx = ship.status()\nafter = 2\nwhile True:\n    sleep(1)\n");
        int id = computer.run("/home/crew/retry.py", ScriptLanguage.Syntax.PYTHON, true);
        computer.tick(false, true);
        assertEquals(1, calls[0]);
        assertEquals(1, computer.processes.get(id).job().pc);
        computer.tick(false, true);
        assertEquals(2, calls[0]);
        assertEquals("", computer.processes.get(id).job().waiting, "Successful resumption clears stale WAIT status");
        assertEquals(7.0, ScriptLanguage.number(computer.processes.get(id).job().variables.get("x")));
        for (int i = 0; i < 40; i++)
            computer.tick(false, true);
        assertEquals(2, calls[0], "Resuming a yielded call cannot repeat it after success");
        assertThrows(IllegalArgumentException.class, () -> new ScriptLanguage.Unavailable("invalid", 0));
    }
}

# Test hygiene: why a GameTest must not fail at random

The native GameTests run about ninety at a time on one server in one world, so a test that is right alone can fail now and then in the full run. That costs a whole [release check](local-verification.md#the-release-check) (about 25 minutes) to find, and finds one failure at a time. In November 2026 two release checks in a row failed on different tests that passed alone. Both had the same two causes, and [`GameTestHygieneTest`](../../src/test/java/com/nullandkale/nullspace/testing/GameTestHygieneTest.java) now fails the build on them, in the unit tests that run first, in seconds.

## The rules

1. **Do not drop the answer of a pad registration.** A registered pad outlives its test, every batch of tests is laid out over the same ground, and `PadComputers.register` refuses (it returns the reason as text) when an earlier test's pad has the same name or overlaps the ring. A test that ignored the text failed later, somewhere else, with "Register the pad below this ship first". Use [`TestPads.register`](../../src/gametest/java/com/nullandkale/nullspace/testing/TestPads.java): it removes pads left by tests that have finished (their computer is gone or in an unloaded chunk), never one that a running test owns, and throws with the reason when the registration is refused.
2. **A test that changes what its neighbours see runs in its own environment.** Launching or landing a ship takes the first free orbit slot; setting the failure hook or the write observer, turning saving off, or recovering every journal changes the world for the tests running beside it. Give it `@GameTest(environment = "nullspace:<name>")` and a file `<name>.json` in [`test_environment`](../../src/gametest/resources/data/nullspace/test_environment) (the body of any of them will do). An environment's tests run after the others, alone.
3. **Wait for loading, not for ticks.** Entities and chunks load on other threads and a test server never sleeps between ticks, so `runAfterDelay(10, ...)` is a few milliseconds. A test that launched when the machine was idle and came back "Ship passengers are still loading" when it was busy. In a file that launches, lands or recovers ships, wait with [`TestWaits.afterLoaded`](../../src/gametest/java/com/nullandkale/nullspace/testing/TestWaits.java): it waits at least the ticks you give and then until every chunk the test holds, here and in space, has its entities. Hold a chunk with `TestWaits.hold(ctx, level, x, z)`, not `setChunkForced`, so the wait knows which chunks are the test's: many tests force chunks and never release them, and a chunk an earlier test left forced says nothing about this test's fixture. The test's own tick limit ends a wait that never finishes. A wait that is about time passing, not about loading (a protocol timeout expiring, a countdown running out), uses `TestWaits.afterTicks`: it says so, and a test that waits for a timeout must not wait for chunks that it left unloaded on purpose.

The test also checks that every environment a test names has its file, and that the scan still sees the tests (so a moved folder cannot make the rules pass by finding nothing). Its last test feeds each rule a sample that must be found and one that must not.

## What is exempt

- [`CrashRecoveryGameTest`](../../src/gametest/java/com/nullandkale/nullspace/gameplay/travel/CrashRecoveryGameTest.java) and [`ShipYardCrashGameTest`](../../src/gametest/java/com/nullandkale/nullspace/gameplay/shipyard/ShipYardCrashGameTest.java): their `restart` test returns at once unless a crash script sets a mode, and then runs alone in its own JVM.
- `PerfWorld` and `DevStoryFixture` are fixtures for generated worlds, not tests: they keep registrations whose computers are gone on purpose.

Add an exemption in the test's two sets, with the reason beside it, and nowhere else.

## What the rules do not see

They read source text. A launch that happens in another class and a wait that happens here are not connected, and a wait written some other way than `runAfterDelay` is not found. A new kind of shared state needs a new rule. When a release check fails on a test that passes alone, do not fix that one test and run again: find what it shares, search the tests for every other use of it, fix them together, and put the rule here.

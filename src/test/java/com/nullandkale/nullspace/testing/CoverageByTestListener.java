package com.nullandkale.nullspace.testing;

import java.nio.file.*;

import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.*;

/**
 * An audit tool, not a test: when the JaCoCo agent is attached and {@code NULLSPACE_COVERAGE_DIR} names a folder, it writes
 * the execution data of every unit test to its own file there, so which main code each test reaches can be compared.
 * Without both, it does nothing.
 */
public final class CoverageByTestListener implements TestExecutionListener
{
    private static final String DIR = System.getenv("NULLSPACE_COVERAGE_DIR");
    private Object agent;

    private Object agent()
    {
        if (DIR == null || DIR.isBlank())
            return null;
        if (agent == null)
        {
            try
            {
                agent = Class.forName("org.jacoco.agent.rt.RT").getMethod("getAgent").invoke(null);
            }
            catch (ReflectiveOperationException | LinkageError absent)
            {
                return null;
            }
        }
        return agent;
    }

    @Override
    public void executionStarted(TestIdentifier id)
    {
        var a = agent();
        if (a != null && id.isTest())
            call(a, "reset", new Class<?>[0]);
    }

    @Override
    public void executionFinished(TestIdentifier id, TestExecutionResult result)
    {
        var a = agent();
        if (a == null || !id.isTest())
            return;
        var data = (byte[]) call(a, "getExecutionData", new Class<?>[]{boolean.class}, true);
        try
        {
            Files.createDirectories(Path.of(DIR));
            String name = id.getUniqueId().replaceAll("[^A-Za-z0-9._-]", "_");
            Files.write(Path.of(DIR, name.substring(Math.max(0, name.length() - 180)) + ".exec"), data);
        }
        catch (java.io.IOException failure)
        {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    private static Object call(Object target, String method, Class<?>[] types, Object... args)
    {
        try
        {
            return target.getClass().getMethod(method, types).invoke(target, args);
        }
        catch (ReflectiveOperationException failure)
        {
            throw new IllegalStateException(failure);
        }
    }
}

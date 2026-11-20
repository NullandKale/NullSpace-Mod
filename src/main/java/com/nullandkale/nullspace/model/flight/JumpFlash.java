package com.nullandkale.nullspace.model.flight;

/**
 * The flash a crew sees as their ship jumps between places in space. A jump moves nobody, so there is no screen and nothing waits: the flash covers the moment
 * the sky changes and the crew keep moving through it. It rises to its peak in {@link #RISE}, holds while the client's new sky is not ready yet (at most
 * {@link #HOLD} from the start), then fades over {@link #FADE}. The curve runs on the clock, so it looks the same on a slow machine.
 */
public final class JumpFlash
{
    public static final double RISE = .08, HOLD = .5, FADE = .6, PEAK = .85;

    /**
     * The flash's opacity {@code age} seconds after it began, when the new sky was ready {@code ready} seconds after it began ({@code Double.POSITIVE_INFINITY}
     * while it is not ready).
     */
    public static double opacity(double age, double ready)
    {
        if (age < 0)
            return 0;
        if (age < RISE)
            return PEAK * smooth(age / RISE);
        double release = Math.max(RISE, Math.min(ready, HOLD));
        if (age < release)
            return PEAK;
        double t = (age - release) / FADE;
        return t >= 1 ? 0 : PEAK * (1 - smooth(t));
    }

    /** Whether a flash that began {@code age} seconds ago has faded out, whatever its sky did. */
    public static boolean over(double age)
    {
        return age >= HOLD + FADE;
    }

    private static double smooth(double t)
    {
        return t * t * (3 - 2 * t);
    }

    private JumpFlash()
    {
    }
}

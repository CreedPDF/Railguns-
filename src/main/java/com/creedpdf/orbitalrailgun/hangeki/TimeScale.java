package com.creedpdf.orbitalrailgun.hangeki;

/**
 * How fast time runs, 1 = normal. The server's tick loop reads {@link #server} (MinecraftServerTimeMixin); every
 * client is told the same value and its tick clock follows it (client RenderTickCounterMixin, via {@link #client}),
 * so the world slows for everyone at once and stays in step.
 */
public final class TimeScale {
    private TimeScale() {}

    /** Read by the server thread's tick loop. */
    public static volatile float server = 1f;
    /** The scale the client is easing toward (set from packets; client side only). */
    public static volatile float clientTarget = 1f;
    /** The scale the client clock runs at this frame (eased toward {@link #clientTarget}). */
    public static volatile float client = 1f;
}

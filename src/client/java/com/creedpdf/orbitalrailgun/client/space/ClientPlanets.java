package com.creedpdf.orbitalrailgun.client.space;

import com.creedpdf.orbitalrailgun.space.Blast;
import com.creedpdf.orbitalrailgun.space.Body;
import net.minecraft.client.MinecraftClient;

import java.util.Arrays;

/** Client mirror of {@link com.creedpdf.orbitalrailgun.space.PlanetState}. */
public final class ClientPlanets {
    private ClientPlanets() {}

    private static final long[] START = new long[Body.ALL.length];

    static {
        Arrays.fill(START, -1L);
    }

    public static void set(long[] v) {
        Arrays.fill(START, -1L);
        System.arraycopy(v, 0, START, 0, Math.min(v.length, START.length));
    }

    /** Ticks since ARES-0's beam hit this body (with partial tick), or -1 if it is intact. */
    public static float elapsed(Body b, float pt) {
        long s = START[b.ordinal()];
        MinecraftClient mc = MinecraftClient.getInstance();
        if (s < 0 || mc.world == null) return -1f;
        return Math.max(0f, mc.world.getTime() - s + pt);
    }

    public static boolean gone(Body b) {
        return elapsed(b, 0f) >= Blast.DETONATE;
    }
}

package com.creedpdf.orbitalrailgun.util;

/**
 * Client effect budget (0..1) visible to common code (entities spawn their own particles on the
 * client). Set every tick by the client's quality settings; 1 on a dedicated server.
 */
public final class FxBudget {
    private FxBudget() {}

    public static volatile float particles = 1f;

    /** Scales a particle count, never below {@code min}. */
    public static int count(int n, int min) {
        return Math.max(min, Math.round(n * particles));
    }
}

package com.creedpdf.orbitalrailgun.legion;

/** How an idle swarm arranges itself round its owner (cycled with the formation key). Ordinals are saved: append only. */
public enum SwarmFormation {
    /** A wide ring at chest height, circling. */
    HALO("HALO"),
    /** A tight ring above the head, blades tip-up, turning slowly. */
    CROWN("CROWN"),
    /** Fanned behind the shoulders like wings, beating gently. */
    WINGS("WINGS"),
    /** An arc out in front, every blade pointing where you look. */
    VANGUARD("VANGUARD");

    public final String label;
    private static final SwarmFormation[] ALL = values();

    SwarmFormation(String label) {
        this.label = label;
    }

    public static SwarmFormation byId(int id) {
        return id >= 0 && id < ALL.length ? ALL[id] : HALO;
    }

    public SwarmFormation next() {
        return ALL[(ordinal() + 1) % ALL.length];
    }
}

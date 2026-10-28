package com.creedpdf.orbitalrailgun.legion.entity;

/** What an {@link AutonomousWeaponEntity} is doing. Ordinals are synced: append only. */
public enum SwarmState {
    /** Holding its place in the formation. */
    ORBIT,
    /** Dashing through a target and back. */
    STRIKE,
    /** Intercepting a projectile aimed at its owner. */
    DEFEND,
    /** Part of a Blade Storm vortex. */
    STORM;

    private static final SwarmState[] ALL = values();

    public static SwarmState byId(int id) {
        return id >= 0 && id < ALL.length ? ALL[id] : ORBIT;
    }

    /** States whose path both sides compute themselves (the client ignores position packets in them). */
    public boolean analytic() {
        return this == ORBIT || this == STORM;
    }
}

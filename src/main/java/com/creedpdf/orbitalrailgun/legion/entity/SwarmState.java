package com.creedpdf.orbitalrailgun.legion.entity;

/** What an {@link AutonomousWeaponEntity} is doing. Ordinals are synced: append only. */
public enum SwarmState {
    /** Circling its owner. */
    ORBIT,
    /** Dashing through a target and back. */
    STRIKE,
    /** Intercepting a projectile aimed at its owner. */
    DEFEND;

    private static final SwarmState[] ALL = values();

    public static SwarmState byId(int id) {
        return id >= 0 && id < ALL.length ? ALL[id] : ORBIT;
    }
}

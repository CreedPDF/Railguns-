package com.creedpdf.orbitalrailgun.entity;

import java.util.Locale;

/**
 * Lifecycle of a railgun. The ordinal is synced through the DataTracker and saved to NBT, so
 * only append new values at the end.
 *
 * <pre>
 *  FALLING ─▶ POD ─(panel: RETRACT)─▶ PANEL_OPEN ─▶ TRANSFORMING ─▶ IDLE ⇄ CHARGING ─▶ FIRING
 *                                                                    ▲                    │
 *                                                                    └──── COOLDOWN ◀─────┘
 *  IDLE/COOLDOWN ─(panel: RETRACT, confirm)─▶ PACKING ─▶ POD_READY ─(panel: RETURN)─▶ LAUNCHING
 * </pre>
 */
public enum RailgunState {
    FALLING,
    POD,
    PANEL_OPEN,
    TRANSFORMING,
    IDLE,
    CHARGING,
    FIRING,
    COOLDOWN,
    PACKING,
    POD_READY,
    LAUNCHING,
    /** After deploying: attachment parts drop from orbital storage, vector in and dock. */
    ARMING,
    /** Scan array up: a holographic pulse maps ores and hostiles around the emplacement. */
    SCANNING;

    private static final RailgunState[] VALUES = values();

    public static RailgunState byId(int id) {
        return id >= 0 && id < VALUES.length ? VALUES[id] : POD;
    }

    /** Deployed and able to take fire-control commands. */
    public boolean isOperational() {
        return this == IDLE || this == CHARGING || this == FIRING || this == COOLDOWN;
    }

    /** Compact pillar form (small hitbox, solid). */
    public boolean isPodForm() {
        return this == FALLING || this == POD || this == PANEL_OPEN || this == POD_READY || this == LAUNCHING;
    }

    public String translationKey() {
        return "gui.orbital_railgun.state." + name().toLowerCase(Locale.ROOT);
    }
}

package com.creedpdf.orbitalrailgun.entity;

/**
 * Lifecycle of a railgun platform. The ordinal is synced through the DataTracker, so only append
 * new values at the end.
 *
 * <pre>
 * FALLING ──(hits ground)──▶ DEPLOYING ──(anim done)──▶ IDLE ──(FIRE pressed)──▶ CHARGING
 *                                                       ▲                         │   │
 *                                                       │                  (abort)│   │(100%)
 *                                                       └──── COOLDOWN ◀── FIRING ◀┘   │
 *                                                       ▲                              │
 *                                                       └──────────────────────────────┘
 * </pre>
 */
public enum RailgunState {
    FALLING,
    DEPLOYING,
    IDLE,
    CHARGING,
    FIRING,
    COOLDOWN;

    private static final RailgunState[] VALUES = values();

    public static RailgunState byId(int id) {
        return id >= 0 && id < VALUES.length ? VALUES[id] : IDLE;
    }

    /** True once the platform is locked down and can be operated from the menu. */
    public boolean isOperational() {
        return this == IDLE || this == CHARGING || this == FIRING || this == COOLDOWN;
    }

    public String translationKey() {
        return "gui.orbital_railgun.state." + name().toLowerCase(java.util.Locale.ROOT);
    }
}

package com.creedpdf.orbitalrailgun.util;

/**
 * Central tuning constants. Everything that affects balance, timing or geometry lives here so
 * the rest of the code never hard-codes magic numbers.
 *
 * <p>Geometry values (PIVOT_Y, BARREL_LENGTH) must match the GeckoLib model: the turret bone's
 * pivot height and the barrel length, in blocks (16 model units = 1 block).</p>
 */
public final class RailgunConfig {
    private RailgunConfig() {}

    // ---- Remote / deployment ------------------------------------------------------------
    /** Max raycast distance for the remote, in blocks. */
    public static final double REMOTE_RANGE = 256.0;
    /** Ticks between confirming a target and the platform being released from orbit. */
    public static final int DROP_COUNTDOWN_TICKS = 100;
    /** Item cooldown applied to the remote after a successful call-in. */
    public static final int REMOTE_COOLDOWN_TICKS = 160;
    /** How far above the landing site the platform spawns (clamped to world height). */
    public static final int DROP_HEIGHT = 180;
    /** Initial and terminal fall speed, blocks/tick. */
    public static final double DROP_START_SPEED = 1.5;
    public static final double DROP_ACCEL = 0.12;
    public static final double DROP_TERMINAL_SPEED = 2.6;

    // ---- Lifecycle timing ----------------------------------------------------------------
    /** Must match the length of animation.railgun.deploy (3.0 s). */
    public static final int DEPLOY_TICKS = 60;
    public static final int CHARGE_TICKS = 120;
    public static final int FIRE_TICKS = 44;
    public static final int COOLDOWN_TICKS = 100;

    // ---- Weapon --------------------------------------------------------------------------
    public static final double MAX_FIRE_RANGE = 512.0;
    public static final float IMPACT_EXPLOSION_POWER = 10.0f;
    public static final float SECONDARY_EXPLOSION_POWER = 6.0f;
    /** Number of secondary explosions punched downward from the impact point. */
    public static final int PENETRATION_STEPS = 4;
    public static final double PENETRATION_STEP_DEPTH = 4.0;
    /** If false, explosions damage entities but never break blocks. */
    public static final boolean BREAK_BLOCKS = true;
    public static final float BEAM_DAMAGE = 60.0f;
    public static final double BEAM_HIT_RADIUS = 3.5;

    // ---- Interaction ---------------------------------------------------------------------
    public static final double MAX_INTERACT_DISTANCE = 24.0;

    // ---- Geometry (blocks) ---------------------------------------------------------------
    /** Height of the turret yaw/pitch pivot above the entity's feet. */
    public static final double PIVOT_Y = 7.5;
    /** Distance from pivot to muzzle along the barrel. */
    public static final double BARREL_LENGTH = 19.5;
    /** Max degrees per tick the turret slews while tracking a new target. */
    public static final float SLEW_DEG_PER_TICK = 2.5f;
    /** Half-extent of the rendered model, used for frustum culling (barrel reach + margin). */
    public static final double RENDER_REACH = 22.0;
}

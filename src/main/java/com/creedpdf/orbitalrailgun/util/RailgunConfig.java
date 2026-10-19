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
    public static final double REMOTE_RANGE = 2000.0;
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
    /** Must match animation.railgun.panel_open (1.5 s). */
    public static final int PANEL_TICKS = 30;
    /** Must match animation.railgun.deploy / pack (12.0 s). */
    public static final int TRANSFORM_TICKS = 240;
    public static final int PACK_TICKS = 240;
    /** Tick of the transformation at which the ground clamps slam down (6.5 s). */
    public static final int CLAMP_SLAM_TICK = 130;
    /** Tick of the transformation at which the rail starts lowering from vertical. */
    public static final int RAIL_LOWER_TICK = 176;
    /** How long the rail takes to swing between vertical and level (eased), in ticks. */
    public static final int RAIL_MOVE_TICKS = 26;
    /** Tick of the pack-up at which the rail starts rising to vertical (barrel already telescoped in). */
    public static final int PACK_RAIL_RAISE_TICK = 38;
    /** How long a "confirm retract" prompt on the panel stays armed. */
    public static final int CONFIRM_TICKS = 80;
    /** Launch back to orbit: acceleration, top speed, and how high before it is removed. */
    public static final double LAUNCH_ACCEL = 0.06;
    public static final double LAUNCH_MAX_SPEED = 3.5;
    public static final double LAUNCH_REMOVE_HEIGHT = 260;
    /** Max distance from the player to the control panel to use it. */
    public static final double PANEL_REACH = 7.0;
    public static final int CHARGE_TICKS = 120;
    public static final int FIRE_TICKS = 70;
    public static final int COOLDOWN_TICKS = 100;

    // ---- Weapon --------------------------------------------------------------------------
    public static final double MAX_FIRE_RANGE = 2000.0;
    public static final float IMPACT_EXPLOSION_POWER = 20.0f;
    public static final float SECONDARY_EXPLOSION_POWER = 10.0f;
    /**
     * Penetration: the beam bores on through the target along its direction, vaporizing a
     * tunnel BORE_RADIUS wide up to BORE_DEPTH blocks deep at BORE_SPEED blocks per tick, with a
     * blast every BORE_BLAST_EVERY ticks at the bore tip. Anything in the way between the muzzle
     * and the target is cut through too (PATH_RADIUS). Only unbreakable blocks stop it.
     */
    public static final double BORE_DEPTH = 96.0;
    public static final double BORE_RADIUS = 4.0;
    public static final double BORE_SPEED = 2.4;
    public static final int BORE_BLAST_EVERY = 6;
    public static final float BORE_BLAST_POWER = 8.0f;
    public static final double PATH_RADIUS = 2.5;
    /** If false, explosions damage entities but never break blocks. */
    public static final boolean BREAK_BLOCKS = true;
    public static final float BEAM_DAMAGE = 250.0f;
    public static final double BEAM_HIT_RADIUS = 7.0;
    /** Ring of blasts around the impact a moment after the strike (count, radius, power). */
    public static final int RING_BLASTS = 8;
    public static final double RING_BLAST_RADIUS = 9.0;
    public static final float RING_BLAST_POWER = 9.0f;

    // ---- Interaction ---------------------------------------------------------------------
    public static final double MAX_INTERACT_DISTANCE = 24.0;
    /** How often (ticks) an open tablet polls the server for railgun status. */
    public static final int TABLET_POLL_TICKS = 5;

    // ---- Geometry (blocks) ---------------------------------------------------------------
    /** Height of the turret yaw/pitch pivot above the entity's feet. */
    public static final double PIVOT_Y = 118.0 / 16;
    /** Distance from pivot to muzzle along the barrel. */
    public static final double BARREL_LENGTH = 316.0 / 16;
    /** Max degrees per tick the turret slews while tracking a new target. */
    public static final float SLEW_DEG_PER_TICK = 2.5f;
    /** Half-extent of the rendered model, used for frustum culling (barrel reach + margin). */
    public static final double RENDER_REACH = 22.0;
}

package com.creedpdf.orbitalrailgun.orbital;

import net.minecraft.util.math.Vec3d;

/**
 * Orbital mode tuning. Every timeline value is in ticks; the strike timeline counts from the
 * moment FIRE is pressed and is shared by the server (strike, player parking) and the client
 * (space cutscene, beam ride, impact shots), which both run off world time.
 */
public final class OrbitalConfig {
    private OrbitalConfig() {}

    /** Platform transformation in orbit (matches animation.orbital.deploy / stow, 9 s). */
    public static final int DEPLOY_TICKS = 180;
    public static final int STOW_TICKS = 180;

    /** Strike radius around the player, in blocks. */
    public static final double RANGE = 900.0;
    /** Time between orbital shots after the impact. */
    public static final int COOLDOWN_TICKS = 900;

    // ---- strike timeline (ticks from FIRE) ----
    /** The player is parked (spectator, above the target) so the target chunks stream in. */
    public static final int PARK_T = 10;
    /** Capacitor charge and nose-down manoeuvre in orbit. */
    public static final int CHARGE_END_T = 60;
    /** Beam ride through space ends, the world cinematic starts high above the target. */
    public static final int WORLD_PHASE_T = 100;
    /** Beam head reaches the ground. */
    public static final int IMPACT_T = 140;
    /** Sustained burn and bore after the impact. */
    public static final int BURN_TICKS = 70;
    /** Cinematic ends, the player is restored. */
    public static final int CINE_END_T = 245;

    /** How far up the beam's visible origin sits along the incoming direction (blocks). */
    public static final double BEAM_LENGTH = 1100.0;
    /** Parking height above the target while the cinematic runs. */
    public static final double PARK_HEIGHT = 60.0;

    public static final float IMPACT_POWER_MUL = 1.7f;
    public static final double BORE_DEPTH = 150.0;
    public static final double BORE_RADIUS = 6.0;
    public static final double BORE_SPEED = 3.2;

    /** Direction the beam travels (from orbit to the ground): almost vertical, a slight slant. */
    public static final Vec3d BEAM_DIR = new Vec3d(-0.14, -1.0, -0.07).normalize();

    public static Vec3d muzzleFor(Vec3d surface) {
        return surface.subtract(BEAM_DIR.multiply(BEAM_LENGTH));
    }
}

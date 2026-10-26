package com.creedpdf.orbitalrailgun.grs;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.space.Body;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.entity.damage.DamageType;
import net.minecraft.world.World;

/**
 * The Gungnir Railway Station (GRS): a station riding the rings of Jupiter whose "railway" is a 112-block accelerator
 * ring. The kinetic spear Gungnir laps it at the speed of light - seen from anywhere it is an orb of orange light racing
 * round the ring, with a zooming roar each time it passes you - and on command breaks from its lap, leaves through the
 * hub's barrel and comes down on Earth out of Jupiter's place in the sky, where it plants itself, 179 blocks of black
 * and orange metal (155 above ground), in the crater it made.
 *
 * <p>It is controlled from the Gungnir goggles (worn on the head), a separate controller from the ARES uplink, and it
 * strikes Earth only.</p>
 */
public final class Grs {
    private Grs() {}

    /** The station rides Jupiter's ring plane at this many Jupiter radii, on the side facing Earth. */
    public static final double RING_ORBIT = 1.62;
    /** Jupiter's faint rings, in Jupiter radii. */
    public static final double RING_IN = 1.36, RING_OUT = 1.9;

    /** The station's centre in the Space dimension. */
    public static Vec3d stationPos() {
        Body j = Body.JUPITER;
        Vec3d toEarth = Body.EARTH.center.subtract(j.center).multiply(1, 0, 1).normalize();
        return j.center.add(toEarth.multiply(j.radius * RING_ORBIT));
    }

    /**
     * Direction from the ground toward Jupiter in Earth's sky right now (the same place the sky renderer draws it), lifted
     * above the horizon when Jupiter has set - the spear always comes down out of the sky.
     */
    public static Vec3d jupiterSkyDir(World w) {
        Body b = Body.JUPITER;
        double th = Math.toRadians(b.skyTheta), tilt = Math.toRadians(b.skyTilt);
        double lx = Math.sin(tilt), ly = Math.cos(tilt) * Math.cos(th), lz = Math.cos(tilt) * Math.sin(th);
        double a = Math.toRadians(w.getSkyAngle(1f) * 360f);
        double y = ly * Math.cos(a) - lz * Math.sin(a), z = ly * Math.sin(a) + lz * Math.cos(a), x = lx;
        Vec3d d = new Vec3d(-z, y, x).normalize();
        if (d.y < 0.42) {
            Vec3d h = new Vec3d(d.x, 0, d.z);
            if (h.lengthSquared() < 1e-6) h = new Vec3d(1, 0, 0);
            h = h.normalize();
            double lift = 0.42 + Math.max(0, -d.y) * 0.3;
            d = h.multiply(Math.sqrt(1 - lift * lift)).add(0, lift, 0);
        }
        return d.normalize();
    }

    /** The planted spear leans a little toward where it came from. */
    public static Vec3d plantAxis(Vec3d skyDir) {
        return new Vec3d(0, 1, 0).multiply(0.74).add(skyDir.multiply(0.26)).normalize();
    }

    // ---- the strike timeline (ticks of the strike entity)
    /**
     * The spear breaks from its lap, slings once round Jupiter and fires for Earth. Long enough for the caller's
     * cutscene (zoom out, fly to Jupiter, the station, riding the spear, the slingshot, the shot).
     */
    public static final int RELEASE = 300;
    /** It leaves the barrel: the descent is a few ticks of light from the sky to the ground. */
    public static final int DESCENT = 6;
    public static final int IMPACT = RELEASE + DESCENT;
    /** How long it stays planted before it dissolves into light and returns to orbit. */
    public static final int PLANTED = 1100;
    public static final int RECALL_LEN = 60;
    public static final int RECALL = IMPACT + PLANTED;
    public static final int END = RECALL + RECALL_LEN;
    /** Ticks after impact before the same player can call another strike. */
    public static final int COOLDOWN = 160;
    /** The crater is carved over this many ticks after impact. */
    public static final int CARVE_TICKS = 28;

    // ---- the spear
    public static final float SPEAR_LENGTH = 179f;
    /** How deep the tip goes into the ground (the rest - over 145 blocks - stands above it). */
    public static final float PLANT_DEPTH = 24f;
    /** The lap: ticks per lap of the ring (it is light: what you see is the strobe of its passes). */
    public static final int LAP_TICKS = 34;

    // ---- power
    public static final int MIN_YIELD = 1, MAX_YIELD = 10;
    private static final int[] RADIUS = {0, 5, 8, 11, 15, 19, 24, 30, 37, 45};
    private static final String[] NAMES = {"SURGICAL", "PIN", "BREACH", "BREAKER", "SHATTER", "QUAKE", "RUIN", "CATACLYSM", "EXTINCTION", "RAGNAROK"};
    public static final double RANGE = 1024;

    public static final RegistryKey<DamageType> DAMAGE = RegistryKey.of(RegistryKeys.DAMAGE_TYPE, RailgunMod.id("gungnir"));

    public static int clampYield(int y) {
        return MathHelper.clamp(y, MIN_YIELD, MAX_YIELD);
    }

    /** Crater radius in blocks (0 = no terrain damage). */
    public static int radius(int power) {
        return RADIUS[clampYield(power) - 1];
    }

    public static String yieldName(int power) {
        return NAMES[clampYield(power) - 1];
    }

    /** The radius in which living things are hit. */
    public static double blastRadius(int power) {
        return Math.max(9, radius(power) * 1.45 + 7);
    }

    public static float damage(int power) {
        return 40f + clampYield(power) * 26f;
    }

    public static boolean earth(World w) {
        return w.getRegistryKey() == World.OVERWORLD;
    }

    public static boolean wearing(LivingEntity e) {
        return e != null && e.getEquippedStack(EquipmentSlot.HEAD).isOf(RailgunMod.GUNGNIR_GOGGLES);
    }
}

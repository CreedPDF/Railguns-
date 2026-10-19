package com.creedpdf.orbitalrailgun.space;

import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * Every celestial body in the Space dimension. Positions/radii are in blocks of the Space
 * dimension (bodies are rendered, not built from blocks). Landable bodies map their surface to
 * their own dimension with an azimuthal-equidistant projection around the top pole, so where you
 * enter the atmosphere decides where you come down, and flying back up returns you above the
 * same spot.
 */
public enum Body {
    //      id        center x, y, z               radius  atmo   gravity  land scale  land offset x, z   sky theta, tilt, size(deg)
    EARTH("earth",       0, -2700,      0,          2400,  180,   1.00f,   8.0,             0,      0,    0f,   0f,  0f),
    MOON("moon",      5200,  1500,  -3000,           650,   70,   0.17f,   4.0,             0,      0,  180f,   0f,  0f),
    MARS("mars",     -7000,   600,   5200,           800,  140,   0.38f,   4.0,         30000,      0,  148f,   3f,  0.9f),
    VENUS("venus",    8200, -1400,   7400,          1100,  220,   0.90f,   4.0,        -30000,  20000,   38f,  -2f,  1.15f),
    EUROPA("europa", -9200,  1700,  -8200,           420,   60,   0.13f,   4.0,         12000, -40000,  206f,   4f,  0.35f),
    JUPITER("jupiter", -15000, 2600, -13000,        4200,  420,   0f,      0.0,             0,      0,  200f,   1.5f, 1.7f),
    SATURN("saturn",  19000,  3500, -15000,         3500,  380,   0f,      0.0,             0,      0,  236f,  -3f,  1.3f);

    public final String id;
    public final Vec3d center;
    public final double radius, atmosphere;
    public final float gravity;
    public final double landScale, landOffsetX, landOffsetZ;
    /** Position in the overworld sky: angle along the ecliptic from the sun, tilt off it, apparent radius. */
    public final float skyTheta, skyTilt, skySize;

    Body(String id, double x, double y, double z, double radius, double atmosphere, float gravity, double landScale,
         double landOffsetX, double landOffsetZ, float skyTheta, float skyTilt, float skySize) {
        this.id = id;
        this.center = new Vec3d(x, y, z);
        this.radius = radius;
        this.atmosphere = atmosphere;
        this.gravity = gravity;
        this.landScale = landScale;
        this.landOffsetX = landOffsetX;
        this.landOffsetZ = landOffsetZ;
        this.skyTheta = skyTheta;
        this.skyTilt = skyTilt;
        this.skySize = skySize;
    }

    public boolean landable() {
        return landScale > 0;
    }

    public boolean gasGiant() {
        return !landable();
    }

    /** The dimension you land in (Earth = the overworld), or null for gas giants. */
    @Nullable
    public RegistryKey<World> landDim() {
        return switch (this) {
            case EARTH -> World.OVERWORLD;
            case MOON -> SpaceDims.MOON;
            case MARS -> SpaceDims.MARS;
            case VENUS -> SpaceDims.VENUS;
            case EUROPA -> SpaceDims.EUROPA;
            default -> null;
        };
    }

    /** ARES-0 can blow up everything but home. */
    public boolean destroyable() {
        return this != EARTH;
    }

    public String displayName() {
        return switch (this) {
            case EARTH -> "EARTH";
            case MOON -> "THE MOON";
            case MARS -> "MARS";
            case VENUS -> "VENUS";
            case EUROPA -> "EUROPA";
            case JUPITER -> "JUPITER";
            case SATURN -> "SATURN";
        };
    }

    // ---- surface <-> space projection -------------------------------------------------------------

    /** Space-dimension point -> (x, z) in the body's land dimension. */
    public double[] spaceToSurface(Vec3d p) {
        Vec3d d = p.subtract(center).normalize();
        double theta = Math.acos(MathHelper.clamp(d.y, -1.0, 1.0));
        double phi = Math.atan2(d.z, d.x);
        double r = theta * radius * landScale;
        return new double[]{landOffsetX + r * Math.cos(phi), landOffsetZ + r * Math.sin(phi)};
    }

    /** (x, z) in the land dimension -> a point above the surface in space, at {@code altitude}. */
    public Vec3d surfaceToSpace(double x, double z, double altitude) {
        return center.add(surfaceNormal(x, z).multiply(radius + altitude));
    }

    public Vec3d surfaceNormal(double x, double z) {
        double dx = (x - landOffsetX) / landScale, dz = (z - landOffsetZ) / landScale;
        double r = Math.sqrt(dx * dx + dz * dz);
        double theta = Math.min(Math.PI, r / radius);
        double phi = Math.atan2(dz, dx);
        return new Vec3d(Math.sin(theta) * Math.cos(phi), Math.cos(theta), Math.sin(theta) * Math.sin(phi));
    }

    /**
     * Direction of this body in the overworld sky at the given sky angle (same frame vanilla uses for
     * the sun: rotate -90 about Y, then skyAngle*360 about X; the sun sits at +Y in that frame).
     */
    public Vec3d skyDirection(float skyAngle) {
        double th = Math.toRadians(skyTheta), tilt = Math.toRadians(skyTilt);
        // local frame: sun at +Y, ecliptic in the Y-Z plane, tilt along X
        double lx = Math.sin(tilt), ly = Math.cos(tilt) * Math.cos(th), lz = Math.cos(tilt) * Math.sin(th);
        double a = skyAngle * Math.PI * 2;
        // rotate about X by a
        double y1 = ly * Math.cos(a) - lz * Math.sin(a);
        double z1 = ly * Math.sin(a) + lz * Math.cos(a);
        double x1 = lx;
        // rotate about Y by -90 deg: (x, z) -> (-z, x)... R_y(-90): x' = x cos - z sin? use JOML convention
        double ang = Math.toRadians(-90);
        double x2 = x1 * Math.cos(ang) + z1 * Math.sin(ang);
        double z2 = -x1 * Math.sin(ang) + z1 * Math.cos(ang);
        return new Vec3d(x2, y1, z2).normalize();
    }

    public static final Body[] ALL = values();

    @Nullable
    public static Body byLandDim(RegistryKey<World> key) {
        for (Body b : ALL) if (key.equals(b.landDim())) return b;
        return null;
    }
}

package com.creedpdf.orbitalrailgun.legion;

import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Where every weapon of a swarm belongs, as pure functions of time - shared by the server (which owns the truth) and
 * the client (which evaluates them every frame from the owner's interpolated position, so the swarm stays glued to a
 * running player with no lag or jitter). Each returns a point, and {@code aim} the direction the blade's tip points.
 *
 * <p>Inputs: the owner's feet position and height, their body and head yaw (degrees, Minecraft convention: yaw 0 faces
 * +Z), the weapon's place in the swarm and the swarm size, the time in ticks (fractional on the client) and the
 * weapon's entity id (a per-blade phase so no two bob in lockstep).</p>
 */
public final class SwarmPaths {
    private SwarmPaths() {}

    public record Pose(Vec3d pos, Vec3d aim) {}

    /** Length of a Blade Storm, in ticks. */
    public static final int STORM_TICKS = 70;

    static Vec3d fwd(float yawDeg) {
        float r = yawDeg * MathHelper.RADIANS_PER_DEGREE;
        return new Vec3d(-MathHelper.sin(r), 0, MathHelper.cos(r));
    }

    static Vec3d right(float yawDeg) {
        float r = yawDeg * MathHelper.RADIANS_PER_DEGREE;
        return new Vec3d(-MathHelper.cos(r), 0, -MathHelper.sin(r));
    }

    public static Pose idle(SwarmFormation f, Vec3d feet, float height, float bodyYaw, float headYaw, int order, int size, double time, int id) {
        size = Math.max(1, size);
        double bob = 0.16 * Math.sin(time * 0.09 + id * 1.7) + 0.06 * Math.sin(time * 0.031 + id);
        switch (f) {
            case CROWN -> {
                double ang = time * 0.03 + order * MathHelper.TAU / size;
                double r = 0.5 + 0.09 * size;
                double c = Math.cos(ang), s = Math.sin(ang);
                Vec3d p = feet.add(c * r, height + 0.6 + bob * 0.5, s * r);
                return new Pose(p, new Vec3d(c * 0.35, 1, s * 0.35).normalize());
            }
            case WINGS -> {
                Vec3d b = fwd(bodyYaw).negate(), side = right(bodyYaw);
                boolean left = (order & 1) == 0;
                int k = order / 2, m = Math.max(1, (size + (left ? 1 : 0)) / 2);
                double flap = Math.toRadians(7) * Math.sin(time * 0.12 + (left ? 0 : 0.3));
                double th = Math.toRadians(-12 + (m <= 1 ? 30 : 85.0 * k / (m - 1))) + flap;
                Vec3d out = side.multiply(left ? -1 : 1);
                Vec3d dir = out.multiply(Math.cos(th)).add(0, Math.sin(th), 0).add(b.multiply(0.25)).normalize();
                Vec3d anchor = feet.add(0, height * 0.74, 0).add(b.multiply(0.32));
                Vec3d p = anchor.add(dir.multiply(0.75 + 0.28 * k)).add(b.multiply(0.12 * k)).add(0, bob * 0.3, 0);
                return new Pose(p, dir);
            }
            case VANGUARD -> {
                Vec3d f0 = fwd(headYaw);
                double spread = size <= 1 ? 0 : Math.toRadians(-55 + 110.0 * order / (size - 1));
                Vec3d dir = f0.rotateY((float) -spread);
                double tier = (order % 2) * 0.35;
                Vec3d p = feet.add(dir.multiply(1.7 + 0.08 * size)).add(0, height * 0.72 + tier + bob * 0.5, 0);
                return new Pose(p, f0.add(0, 0.05, 0).normalize());
            }
            default -> {
                double ang = time * 0.055 + order * MathHelper.TAU / size;
                double r = 1.5 + 0.17 * size;
                double c = Math.cos(ang), s = Math.sin(ang);
                Vec3d p = feet.add(c * r, height * 0.62 + bob, s * r);
                return new Pose(p, new Vec3d(-s, 0.22, c).normalize());
            }
        }
    }

    /**
     * A Blade Storm, {@code t} ticks in: the swarm whips round its owner in a widening, rising vortex (out to ~6.5
     * blocks at its height, then back in), every blade at its own height and speed.
     */
    public static Pose storm(Vec3d feet, float height, int order, int size, double t, int id) {
        size = Math.max(1, size);
        double phase = MathHelper.clamp(t / STORM_TICKS, 0, 1);
        double r = 1.4 + 5.2 * Math.sin(Math.PI * phase);
        double ang = order * MathHelper.TAU / size + t * (0.42 + 0.04 * (order % 3));
        double y = height * 0.35 + (order % 3) * 0.55 + 0.5 * Math.sin(t * 0.21 + id) + 1.2 * Math.sin(Math.PI * phase);
        double c = Math.cos(ang), s = Math.sin(ang);
        return new Pose(feet.add(c * r, y, s * r), new Vec3d(-s, 0.1, c).normalize());
    }
}

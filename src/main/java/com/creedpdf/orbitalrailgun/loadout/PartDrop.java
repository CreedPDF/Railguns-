package com.creedpdf.orbitalrailgun.loadout;

import com.creedpdf.orbitalrailgun.loadout.Loadout.Option;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Orbital part drops. When a configured railgun finishes deploying it enters ARMING: every
 * attachment part is fired down from orbital storage on its own. Each part free-falls nose
 * first, burns its retro thrusters, vectors into a hover point beside its mount while turning to
 * its final orientation, extends four docking arms, slides in, clamps on, and folds its arms
 * away. When the railgun packs up the parts undock and boost back to orbit.
 *
 * <p>Shared by server (timing, sounds, sparks) and client (bone animation, thruster particles).
 * Bone pivots and approach directions match tools/blender/build_models.py (DROP_APPROACH).</p>
 */
public final class PartDrop {
    private PartDrop() {}

    public static final int DESCENT = 34, HOVER_END = 52, APPROACH_END = 64, DOCK_END = 70, ARMS_END = 84;
    public static final int STAGGER = 10;
    public static final float HOVER = 56f;      // px from the mount, along the approach axis
    public static final float DROP = 2400f;     // px (150 blocks) above the hover point
    public static final int UNDOCK_END = 44;

    /** One flying part. Pivot in model px; turret parts yaw with the turret. */
    public record Entry(Option option, String bone, float px, float py, float pz, float ax, float ay, float az,
                        boolean turret, int start) {}

    private record Mount(String bone, float px, float py, float pz, float ax, float ay, float az, boolean turret) {}

    private static List<Mount> mounts(Option o) {
        List<Mount> m = new ArrayList<>();
        switch (o.category) {
            case MUZZLE -> m.add(new Mount(o.bone(), 0, 118, -316, 0, 0, -1, true));
            case AMMO -> m.add(new Mount(o.bone(), 28, 118, 28, 1, 0, 0, true));
            case MODULE -> {
                switch (o) {
                    case CAPACITOR -> m.add(new Mount(o.bone(), 0, 72, 0, 0, 1, 0, true));
                    case COOLANT -> m.add(new Mount(o.bone(), 0, 118, 0, 0, 1, 0, true));
                    case OVERCLOCK -> m.add(new Mount(o.bone(), 0, 118, 0, 0, 1, 0, true));
                    case TARGETING -> {
                        m.add(new Mount(o.bone(), 0, 72, 0, 0, 1, 0, true));
                        m.add(new Mount("opt_targeting__laser", -26, 107, -50, -1, 0, 0, true));
                    }
                    case SHIELD -> {
                        m.add(new Mount("opt_shield__a", 76, 112, -76, 0, 1, 0, false));
                        m.add(new Mount("opt_shield__b", -76, 112, 76, 0, 1, 0, false));
                    }
                    case SENTRY -> {
                        m.add(new Mount("opt_sentry__a", 76, 112, 76, 0, 1, 0, false));
                        m.add(new Mount("opt_sentry__b", -76, 112, -76, 0, 1, 0, false));
                    }
                    case THRUSTER -> m.add(new Mount(o.bone(), 0, 0, 0, 0, 1, 0, false));
                    case SEISMIC -> {
                        m.add(new Mount("opt_seismic__pp", 88, 4, 88, 0, 1, 0, false));
                        m.add(new Mount("opt_seismic__pn", 88, 4, -88, 0, 1, 0, false));
                        m.add(new Mount("opt_seismic__np", -88, 4, 88, 0, 1, 0, false));
                        m.add(new Mount("opt_seismic__nn", -88, 4, -88, 0, 1, 0, false));
                    }
                    case AUTOLOADER -> m.add(new Mount(o.bone(), 0, 149, 28, 0, 1, 0, true));
                    case AFTERBURN -> m.add(new Mount(o.bone(), 0, 118, -180, 0, -1, 0, true));
                    default -> { }
                }
            }
        }
        return m;
    }

    public static List<Entry> schedule(Loadout l) {
        List<Option> parts = new ArrayList<>();
        if (l.muzzle() != null) parts.add(l.muzzle());
        if (l.ammo() != null) parts.add(l.ammo());
        parts.addAll(l.modules());
        List<Entry> out = new ArrayList<>();
        int t = 0;
        for (Option o : parts) {
            for (Mount m : mounts(o)) {
                out.add(new Entry(o, m.bone, m.px, m.py, m.pz, m.ax, m.ay, m.az, m.turret, t));
                t += STAGGER;
            }
        }
        return out;
    }

    public static int armingTicks(Loadout l) {
        List<Entry> s = schedule(l);
        return s.isEmpty() ? 0 : s.get(s.size() - 1).start() + ARMS_END + 6;
    }

    /** Pose of a part at local time t (ticks since its launch). */
    public record Pose(boolean visible, float x, float y, float z, float rotX, float rotY, float rotZ, float arms) {
        public static final Pose HIDDEN = new Pose(false, 0, 0, 0, 0, 0, 0, 0);
        public static final Pose DOCKED = new Pose(true, 0, 0, 0, 0, 0, 0, 0);
    }

    private static float ease(float f) {
        f = MathHelper.clamp(f, 0f, 1f);
        return f * f * (3f - 2f * f);
    }

    /** Free-flight pose; the tumble tips the part about the axis that turns its approach line to vertical. */
    private static Pose pose(Entry e, float x, float y, float z, float tumble, float spin, float arms) {
        return new Pose(true, x, y, z, e.az() != 0 ? tumble * Math.signum(e.az()) : 0f, spin,
                e.ax() != 0 ? -tumble * Math.signum(e.ax()) : 0f, arms);
    }

    public static Pose arrive(Entry e, float t) {
        if (t < 0) return Pose.HIDDEN;
        float hx = e.ax() * HOVER, hy = e.ay() * HOVER, hz = e.az() * HOVER;
        boolean horizontal = e.ay() == 0;
        float nose = horizontal ? MathHelper.HALF_PI : 0f;
        // Parts that dock from below come down beside the hull, then slide under it while hovering.
        float side = e.ay() < 0 ? 84f : 0f;
        if (t < DESCENT) {
            float f = t / DESCENT;
            float fall = DROP * (float) Math.pow(1 - f, 2.2);
            return pose(e, hx + side, hy + fall, hz, nose, (1 - f) * (1 - f) * MathHelper.TAU * 2, 0);
        }
        if (t < HOVER_END) {
            float g = ease((t - DESCENT) / (HOVER_END - DESCENT));
            float bob = 3f * MathHelper.sin((t - DESCENT) * 0.35f) * (1 - g);
            float arms = ease((t - (HOVER_END - 6)) / 6f);
            return pose(e, hx + side * (1 - g), hy + bob, hz, nose * (1 - g), 0, arms);
        }
        if (t < APPROACH_END) {
            float a = ease((t - HOVER_END) / (APPROACH_END - HOVER_END));
            return new Pose(true, hx * (1 - a), hy * (1 - a), hz * (1 - a), 0, 0, 0, 1);
        }
        if (t < DOCK_END) {
            float p = MathHelper.sin(MathHelper.PI * (t - APPROACH_END) / (DOCK_END - APPROACH_END)) * -1.2f;
            return new Pose(true, e.ax() * p, e.ay() * p, e.az() * p, 0, 0, 0, 1);
        }
        if (t < ARMS_END) return new Pose(true, 0, 0, 0, 0, 0, 0, 1 - ease((t - DOCK_END) / (ARMS_END - DOCK_END)));
        return Pose.DOCKED;
    }

    /** Pack-up: arms out, pull away to the hover point, boost straight back up to orbit. */
    public static Pose depart(Entry e, float t) {
        if (t < 0) return Pose.DOCKED;
        float hx = e.ax() * HOVER, hy = e.ay() * HOVER, hz = e.az() * HOVER;
        if (t < 6) return new Pose(true, 0, 0, 0, 0, 0, 0, ease(t / 6f));
        if (t < 14) {
            float a = ease((t - 6) / 8f);
            return new Pose(true, hx * a, hy * a, hz * a, 0, 0, 0, 1 - ease((t - 10) / 4f));
        }
        if (t < UNDOCK_END) {
            float f = (t - 14) / (UNDOCK_END - 14);
            float side = e.ay() < 0 ? 84f * ease(f * 4f) : 0f;
            return new Pose(true, hx + side, hy + DROP * (float) Math.pow(Math.max(0f, f - 0.15f) / 0.85f, 2.4), hz, 0, f * f * MathHelper.TAU, 0, 0);
        }
        return Pose.HIDDEN;
    }

    /** Stagger for the pack-up departures (small, so the barrel can retract in time). */
    public static int departStart(Entry e) {
        return e.start() / STAGGER * 2;
    }

    /** World position of a model-space point (px) relative to the railgun, with turret yaw. */
    public static Vec3d world(Vec3d base, Entry e, float yawRad, float ox, float oy, float oz) {
        double x = (e.px() + ox) / 16.0, y = (e.py() + oy) / 16.0, z = -(e.pz() + oz) / 16.0;
        if (e.turret()) {
            double c = Math.cos(yawRad), s = Math.sin(yawRad);
            double rx = x * c + z * s, rz = -x * s + z * c;
            x = rx;
            z = rz;
        }
        return base.add(x, y, z);
    }
}

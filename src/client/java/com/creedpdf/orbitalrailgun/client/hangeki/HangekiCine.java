package com.creedpdf.orbitalrailgun.client.hangeki;

import com.creedpdf.orbitalrailgun.hangeki.HangekiNet;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.Random;

/**
 * The parry cutscene. While the local player's stance plays out (in slow motion) the camera leaves their eyes,
 * swings out to their right side and slowly orbits them, framing the bearer settling into the stance and the threat
 * hanging in the air in front of them; in the last moments it pushes back in over the shoulder. When the stance ends
 * (time snaps back, the hit lands) it cuts straight back to first person, on the impact frames, and shakes.
 */
public final class HangekiCine {
    private HangekiCine() {}

    private static boolean active;
    private static Vec3d focus = Vec3d.ZERO;
    private static float shake;
    private static final Random RNG = new Random();

    static void beginStance(Vec3d f) {
        active = true;
        focus = f;
    }

    static void endStance() {
        active = false;
    }

    static void reset() {
        active = false;
        shake = 0;
    }

    public static void kick(float s) {
        shake = Math.max(shake, s);
    }

    public static boolean active() {
        return active && MinecraftClient.getInstance().player != null;
    }

    /** Each client tick: the bearer turns to face what they are parrying; the shake dies away. */
    static void tick(MinecraftClient mc) {
        shake *= 0.8f;
        if (shake < 0.002f) shake = 0;
        ClientPlayerEntity p = mc.player;
        if (p == null) return;
        ParryClient.Stance s = ParryClient.stance(p.getId());
        if (s == null || (s.phase() != HangekiNet.PH_STANCE && s.phase() != HangekiNet.PH_SLOW)) return;
        if (s.phase() == HangekiNet.PH_SLOW) return;
        Vec3d d = s.focus().subtract(p.getEyePos());
        if (d.lengthSquared() < 1e-4) return;
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pitch = (float) Math.toDegrees(-Math.atan2(d.y, Math.hypot(d.x, d.z)));
        p.setYaw(p.getYaw() + MathHelper.wrapDegrees(yaw - p.getYaw()) * 0.45f);
        p.setPitch(p.getPitch() + (pitch - p.getPitch()) * 0.45f);
        p.setHeadYaw(p.getYaw());
        p.setBodyYaw(p.getYaw());
    }

    /** {x, y, z, yaw, pitch} for the camera this frame, or null to leave it alone. */
    public static double[] camera(float pt) {
        if (!active()) return null;
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity p = mc.player;
        ParryClient.Stance s = ParryClient.stance(p.getId());
        if (s == null || s.phase() != HangekiNet.PH_STANCE) return null;
        float u = ParryClient.progress(s, pt);
        Vec3d eye = p.getCameraPosVec(pt);
        Vec3d f = focus.subtract(eye);
        Vec3d h = new Vec3d(f.x, 0, f.z);
        h = h.lengthSquared() < 1e-6 ? p.getRotationVec(pt).multiply(1, 0, 1).normalize() : h.normalize();
        Vec3d side = new Vec3d(-h.z, 0, h.x);
        float e = ParryClient.smooth(Math.min(1f, u / 0.3f));
        double a = Math.toRadians(-18 + 42 * u);
        // orbit: out to the right, a little ahead, slightly low (a heroic angle), drifting round the front
        Vec3d off = side.multiply(2.8 * Math.cos(a)).add(h.multiply(2.8 * Math.sin(a) + 0.3)).add(0, -0.15, 0);
        Vec3d pos = eye.add(off.multiply(e));
        Vec3d look = eye.add(h.multiply(1.0)).add(0, -0.2, 0).lerp(focus, 0.3);
        if (u > 0.84f) {
            // push in over the shoulder for the hit
            float k = ParryClient.smooth((u - 0.84f) / 0.16f);
            Vec3d shoulder = eye.add(side.multiply(0.55)).add(h.multiply(-0.7)).add(0, 0.12, 0);
            pos = pos.lerp(shoulder, k);
            look = look.lerp(focus, k * 0.7);
        }
        Vec3d v = look.subtract(pos);
        float yaw = (float) Math.toDegrees(Math.atan2(-v.x, v.z));
        float pitch = (float) Math.toDegrees(-Math.atan2(v.y, Math.hypot(v.x, v.z)));
        float py = p.getYaw(pt), pp = p.getPitch(pt);
        yaw = py + MathHelper.wrapDegrees(yaw - py) * e;
        pitch = pp + (pitch - pp) * e;
        return new double[]{pos.x, pos.y, pos.z, yaw, pitch};
    }

    /** The impact shake, as a small camera offset. */
    public static Vec3d shake() {
        if (shake <= 0) return Vec3d.ZERO;
        double s = shake * 0.35;
        return new Vec3d((RNG.nextDouble() - 0.5) * s, (RNG.nextDouble() - 0.5) * s, (RNG.nextDouble() - 0.5) * s);
    }
}

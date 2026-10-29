package com.creedpdf.orbitalrailgun.client.hangeki;

import com.creedpdf.orbitalrailgun.hangeki.HangekiNet;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.Random;

/**
 * The parry cutscene, shot by shot. Nothing is rushed; everything is on screen before it happens.
 * <ol>
 *   <li><b>The side shot</b> (time slows): the camera eases out of the bearer's eyes to a wide side-on angle,
 *   perpendicular to the line of attack and far enough out to hold both the bearer and the threat, and drifts slowly
 *   along that line as the arrow / fireball / missile / railgun bolt closes in.</li>
 *   <li><b>The stance</b> (parry pressed): from the side shot the camera swings round low to a front three-quarter
 *   hero angle on the bearer as they sink into the stance and the ward snaps up in front of the blade, and pushes in
 *   slowly.</li>
 *   <li><b>The hit</b>: it swings behind the bearer's shoulder and looks straight down the line at the threat as it
 *   reaches the blade; at the hit it cuts back to first person on the impact frames, with a shake.</li>
 * </ol>
 * Shots blend into one another (eased), framed on the live position of the threat.
 */
public final class HangekiCine {
    private HangekiCine() {}

    private static boolean active;
    private static byte phase;
    private static Vec3d focus = Vec3d.ZERO;
    private static long slowAt, stanceAt;
    private static float shake;
    private static final Random RNG = new Random();
    /** The last shot's camera, held while the stance waits for the hit to arrive. */
    private static double[] last;

    static void begin(byte ph, Vec3d f) {
        long now = System.currentTimeMillis();
        if (ph == HangekiNet.PH_SLOW && !(active && phase == HangekiNet.PH_SLOW)) slowAt = now;
        if (ph == HangekiNet.PH_STANCE) {
            stanceAt = now;
            if (!active) slowAt = now - 2000;
        }
        active = true;
        phase = ph;
        focus = f;
    }

    static void endStance() {
        active = false;
        last = null;
    }

    static void reset() {
        active = false;
        shake = 0;
        last = null;
    }

    public static void kick(float s) {
        shake = Math.max(shake, s);
    }

    public static boolean active() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!active || mc.player == null) return false;
        ParryClient.Stance s = ParryClient.stance(mc.player.getId());
        if (s == null || (s.phase() != HangekiNet.PH_SLOW && s.phase() != HangekiNet.PH_STANCE)) {
            active = false;
            return false;
        }
        return true;
    }

    /** Each client tick: the bearer turns to face what is coming; the shake dies away. */
    static void tick(MinecraftClient mc) {
        shake *= 0.8f;
        if (shake < 0.002f) shake = 0;
        ClientPlayerEntity p = mc.player;
        if (p == null || !active()) return;
        ParryClient.Stance s = ParryClient.stance(p.getId());
        Vec3d d = ParryClient.threat(1f, s.focus()).subtract(p.getEyePos());
        if (d.lengthSquared() < 1e-4) return;
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pitch = (float) Math.toDegrees(-Math.atan2(d.y, Math.hypot(d.x, d.z)));
        float k = s.phase() == HangekiNet.PH_STANCE ? 0.4f : 0.15f;
        p.setYaw(p.getYaw() + MathHelper.wrapDegrees(yaw - p.getYaw()) * k);
        p.setPitch(p.getPitch() + (pitch - p.getPitch()) * k);
        p.setHeadYaw(p.getYaw());
        p.setBodyYaw(p.getYaw());
    }

    private static float ease(float x) {
        return ParryClient.smooth(MathHelper.clamp(x, 0f, 1f));
    }

    /** {x, y, z, yaw, pitch} for the camera this frame, or null to leave it alone. */
    public static double[] camera(float pt) {
        if (!active()) return null;
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity p = mc.player;
        ParryClient.Stance s = ParryClient.stance(p.getId());
        long now = System.currentTimeMillis();
        Vec3d eye = p.getCameraPosVec(pt);
        Vec3d body = p.getLerpedPos(pt).add(0, p.getHeight() * 0.55, 0);
        Vec3d threat = ParryClient.threat(pt, s.focus());
        Vec3d line = threat.subtract(body);
        double dist = Math.max(1.5, line.length());
        Vec3d h = new Vec3d(line.x, 0, line.z);
        h = h.lengthSquared() < 1e-6 ? p.getRotationVec(pt).multiply(1, 0, 1).normalize() : h.normalize();
        Vec3d side = new Vec3d(-h.z, 0, h.x);

        // shot A - the side shot: square to the line of attack, wide enough for both ends, drifting slowly
        float drift = (now - slowAt) / 1000f;
        Vec3d mid = body.lerp(threat, 0.42);
        double out = 3.2 + Math.min(dist, 14) * 0.72;
        Vec3d camA = mid.add(side.multiply(out)).add(0, 0.45 + 0.04 * drift, 0).add(h.multiply(-0.35 - 0.12 * drift));
        Vec3d lookA = mid.add(0, -0.1, 0);

        // shot B - the hero angle: low, front three-quarter on the bearer and the ward
        Vec3d heroDir = h.multiply(Math.cos(Math.toRadians(38))).add(side.multiply(Math.sin(Math.toRadians(38))));
        Vec3d camB = body.add(heroDir.multiply(3.4)).add(0, -0.45, 0);
        Vec3d lookB = body.add(h.multiply(0.9)).add(0, 0.15, 0);

        // shot C - over the shoulder, straight down the line at what is about to hit
        Vec3d camC = eye.add(h.multiply(-1.35)).add(side.multiply(0.75)).add(0, 0.25, 0);
        Vec3d lookC = threat;

        Vec3d pos, look;
        if (s.phase() == HangekiNet.PH_SLOW) {
            float e = ease((now - slowAt) / 900f);
            pos = eye.lerp(camA, e);
            look = eye.add(p.getRotationVec(pt).multiply(5)).lerp(lookA, e);
        } else {
            float u = (now - stanceAt) / 1000f;   // seconds into the stance (real time)
            float ab = ease(u / 0.9f);
            pos = camA.lerp(camB, ab);
            look = lookA.lerp(lookB, ab);
            // a slow push in on the hero angle
            float push = ease((u - 0.9f) / 1.0f);
            pos = pos.lerp(body.add(heroDir.multiply(2.5)).add(0, -0.3, 0), push * 0.6);
            // then round behind the shoulder for the hit
            float bc = ease((u - 1.7f) / 0.6f);
            pos = pos.lerp(camC, bc);
            look = look.lerp(lookC, bc);
        }
        Vec3d v = look.subtract(pos);
        float yaw = (float) Math.toDegrees(Math.atan2(-v.x, v.z));
        float pitch = (float) Math.toDegrees(-Math.atan2(v.y, Math.hypot(v.x, v.z)));
        last = new double[]{pos.x, pos.y, pos.z, yaw, pitch};
        return last;
    }

    /** The impact shake, as a small camera offset. */
    public static Vec3d shake() {
        if (shake <= 0) return Vec3d.ZERO;
        double s = shake * 0.35;
        return new Vec3d((RNG.nextDouble() - 0.5) * s, (RNG.nextDouble() - 0.5) * s, (RNG.nextDouble() - 0.5) * s);
    }
}

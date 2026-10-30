package com.creedpdf.orbitalrailgun.client.astraea;

import com.creedpdf.orbitalrailgun.astraea.Timeline;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * ASTRAEA's camera, for the bearer (the cutscenes can be skipped with the ability key, and switched off altogether with
 * their own key; with them off you watch it all from your own eyes - look up).
 * <p>
 * <b>Ascension</b>: a low hero shot as the bearer lifts the lance overhead; behind and below as the rift tears open
 * high above; from beneath, rising slowly, as the star inside it pulses brighter; wide and side-on as the light comes
 * down; close in front, circling, as the bearer drinks it in; the impact frames; pulling back as the rift seals.
 * <p>
 * <b>The throw</b>: low in front as the lance is hurled up, tilting after it; then space ({@link StrikeCine}); then
 * high over the chasm as it rips open across the land; then low in front, looking up, as the lance falls back into
 * the bearer's hand.
 */
public final class AstraeaCine {
    private AstraeaCine() {}

    private static boolean enabled = true;
    private static long skippedRitual = Long.MIN_VALUE, skippedStrike = Long.MIN_VALUE;
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("orbital_railgun_astraea.txt");

    static void load() {
        try {
            if (Files.exists(FILE)) enabled = !Files.readString(FILE).trim().equals("cutscenes=off");
        } catch (Exception ignored) {
        }
    }

    static boolean toggle() {
        enabled = !enabled;
        try {
            Files.writeString(FILE, enabled ? "cutscenes=on" : "cutscenes=off");
        } catch (Exception ignored) {
        }
        return enabled;
    }

    public static boolean enabled() {
        return enabled;
    }

    /** The ability key during a cutscene skips it. Returns true if there was one to skip. */
    static boolean skip() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return false;
        if (ritualFilming()) {
            skippedRitual = AstraeaState.RITUALS.get(mc.player.getId()).start();
            return true;
        }
        if (strikeFilming()) {
            skippedStrike = AstraeaState.strike.start();
            return true;
        }
        return false;
    }

    static boolean ritualFilming() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!enabled || mc.player == null) return false;
        AstraeaState.Ritual r = AstraeaState.RITUALS.get(mc.player.getId());
        if (r == null || r.start() == skippedRitual) return false;
        double t = AstraeaState.ritualT(r.player(), 0);
        return t >= 0 && t < Timeline.R_END;
    }

    public static boolean strikeFilming() {
        MinecraftClient mc = MinecraftClient.getInstance();
        AstraeaState.Strike s = AstraeaState.strike;
        if (!enabled || mc.player == null || s == null || !s.mine() || s.start() == skippedStrike) return false;
        double t = AstraeaState.strikeT(0);
        return t >= 0 && t < Timeline.S_END;
    }

    public static boolean active() {
        return ritualFilming() || strikeFilming();
    }

    /** In space (the world is covered): the HUD and hand are hidden, the world needn't be looked at. */
    public static boolean inSpace(float pt) {
        return strikeFilming() && StrikeCine.showing(AstraeaState.strikeT(pt));
    }

    // ------------------------------------------------------------------------------------------- shots

    private record Shot(Vec3d eye, Vec3d look) {}

    private static float smooth(double x) {
        float k = (float) MathHelper.clamp(x, 0, 1);
        return k * k * (3 - 2 * k);
    }

    /** Camera {x, y, z, yaw, pitch} or null. */
    public static double[] camera(float pt) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity p = mc.player;
        if (p == null) return null;
        Shot own = new Shot(p.getCameraPosVec(pt), p.getCameraPosVec(pt).add(p.getRotationVec(pt)));
        Shot s = null;
        if (ritualFilming()) {
            AstraeaState.Ritual r = AstraeaState.RITUALS.get(p.getId());
            s = ritual(r, AstraeaState.ritualT(r.player(), pt), own);
        } else if (strikeFilming()) {
            s = strike(AstraeaState.strike, AstraeaState.strikeT(pt), own, pt);
        }
        if (s == null) return null;
        Vec3d eye = s.eye().add(AstraeaClient.shake(pt));
        Vec3d d = s.look().subtract(s.eye());
        double h = Math.sqrt(d.x * d.x + d.z * d.z);
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.max(1e-6, h)));
        if (h < 1e-4) yaw = p.getYaw(pt);
        return new double[]{eye.x, eye.y, eye.z, yaw, MathHelper.clamp(pitch, -89.9f, 89.9f)};
    }

    private static Shot blend(Shot a, Shot b, float k) {
        return new Shot(a.eye().lerp(b.eye(), k), a.look().lerp(b.look(), k));
    }

    private static Vec3d fwd(float yaw) {
        double r = Math.toRadians(yaw);
        return new Vec3d(-Math.sin(r), 0, Math.cos(r));
    }

    private static Shot ritual(AstraeaState.Ritual r, double t, Shot own) {
        Vec3d A = r.anchor();
        Vec3d F = fwd(r.yaw()), S = new Vec3d(-F.z, 0, F.x), UP = new Vec3d(0, 1, 0);
        Vec3d rift = A.add(0, RitualFx.RIFT_HEIGHT, 0);
        Shot hero = new Shot(A.add(F.multiply(4.2)).add(S.multiply(1.6)).add(0, 0.5 + 0.3 * smooth(t / 34), 0), A.add(0, 1.55, 0));
        double u2 = (t - Timeline.R_RIFT) / (double) (Timeline.R_STAR - Timeline.R_RIFT);
        Shot under = new Shot(A.subtract(F.multiply(5.5 - u2)).add(S.multiply(2.2)).add(0, 0.6, 0), A.add(0, 6 + 22 * smooth(u2 * 1.4), 0));
        double u3 = (t - Timeline.R_STAR) / (double) (Timeline.R_DESCEND - Timeline.R_STAR);
        Shot rise = new Shot(A.add(S.multiply(3.2 + 2 * u3)).subtract(F.multiply(2.2)).add(0, 1.2 + 9 * smooth(u3), 0), rift.add(0, -0.6, 0));
        double u4 = (t - Timeline.R_DESCEND) / (double) (Timeline.R_ABSORB - Timeline.R_DESCEND);
        Shot side = new Shot(A.add(S.multiply(17)).add(F.multiply(4)).add(0, 9 - 2 * u4, 0), A.add(0, 34 - 31 * smooth(u4 * 1.1), 0));
        double u5 = (t - Timeline.R_ABSORB) / (double) (Timeline.R_IMPACT - Timeline.R_ABSORB);
        double ang = 0.5 * Math.min(u5, 1.25);
        Vec3d orb = F.multiply(Math.cos(ang)).add(S.multiply(Math.sin(ang)));
        Shot close = new Shot(A.add(orb.multiply(3.1 - 0.5 * Math.min(u5, 1))).add(0, 1.75, 0), A.add(0, 1.6, 0));
        double u7 = (t - Timeline.R_CLOSE) / (double) (Timeline.R_END - Timeline.R_CLOSE);
        Shot back = new Shot(A.add(F.multiply(5 + 3 * u7)).add(S.multiply(2.5)).add(0, 2.5 + 2.5 * u7, 0), A.add(0, 3 + 6 * u7, 0));

        if (t < 22) return blend(own, hero, smooth(t / 22));
        if (t < Timeline.R_RIFT) return hero;
        if (t < Timeline.R_STAR) return blend(hero, under, smooth((t - Timeline.R_RIFT) / 18));
        if (t < Timeline.R_DESCEND) return blend(under, rise, smooth((t - Timeline.R_STAR) / 24));
        if (t < Timeline.R_ABSORB) return blend(rise, side, smooth((t - Timeline.R_DESCEND) / 8));
        if (t < Timeline.R_CLOSE) return blend(side, close, smooth((t - Timeline.R_ABSORB) / 14));
        Shot b = blend(close, back, smooth((t - Timeline.R_CLOSE) / 16));
        return blend(b, own, smooth((t - (Timeline.R_END - 22)) / 22));
    }

    private static Shot strike(AstraeaState.Strike s, double t, Shot own, float pt) {
        MinecraftClient mc = MinecraftClient.getInstance();
        Vec3d A = s.anchor();
        float yaw = mc.player.getYaw(pt);
        Vec3d F = fwd(yaw), S = new Vec3d(-F.z, 0, F.x);
        if (t < StrikeCine.FROM) {
            double h = t < Timeline.S_RELEASE ? 1.8 : 2.3 + 900 * Math.pow((t - Timeline.S_RELEASE) / (Timeline.S_ORBIT + 20 - Timeline.S_RELEASE), 2.4);
            Shot low = new Shot(A.add(F.multiply(4.6)).add(S.multiply(1.5)).add(0, 0.55, 0), A.add(0, Math.min(1.6 + h * 0.85, 400), 0));
            return t < 10 ? blend(own, low, smooth(t / 10)) : low;
        }
        if (t < StrikeCine.TO) {
            // covered by space; keep the world camera still, looking up
            return new Shot(A.add(0, 1.6, 0), A.add(F.multiply(0.01)).add(0, 50, 0));
        }
        AstraeaState.Chasm c = AstraeaState.nearest(A.x, A.z);
        if (t < Timeline.S_RETURN && c != null) {
            Vec3d along = new Vec3d(Math.cos(c.angle()), 0, Math.sin(c.angle()));
            Vec3d perp = new Vec3d(-along.z, 0, along.x);
            double u = (t - StrikeCine.TO) / (double) (Timeline.S_RETURN - StrikeCine.TO);
            Vec3d mid = new Vec3d(c.x(), A.y, c.z());
            Vec3d eye = mid.add(perp.multiply(55)).add(along.multiply(-40 + 50 * u)).add(0, 46 - 8 * u, 0);
            return new Shot(eye, mid.add(along.multiply(-10 + 40 * u)).add(0, -14, 0));
        }
        Vec3d hand = A.add(0, 1.5, 0);
        double u = MathHelper.clamp((t - Timeline.S_RETURN) / (Timeline.S_CATCH - Timeline.S_RETURN), 0, 1);
        double e = 1 - Math.pow(1 - u, 2.2);
        Vec3d lance = hand.add(0, 700 * (1 - e), 0);
        Shot low = new Shot(A.add(F.multiply(4.4)).add(S.multiply(-1.6)).add(0, 0.6, 0), new Vec3d(A.x, Math.min(lance.y, A.y + 40), A.z));
        if (t < Timeline.S_RETURN + 4) return low;
        return blend(low, own, smooth((t - Timeline.S_CATCH - 2) / (Timeline.S_END - Timeline.S_CATCH - 2)));
    }
}

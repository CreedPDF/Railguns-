package com.creedpdf.orbitalrailgun.client.hangeki;

import com.creedpdf.orbitalrailgun.hangeki.Hangeki;
import com.creedpdf.orbitalrailgun.hangeki.HangekiNet;
import com.creedpdf.orbitalrailgun.hangeki.TimeScale;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;

/** What the client knows about parries: the local threat meter and every nearby duelist's stance. */
public final class ParryClient {
    private ParryClient() {}

    public record Stance(byte phase, long start, int ticks, Vec3d focus) {}

    /** Duelists by entity id. */
    static final Map<Integer, Stance> STANCES = new HashMap<>();

    // the local threat meter
    static byte meterKind = HangekiNet.K_NONE;
    static float closeness, shown;
    static String label = "";
    static int count;
    static boolean slowed;
    /** Real-time millis the local player's slow motion began (for the HUD's fade-ins). */
    static long slowSince;
    // what the local cutscene follows: an entity (projectile, warden), or a point from the bolt / missile packets
    static int trackEntity = -1;
    static Vec3d trackPos, trackPrev;
    static long trackTick;

    static void track(int entity, Vec3d focus) {
        trackEntity = entity;
        if (trackPos == null) trackPos = trackPrev = focus;
    }

    static void trackAt(Vec3d p) {
        trackPrev = trackPos == null ? p : trackPos;
        trackPos = p;
    }

    /** Where the threat the local player is facing is now (interpolated). */
    static Vec3d threat(float pt, Vec3d fallback) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (trackEntity >= 0 && mc.world != null) {
            var e = mc.world.getEntityById(trackEntity);
            if (e != null) return e.getLerpedPos(pt).add(0, e.getHeight() * 0.5, 0);
        }
        if (trackPos != null) return trackPrev.lerp(trackPos, pt);
        return fallback;
    }

    /** Real-time millis of the local player's last reflection (the panel shows RETURN). */
    static long lastReturn;

    static void meter(byte kind, float c, String l, int n, boolean s) {
        if (s && !slowed) slowSince = System.currentTimeMillis();
        meterKind = kind;
        closeness = c;
        label = l;
        count = n;
        slowed = s && kind != HangekiNet.K_NONE;
    }

    static void state(int player, byte phase, long start, int ticks, Vec3d focus) {
        if (phase == HangekiNet.PH_IDLE) {
            Stance old = STANCES.remove(player);
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player != null && mc.player.getId() == player) {
                trackEntity = -1;
                trackPos = trackPrev = null;
            }
            if (old != null && old.phase == HangekiNet.PH_STANCE) {
                // the follow-through: the body stays low for a moment and unwinds out of the stance
                STANCES.put(player, new Stance(HangekiNet.PH_RELEASE, start, 10, old.focus()));
                if (mc.player != null && mc.player.getId() == player) HangekiCine.endStance();
            }
        } else {
            STANCES.put(player, new Stance(phase, start, ticks, focus));
            MinecraftClient mc = MinecraftClient.getInstance();
            if ((phase == HangekiNet.PH_STANCE || phase == HangekiNet.PH_SLOW) && mc.player != null && mc.player.getId() == player)
                HangekiCine.begin(phase, focus);
        }
    }

    /** Drop finished follow-throughs. */
    static void tick() {
        STANCES.values().removeIf(s -> s.phase() == HangekiNet.PH_RELEASE && progress(s, 0) >= 1f);
    }

    static void clear() {
        trackEntity = -1;
        trackPos = trackPrev = null;
        STANCES.clear();
        meterKind = HangekiNet.K_NONE;
        slowed = false;
        closeness = shown = 0;
        TimeScale.clientTarget = 1f;
        TimeScale.client = 1f;
        HangekiCine.reset();
    }

    public static Stance stance(int id) {
        return STANCES.get(id);
    }

    /** How far into its stance (0..1) a duelist is, by world time; -1 if not in one. */
    public static float progress(Stance s, float pt) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (s == null || mc.world == null) return -1;
        float t = (mc.world.getTime() - s.start() + pt) / Math.max(1, s.ticks());
        return MathHelper.clamp(t, 0f, 1f);
    }

    /** The stance pose weight for a player model: 0 = normal, 1 = full stance (eased). Guard poses are quicker. */
    public static float poseWeight(PlayerEntity p, float pt) {
        Stance s = STANCES.get(p.getId());
        if (s == null) return 0f;
        float u = progress(s, pt);
        if (s.phase() == HangekiNet.PH_STANCE) return smooth(Math.min(1f, u / 0.55f));
        if (s.phase() == HangekiNet.PH_RELEASE) return 1f - smooth(u);
        if (s.phase() == HangekiNet.PH_GUARD) return u < 0.25f ? smooth(u / 0.25f) : smooth(1f - (u - 0.25f) / 0.75f) * 0.999f;
        if (s.phase() == HangekiNet.PH_SLOW) return 0.18f * smooth(Math.min(1f, u * 6));
        return 0f;
    }

    public static boolean guardPose(PlayerEntity p) {
        Stance s = STANCES.get(p.getId());
        return s != null && s.phase() == HangekiNet.PH_GUARD;
    }

    static float smooth(float x) {
        x = MathHelper.clamp(x, 0f, 1f);
        return x * x * (3 - 2 * x);
    }

    static boolean holding() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.player != null && mc.player.getMainHandStack().isOf(Hangeki.SWORD);
    }
}

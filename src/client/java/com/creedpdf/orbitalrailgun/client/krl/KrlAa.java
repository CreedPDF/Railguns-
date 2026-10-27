package com.creedpdf.orbitalrailgun.client.krl;

import net.minecraft.client.MinecraftClient;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Client side of KRL air defence: the interceptors in flight (positions streamed by the server every tick and
 * interpolated between), their smoke trails and their detonations. Drawn by {@link KrlFx}.
 */
public final class KrlAa {
    private KrlAa() {}

    static final class Shot {
        final int id;
        Vec3d prev, pos, vel;
        double trailFrom = -1;
        int age;
        boolean seen;

        Shot(int id, Vec3d pos, Vec3d vel) {
            this.id = id;
            this.prev = pos;
            this.pos = pos;
            this.vel = vel;
        }

        Vec3d at(float pt) {
            return prev.lerp(pos, pt);
        }
    }

    static final class Burst {
        final Vec3d at;
        final int kind;
        int age;

        Burst(Vec3d at, int kind) {
            this.at = at;
            this.kind = kind;
        }
    }

    public record Frame(List<double[]> shots, List<double[]> booms) {}

    static final Map<Integer, Shot> SHOTS = new HashMap<>();
    static final List<Burst> BURSTS = new ArrayList<>();

    public static Frame read(PacketByteBuf b) {
        int n = b.readVarInt();
        List<double[]> s = new ArrayList<>();
        for (int i = 0; i < n; i++)
            s.add(new double[]{b.readVarInt(), b.readDouble(), b.readDouble(), b.readDouble(), b.readFloat(), b.readFloat(), b.readFloat()});
        int m = b.readVarInt();
        List<double[]> bo = new ArrayList<>();
        for (int i = 0; i < m; i++) bo.add(new double[]{b.readDouble(), b.readDouble(), b.readDouble(), b.readByte()});
        return new Frame(s, bo);
    }

    public static void apply(Frame f) {
        for (Shot s : SHOTS.values()) s.seen = false;
        for (double[] d : f.shots()) {
            int id = (int) d[0];
            Vec3d p = new Vec3d(d[1], d[2], d[3]), v = new Vec3d(d[4], d[5], d[6]);
            Shot s = SHOTS.get(id);
            if (s == null) {
                s = new Shot(id, p.subtract(v), v);
                SHOTS.put(id, s);
            }
            s.prev = s.pos;
            s.pos = p;
            s.vel = v;
            s.seen = true;
        }
        SHOTS.values().removeIf(s -> !s.seen);
        MinecraftClient mc = MinecraftClient.getInstance();
        for (double[] d : f.booms()) {
            Burst b = new Burst(new Vec3d(d[0], d[1], d[2]), (int) d[3]);
            BURSTS.add(b);
            // a burst of smoke and debris where it went off
            for (int i = 0; i < 26; i++) {
                double a = Math.random() * Math.PI * 2, e = Math.random() * Math.PI - Math.PI / 2, sp = 0.25 + Math.random() * 0.45;
                KrlFx.puff(b.at.x, b.at.y, b.at.z, Math.cos(a) * Math.cos(e) * sp, Math.sin(e) * sp, Math.sin(a) * Math.cos(e) * sp, KrlFx.r(60, 110),
                        1.2f, 5.5f, b.kind == 2 ? 0.45f : 0.3f, 0.6f, 1.4f, 0.9f, 0.002f);
            }
            if (mc.gameRenderer != null) {
                double dist = mc.gameRenderer.getCamera().getPos().distanceTo(b.at);
                com.creedpdf.orbitalrailgun.client.hud.RailgunHud.shake((float) MathHelper.clamp(0.9 - dist / 250, 0, 0.9));
            }
        }
    }

    static boolean any() {
        return !SHOTS.isEmpty() || !BURSTS.isEmpty();
    }

    /** Client tick: lay the smoke trails, age the bursts. */
    static void tick() {
        for (Shot s : SHOTS.values()) {
            s.age++;
            Vec3d a = s.prev, b = s.pos;
            double len = a.distanceTo(b);
            int n = (int) MathHelper.clamp(len / 1.6, 1, 18);
            for (int i = 0; i < n; i++) {
                Vec3d p = a.lerp(b, i / (double) n);
                float hot = s.age < 30 ? 1f : 0.6f;
                KrlFx.puff(p.x + KrlFx.rs() * 0.15, p.y + KrlFx.rs() * 0.15, p.z + KrlFx.rs() * 0.15, KrlFx.rs() * 0.02, 0.01, KrlFx.rs() * 0.02,
                        KrlFx.r(120, 200), 0.35f, 2.4f, 0.92f, 0.3f * hot, 0.9f, 0.985f, 0.0005f);
            }
        }
        for (Iterator<Burst> it = BURSTS.iterator(); it.hasNext(); ) {
            Burst b = it.next();
            if (++b.age > 24) it.remove();
        }
    }

    public static void clear() {
        SHOTS.clear();
        BURSTS.clear();
    }
}

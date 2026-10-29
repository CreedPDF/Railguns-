package com.creedpdf.orbitalrailgun.client.hangeki;

import com.creedpdf.orbitalrailgun.client.krl.KrlCompat;
import com.creedpdf.orbitalrailgun.client.krl.KrlDraw;
import com.creedpdf.orbitalrailgun.client.krl.KrlFlame;
import com.creedpdf.orbitalrailgun.client.krl.KrlMesh;
import com.creedpdf.orbitalrailgun.client.krl.KrlModels;
import com.creedpdf.orbitalrailgun.client.render.BeamRenderLayers;
import com.creedpdf.orbitalrailgun.hangeki.HangekiNet;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * HANGEKI's world-space effects, additive glow (BeamRenderLayers.GLOW, fog off) so they read through shader packs:
 * <ul>
 *   <li>the reflection at the blade: a white flash, three shock rings across the line of attack, a starburst of
 *   spikes, a gold crescent where the blade met it, sparks; the thing that was sent back drags a red-hot tracer;</li>
 *   <li>a counter: a tighter burst and a slash crescent;</li>
 *   <li>a reflected railgun shot: the incoming lance, then a far thicker beam back to the emplacement with rings
 *   running down it and a flare at the far end;</li>
 *   <li>a reflected sonic boom: rings of force racing back along the line;</li>
 *   <li>a caught KRL missile hanging in the air, and the same missile flying home, plume lit.</li>
 * </ul>
 */
public final class HangekiFx {
    private HangekiFx() {}

    record Burst(byte kind, Vec3d at, Vec3d dir, Vec3d to, int player, int entity, float speed, int extra, long t0, long seed) {}

    private static final class Trail {
        final ArrayDeque<Vec3d> pts = new ArrayDeque<>();
        long until;
    }

    private static final class Held {
        Vec3d pos, prev, dir;
        int livery;
        long seen;
    }

    private static final class Spark {
        Vec3d pos, vel;
        int life, max;
        boolean hot;
    }

    private static final class Bolt {
        Vec3d muzzle, head, prev;
        long seen, born;
    }

    /** Railgun rounds in flight (by emplacement id): a lance from the muzzle to the bolt's head. */
    private static final Map<Integer, Bolt> BOLTS = new HashMap<>();
    private static final List<Burst> BURSTS = new ArrayList<>();
    private static final Map<Integer, Trail> TRAILS = new HashMap<>();
    private static final Map<Integer, Held> HELD = new HashMap<>();
    private static final List<Spark> SPARKS = new ArrayList<>();
    private static final Random RNG = new Random();
    private static long ticks;
    private static final VertexConsumerProvider.Immediate BUF = VertexConsumerProvider.immediate(new BufferBuilder(1 << 19));

    private static final float[] WHITE = {1f, 0.96f, 0.92f}, RED = {0.9f, 0.03f, 0.07f}, AMBER = {1f, 0.3f, 0.32f}, GOLD = {1f, 0.86f, 0.82f};

    static void clear() {
        BURSTS.clear();
        BOLTS.clear();
        TRAILS.clear();
        HELD.clear();
        SPARKS.clear();
    }

    static void add(byte kind, Vec3d at, Vec3d dir, Vec3d to, int player, int entity, float speed, int extra) {
        if (kind == HangekiNet.FX_MISSILE_HOLD) {
            Held h = HELD.computeIfAbsent(entity, k -> new Held());
            h.prev = h.pos == null ? at : h.pos;
            h.pos = at;
            h.dir = dir;
            h.livery = extra;
            h.seen = ticks;
            return;
        }
        if (kind == HangekiNet.FX_BEAM_HOLD) {
            Bolt b = BOLTS.get(entity);
            if (b == null) {
                b = new Bolt();
                b.born = ticks;
                b.prev = at;
                BOLTS.put(entity, b);
            } else b.prev = b.head;
            b.muzzle = at;
            b.head = to;
            b.seen = ticks;
            return;
        }
        if (kind == HangekiNet.FX_BEAM) BOLTS.remove(entity);
        if (kind == HangekiNet.FX_MISSILE) HELD.remove(entity);
        BURSTS.add(new Burst(kind, at, dir, to, player, entity, speed, extra, ticks, RNG.nextLong()));
        if ((kind == HangekiNet.FX_REFLECT || kind == HangekiNet.FX_REFLECT_QUICK) && entity > 0) {
            Trail t = new Trail();
            t.until = ticks + 60;
            TRAILS.put(entity, t);
        }
        switch (kind) {
            case HangekiNet.FX_REFLECT -> sparks(at, dir, entity > 0 ? 26 : 70, 0.55f);
            case HangekiNet.FX_REFLECT_QUICK -> sparks(at, dir, 24, 0.4f);
            case HangekiNet.FX_COUNTER -> sparks(at, dir, 34, 0.45f);
            case HangekiNet.FX_BEAM -> sparks(at, dir, 60, 0.7f);
            default -> {
            }
        }
    }

    private static void sparks(Vec3d at, Vec3d dir, int n, float speed) {
        for (int i = 0; i < n; i++) {
            Spark s = new Spark();
            Vec3d r = new Vec3d(RNG.nextGaussian(), RNG.nextGaussian(), RNG.nextGaussian()).normalize();
            // mostly a cone thrown out along the reflection, the rest everywhere
            Vec3d v = i % 3 == 0 ? r : dir.multiply(0.8).add(r.multiply(0.75)).normalize();
            s.pos = at;
            s.vel = v.multiply(speed * (0.3 + RNG.nextFloat()));
            s.max = s.life = 8 + RNG.nextInt(14);
            s.hot = RNG.nextFloat() < 0.4f;
            SPARKS.add(s);
        }
    }

    static void tick(MinecraftClient mc) {
        if (mc.world == null) {
            clear();
            return;
        }
        ticks++;
        BURSTS.removeIf(b -> ticks - b.t0() > 80);
        HELD.values().removeIf(h -> ticks - h.seen > 4);
        BOLTS.values().removeIf(b -> ticks - b.seen > 4);
        for (Iterator<Map.Entry<Integer, Trail>> it = TRAILS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Trail> en = it.next();
            Trail t = en.getValue();
            Entity e = mc.world.getEntityById(en.getKey());
            if (ticks > t.until || (e == null && t.pts.isEmpty())) {
                it.remove();
                continue;
            }
            if (e != null && !e.isRemoved()) t.pts.addFirst(e.getPos().add(0, e.getHeight() * 0.5, 0));
            else if (!t.pts.isEmpty()) t.pts.removeLast();
            while (t.pts.size() > 16) t.pts.removeLast();
        }
        for (Iterator<Spark> it = SPARKS.iterator(); it.hasNext(); ) {
            Spark s = it.next();
            s.pos = s.pos.add(s.vel);
            s.vel = s.vel.multiply(0.86).add(0, -0.02, 0);
            if (--s.life <= 0) it.remove();
        }
    }

    // ------------------------------------------------------------------------------------------- render

    static void render(WorldRenderContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || KrlCompat.shadowPass()) return;
        if (BURSTS.isEmpty() && TRAILS.isEmpty() && HELD.isEmpty() && SPARKS.isEmpty() && ParryClient.STANCES.isEmpty() && BOLTS.isEmpty()) return;
        float pt = ctx.tickDelta();
        Vec3d cam = ctx.camera().getPos();
        MatrixStack ms = ctx.matrixStack();
        float time = ticks + pt;

        // meshes first (their own layers), then all the glow in one go
        for (Map.Entry<Integer, Held> en : HELD.entrySet()) {
            Held h = en.getValue();
            Vec3d p = h.prev.lerp(h.pos, pt);
            spear(mc, ms, cam, p, h.dir, h.livery, time, 0.35f, 3f);
        }
        for (Burst b : BURSTS) {
            if (b.kind() != HangekiNet.FX_MISSILE) continue;
            float age = ticks - b.t0() + pt;
            double len = b.to().distanceTo(b.at());
            double gone = age * b.speed();
            if (gone >= len) continue;
            Vec3d nose = b.at().add(b.dir().multiply(gone));
            spear(mc, ms, cam, nose, b.dir(), b.extra(), time, 1f, 14f);
        }

        List<float[]> wards = wards(mc, ms, cam, pt, time);

        Matrix4f m = ms.peek().getPositionMatrix();
        VertexConsumer g = BUF.getBuffer(BeamRenderLayers.GLOW);
        for (float[] wd : wards) wardGlow(g, m, wd);
        for (Bolt bo : BOLTS.values()) bolt(g, m, cam, bo, pt, time);
        for (Burst b : BURSTS) {
            float age = ticks - b.t0() + pt;
            switch (b.kind()) {
                case HangekiNet.FX_REFLECT -> reflect(g, m, cam, b, age, 1f);
                case HangekiNet.FX_REFLECT_QUICK -> reflect(g, m, cam, b, age, 0.6f);
                case HangekiNet.FX_COUNTER -> counter(g, m, cam, b, age);
                case HangekiNet.FX_BEAM -> beam(g, m, cam, b, age, time);
                case HangekiNet.FX_SONIC -> sonic(g, m, cam, b, age);
                case HangekiNet.FX_MISSILE -> missileTrail(g, m, cam, b, age);
                default -> {
                }
            }
        }
        for (Map.Entry<Integer, Trail> en : TRAILS.entrySet()) trail(g, m, cam, mc, en.getKey(), en.getValue(), pt);
        for (Spark s : SPARKS) {
            Vec3d p = s.pos.add(s.vel.multiply(pt));
            float k = s.life / (float) s.max;
            float[] c = s.hot ? WHITE : k > 0.5f ? GOLD : RED;
            strip(g, m, rel(p, cam), rel(p.subtract(s.vel.multiply(1.8)), cam), 0.05f, 0.01f, c, k, 0f);
        }
        float fs = RenderSystem.getShaderFogStart(), fe = RenderSystem.getShaderFogEnd();
        RenderSystem.setShaderFogStart(1e7f);
        RenderSystem.setShaderFogEnd(2e7f);
        BUF.draw();
        RenderSystem.setShaderFogStart(fs);
        RenderSystem.setShaderFogEnd(fe);
    }

    // ---- the ward: the little samurai shield in front of the blade during a parry

    private static float easeOutBack(float x) {
        float c1 = 1.9f, c3 = c1 + 1;
        float y = x - 1;
        return 1 + c3 * y * y * y + c1 * y * y;
    }

    /** Draws every duelist's ward; returns {x, y, z (camera-relative), fx, fy, fz, scale, flash} for the glow pass. */
    private static List<float[]> wards(MinecraftClient mc, MatrixStack ms, Vec3d cam, float pt, float time) {
        List<float[]> out = new ArrayList<>();
        KrlMesh mesh = KrlModels.HANGEKI_WARD;
        for (Map.Entry<Integer, ParryClient.Stance> en : ParryClient.STANCES.entrySet()) {
            ParryClient.Stance s = en.getValue();
            if (s.phase() != HangekiNet.PH_STANCE && s.phase() != HangekiNet.PH_RELEASE) continue;
            if (!(mc.world.getEntityById(en.getKey()) instanceof net.minecraft.entity.player.PlayerEntity p)) continue;
            float u = ParryClient.progress(s, pt);
            float scale, flash = 0;
            if (s.phase() == HangekiNet.PH_STANCE) scale = easeOutBack(Math.min(1f, u / 0.45f));
            else if (u < 0.2f) {
                scale = 1f + 0.35f * u / 0.2f;
                flash = 1f - u / 0.2f;
            } else scale = 1.35f * (1f - ParryClient.smooth((u - 0.2f) / 0.8f));
            if (scale <= 0.01f) continue;
            Vec3d eye = p.getLerpedPos(pt).add(0, p.getStandingEyeHeight() * 0.8, 0);
            Vec3d face = s.focus().subtract(eye);
            face = face.lengthSquared() < 1e-6 ? p.getRotationVec(pt) : face.normalize();
            Vec3d at = eye.add(face.multiply(1.55)).add(0, -0.1, 0);
            out.add(new float[]{(float) (at.x - cam.x), (float) (at.y - cam.y), (float) (at.z - cam.z), (float) face.x, (float) face.y, (float) face.z,
                    scale, flash});
            if (mesh == null) continue;
            float sc = 0.85f * scale;
            float spin = time * (s.phase() == HangekiNet.PH_RELEASE ? 0.5f : 0.07f);
            float bob = MathHelper.sin(time * 0.3f) * 0.03f;
            int light = LightmapTextureManager.MAX_LIGHT_COORDINATE;
            ms.push();
            ms.translate(at.x - cam.x, at.y - cam.y + bob, at.z - cam.z);
            ms.multiply(net.minecraft.util.math.RotationAxis.POSITIVE_Y.rotation((float) Math.atan2(face.x, face.z)));
            ms.multiply(net.minecraft.util.math.RotationAxis.POSITIVE_X.rotation((float) -Math.asin(MathHelper.clamp(face.y, -1, 1))));
            ms.scale(sc, sc, sc);
            KrlMesh.Pose pose = (part, st) -> {
                if ("mon".equals(part)) st.multiply(net.minecraft.util.math.RotationAxis.POSITIVE_Z.rotation(spin));
                else if ("halo".equals(part)) st.multiply(net.minecraft.util.math.RotationAxis.POSITIVE_Z.rotation(-spin * 0.6f));
                return true;
            };
            float bright = 0.85f + 0.15f * flash;
            mesh.lod(0).render(ms, BUF.getBuffer(RenderLayer.getEntityCutoutNoCull(KrlModels.WARD_TEX)), light, pose, null);
            mesh.glow(0).render(ms, BUF.getBuffer(RenderLayer.getEyes(KrlModels.WARD_GLOW)), light, pose, (nx, ny, nz, o) -> {
                o[0] = bright;
                o[1] = bright;
                o[2] = bright;
                o[3] = 1f;
            });
            ms.pop();
        }
        return out;
    }

    /** A railgun round in flight: a white-cored crimson lance from the muzzle to a blazing head ringed with shock. */
    private static void bolt(VertexConsumer g, Matrix4f m, Vec3d cam, Bolt b, float pt, float time) {
        Vec3d headW = b.prev.lerp(b.head, pt);
        Vector3f a = rel(b.muzzle, cam), z = rel(headW, cam);
        float fl = 0.85f + 0.15f * MathHelper.sin(time * 5.1f);
        float grow = MathHelper.clamp((ticks - b.born + pt) / 3f, 0, 1);
        float w = 1.1f * fl * (0.4f + 0.6f * grow);
        strip(g, m, a, z, w * 2.2f, w * 1.6f, RED, 0.22f, 0.4f);
        strip(g, m, a, z, w, w * 0.9f, AMBER, 0.55f, 0.75f);
        strip(g, m, a, z, w * 0.32f, w * 0.34f, WHITE, 0.95f, 1f);
        Vector3f[] cb = camBasis(z);
        ring(g, m, z, cb[0], cb[1], 0f, 1.3f * fl, 24, 0, MathHelper.TAU, WHITE, 1f, 0f);
        ring(g, m, z, cb[0], cb[1], 0.6f, 2.6f * fl, 24, 0, MathHelper.TAU, RED, 0.6f, 0f);
        Vector3f d = new Vector3f(z).sub(a);
        float len = d.length();
        if (len < 1e-3f) return;
        d.div(len);
        Vector3f[] uv = basis(d);
        for (int i = 0; i < 4; i++) {
            float ph = ((time * 0.35f + i * 0.25f) % 1f);
            Vector3f c = new Vector3f(d).mul(-ph * 6f).add(z);
            float r = 0.5f + ph * 1.4f;
            ring(g, m, c, uv[0], uv[1], r, r + 0.15f, 28, 0, MathHelper.TAU, i % 2 == 0 ? WHITE : RED, 0.7f * (1 - ph), 0f);
        }
    }

    /** A faint crimson barrier behind the ward, its rim, and on the hit a white ring blown outward. */
    private static void wardGlow(VertexConsumer g, Matrix4f m, float[] wd) {
        Vector3f c = new Vector3f(wd[0], wd[1], wd[2]);
        Vector3f d = new Vector3f(wd[3], wd[4], wd[5]);
        if (d.lengthSquared() < 1e-6f) return;
        d.normalize();
        Vector3f[] uv = basis(d);
        float s = wd[6], flash = wd[7];
        Vector3f back = new Vector3f(c).sub(new Vector3f(d).mul(0.08f));
        ring(g, m, back, uv[0], uv[1], 0f, 0.72f * s, 36, 0, MathHelper.TAU, RED, 0.16f, 0.05f);
        ring(g, m, back, uv[0], uv[1], 0.72f * s, 0.78f * s, 36, 0, MathHelper.TAU, RED, 0.55f, 0.55f);
        if (flash > 0) {
            float r = 0.8f + (1 - flash) * 2.4f;
            ring(g, m, c, uv[0], uv[1], r, r + 0.18f, 40, 0, MathHelper.TAU, WHITE, flash, 0f);
            ring(g, m, c, uv[0], uv[1], 0f, 0.9f * s, 30, 0, MathHelper.TAU, WHITE, 0.6f * flash, 0f);
        }
    }

    /** The KRL spear, nose at `nose`, pointing along `dir`, its motor plume streaming behind. */
    private static void spear(MinecraftClient mc, MatrixStack ms, Vec3d cam, Vec3d nose, Vec3d dir, int livery, float time, float burn, float plume) {
        KrlMesh mesh = KrlModels.SPEAR;
        if (mesh == null || dir.lengthSquared() < 1e-6) return;
        Vec3d d = dir.normalize();
        Vec3d nozzle = nose.subtract(d.multiply(KrlModels.NOSE_TIP));
        int light = mc.world == null ? LightmapTextureManager.MAX_LIGHT_COORDINATE : WorldRenderer.getLightmapCoordinates(mc.world, BlockPos.ofFloored(nozzle));
        ms.push();
        ms.translate(nozzle.x - cam.x, nozzle.y - cam.y, nozzle.z - cam.z);
        ms.multiply(new Quaternionf().rotationTo(0f, 1f, 0f, (float) d.x, (float) d.y, (float) d.z));
        ms.multiply(new Quaternionf().rotationY(time * 0.3f));
        mesh.lod(0).render(ms, BUF.getBuffer(RenderLayer.getEntityCutoutNoCull(KrlModels.spearTex(livery))), light, null, null);
        mesh.glow(0).render(ms, BUF.getBuffer(RenderLayer.getEyes(KrlModels.SPEAR_GLOW)), light, null, (nx, ny, nz, o) -> {
            o[0] = 1f;
            o[1] = 1f;
            o[2] = 1f;
            o[3] = 1f;
        });
        ms.pop();
        Matrix4f m = ms.peek().getPositionMatrix();
        float flick = 0.85f + 0.15f * MathHelper.sin(time * 2.7f);
        KrlFlame.plume(m, BUF.getBuffer(BeamRenderLayers.textured(KrlDraw.PLASMA)), nozzle.subtract(cam), d.multiply(-1), plume, 0.34f, burn * flick, time);
    }

    // ---- bursts

    private static void reflect(VertexConsumer g, Matrix4f m, Vec3d cam, Burst b, float age, float s) {
        Vector3f c = rel(b.at(), cam);
        Vector3f d = new Vector3f((float) b.dir().x, (float) b.dir().y, (float) b.dir().z);
        if (d.lengthSquared() < 1e-6f) d.set(0, 1, 0);
        d.normalize();
        Vector3f[] uv = basis(d);
        // flash
        if (age < 6) {
            float k = 1 - age / 6;
            Vector3f[] cb = camBasis(c);
            ring(g, m, c, cb[0], cb[1], 0f, 1.9f * s * (0.5f + k), 24, 0, MathHelper.TAU, WHITE, k, 0f);
        }
        // three shock rings across the line of attack
        for (int i = 0; i < 3; i++) {
            float a = age - i * 1.5f;
            if (a < 0 || a > 16) continue;
            float k = 1 - a / 16;
            float r = s * (0.3f + a * (0.5f + i * 0.22f));
            ring(g, m, c, uv[0], uv[1], r, r + 0.22f * s * (0.5f + k), 40, 0, MathHelper.TAU, i == 0 ? WHITE : i == 1 ? AMBER : RED, k * 0.95f, 0f);
        }
        // starburst
        if (age < 9) {
            Random r = new Random(b.seed());
            float k = 1 - age / 9;
            for (int i = 0; i < 20; i++) {
                float a = r.nextFloat() * MathHelper.TAU;
                Vector3f dd = new Vector3f(uv[0]).mul(MathHelper.cos(a)).add(new Vector3f(uv[1]).mul(MathHelper.sin(a)));
                float len = s * (1.2f + r.nextFloat() * 2.8f) * Math.min(1f, age / 1.5f + 0.2f);
                strip(g, m, new Vector3f(c), new Vector3f(dd).mul(len).add(c), 0.16f * s, 0f, i % 2 == 0 ? WHITE : RED, k, 0f);
            }
        }
        // the crescent where the blade met it
        if (age < 12) {
            float k = 1 - age / 12;
            float a0 = (b.seed() % 628) / 100f;
            ring(g, m, c, uv[0], uv[1], 0.95f * s, 1.25f * s, 30, a0, a0 + MathHelper.PI * 1.25f, GOLD, k, k * 0.2f);
        }
        // a lance down the line it went back on
        if (age < 10 && b.speed() > 0) {
            float k = 1 - age / 10;
            Vector3f far = new Vector3f(d).mul(Math.min(age * b.speed(), 40f)).add(c);
            strip(g, m, new Vector3f(c), far, 0.32f * s * k, 0.02f, AMBER, k * 0.8f, 0f);
            strip(g, m, new Vector3f(c), far, 0.1f * s, 0.01f, WHITE, k, 0f);
        }
    }

    private static void counter(VertexConsumer g, Matrix4f m, Vec3d cam, Burst b, float age) {
        if (age > 12) return;
        Vector3f c = rel(b.at(), cam);
        Vector3f d = new Vector3f((float) b.dir().x, (float) b.dir().y, (float) b.dir().z);
        if (d.lengthSquared() < 1e-6f) d.set(0, 0, 1);
        d.normalize();
        Vector3f[] uv = basis(d);
        float k = 1 - age / 12;
        for (int i = 0; i < 2; i++) {
            float r = 0.25f + (age - i) * 0.4f;
            if (r > 0) ring(g, m, c, uv[0], uv[1], r, r + 0.16f, 32, 0, MathHelper.TAU, i == 0 ? WHITE : RED, k, 0f);
        }
        // the riposte: a long horizontal slash crescent
        Vector3f side = new Vector3f(d).cross(0, 1, 0);
        if (side.lengthSquared() < 1e-6f) side.set(1, 0, 0);
        side.normalize();
        float a0 = -1.2f + age * 0.25f;
        ring(g, m, new Vector3f(c).add(new Vector3f(d).mul(0.3f)), side, d, 1.0f, 1.3f, 26, a0, a0 + 2.4f, WHITE, k, 0f);
        ring(g, m, new Vector3f(c).add(new Vector3f(d).mul(0.3f)), side, d, 1.3f, 1.55f, 26, a0, a0 + 2.4f, RED, k * 0.7f, 0f);
        if (age < 5) {
            Vector3f[] cb = camBasis(c);
            ring(g, m, c, cb[0], cb[1], 0f, 1.1f, 20, 0, MathHelper.TAU, WHITE, 1 - age / 5, 0f);
        }
    }

    private static void beam(VertexConsumer g, Matrix4f m, Vec3d cam, Burst b, float age, float time) {
        Vector3f a = rel(b.at(), cam), z = rel(b.to(), cam);
        if (age < 3) {
            float k = 1 - age / 3;
            strip(g, m, z, a, 0.5f * k, 0.5f * k, WHITE, k, k);
        }
        float life = 26;
        if (age > life) return;
        float k = 1 - age / life;
        float fl = 0.8f + 0.2f * MathHelper.sin(time * 4.3f);
        float w = 1.6f * (0.35f + 0.65f * k) * fl;
        strip(g, m, a, z, w * 1.9f, w * 2.3f, RED, 0.28f * k, 0.22f * k);
        strip(g, m, a, z, w, w * 1.2f, AMBER, 0.6f * k, 0.5f * k);
        strip(g, m, a, z, w * 0.36f, w * 0.42f, WHITE, k, k);
        // rings racing down the beam
        Vector3f d = new Vector3f(z).sub(a);
        float len = d.length();
        if (len < 1e-3f) return;
        d.div(len);
        Vector3f[] uv = basis(d);
        for (float s = 4; s < len; s += 9) {
            float t = age - s / 30f;
            if (t < 0 || t > 10) continue;
            float kk = 1 - t / 10;
            Vector3f c = new Vector3f(d).mul(s).add(a);
            float r = 0.8f + t * 0.45f;
            ring(g, m, c, uv[0], uv[1], r, r + 0.25f, 28, 0, MathHelper.TAU, AMBER, kk * 0.9f, 0f);
        }
        // the flare at the far end
        Vector3f[] cb = camBasis(z);
        ring(g, m, z, cb[0], cb[1], 0f, 7f * k + 2f, 28, 0, MathHelper.TAU, WHITE, k, 0f);
        ring(g, m, z, cb[0], cb[1], 2f, 14f * k + 4f, 28, 0, MathHelper.TAU, RED, 0.6f * k, 0f);
    }

    private static void sonic(VertexConsumer g, Matrix4f m, Vec3d cam, Burst b, float age) {
        Vector3f a = rel(b.at(), cam), z = rel(b.to(), cam);
        Vector3f d = new Vector3f(z).sub(a);
        float len = d.length();
        if (len < 1e-3f) return;
        d.div(len);
        Vector3f[] uv = basis(d);
        float speed = 7f;
        for (float s = 0.5f; s < len + 2; s += 1.4f) {
            float t = age - s / speed;
            if (t < 0 || t > 8) continue;
            float k = 1 - t / 8;
            Vector3f c = new Vector3f(d).mul(s).add(a);
            float r = 0.35f + t * 0.3f;
            ring(g, m, c, uv[0], uv[1], r, r + 0.18f + 0.1f * k, 24, 0, MathHelper.TAU, ((int) (s / 1.4f)) % 2 == 0 ? AMBER : RED, k, 0f);
        }
        if (age < 12) {
            float k = 1 - age / 12;
            strip(g, m, a, z, 0.5f * k, 0.5f * k, RED, 0.35f * k, 0.2f * k);
        }
    }

    private static void missileTrail(VertexConsumer g, Matrix4f m, Vec3d cam, Burst b, float age) {
        double len = b.to().distanceTo(b.at());
        double gone = Math.min(len, age * b.speed());
        float fade = gone >= len ? Math.max(0f, 1f - (float) (age - len / Math.max(1e-3, b.speed())) / 20f) : 1f;
        if (fade <= 0) return;
        Vec3d nose = b.at().add(b.dir().multiply(gone));
        Vec3d tail = b.at().add(b.dir().multiply(Math.max(0, gone - 60)));
        strip(g, m, rel(tail, cam), rel(nose, cam), 0.1f, 1.2f, RED, 0f, 0.55f * fade);
        strip(g, m, rel(tail, cam), rel(nose, cam), 0.04f, 0.35f, WHITE, 0f, 0.8f * fade);
    }

    private static void trail(VertexConsumer g, Matrix4f m, Vec3d cam, MinecraftClient mc, int id, Trail t, float pt) {
        if (t.pts.size() < 2) return;
        Entity e = mc.world.getEntityById(id);
        Vec3d head = e != null && !e.isRemoved() ? e.getLerpedPos(pt).add(0, e.getHeight() * 0.5, 0) : t.pts.peekFirst();
        Vec3d prev = head;
        int i = 0, n = t.pts.size();
        float fade = MathHelper.clamp((t.until - ticks - pt) / 20f, 0, 1);
        for (Vec3d p : t.pts) {
            float k0 = 1 - i / (float) n, k1 = 1 - (i + 1) / (float) n;
            strip(g, m, rel(prev, cam), rel(p, cam), 0.5f * k0, 0.5f * k1, RED, 0.55f * k0 * fade, 0.55f * k1 * fade);
            strip(g, m, rel(prev, cam), rel(p, cam), 0.16f * k0, 0.16f * k1, WHITE, k0 * fade, k1 * fade);
            prev = p;
            i++;
        }
    }

    // ------------------------------------------------------------------------------------------- primitives (camera at the origin)

    private static void v(VertexConsumer g, Matrix4f m, Vector3f p, float[] c, float a) {
        g.vertex(m, p.x, p.y, p.z).color(c[0], c[1], c[2], a).next();
    }

    private static Vector3f rel(Vec3d p, Vec3d c) {
        return new Vector3f((float) (p.x - c.x), (float) (p.y - c.y), (float) (p.z - c.z));
    }

    /** A strip a->b, width wa at a and wb at b, turned about its axis to face the camera. */
    private static void strip(VertexConsumer g, Matrix4f m, Vector3f a, Vector3f b, float wa, float wb, float[] col, float aa, float ab) {
        Vector3f d = new Vector3f(b).sub(a), mid = new Vector3f(a).add(b).mul(0.5f);
        Vector3f side = d.cross(mid, new Vector3f());
        if (side.lengthSquared() < 1e-10f) return;
        side.normalize();
        Vector3f sa = new Vector3f(side).mul(wa * 0.5f), sb = new Vector3f(side).mul(wb * 0.5f);
        v(g, m, new Vector3f(a).sub(sa), col, aa);
        v(g, m, new Vector3f(b).sub(sb), col, ab);
        v(g, m, new Vector3f(b).add(sb), col, ab);
        v(g, m, new Vector3f(a).add(sa), col, aa);
    }

    private static void ring(VertexConsumer g, Matrix4f m, Vector3f c, Vector3f u, Vector3f w, float r0, float r1, int segs, float a0, float a1,
                             float[] col, float alpha0, float alpha1) {
        for (int i = 0; i < segs; i++) {
            float t0 = a0 + (a1 - a0) * i / segs, t1 = a0 + (a1 - a0) * (i + 1) / segs;
            Vector3f d0 = new Vector3f(u).mul(MathHelper.cos(t0)).add(new Vector3f(w).mul(MathHelper.sin(t0)));
            Vector3f d1 = new Vector3f(u).mul(MathHelper.cos(t1)).add(new Vector3f(w).mul(MathHelper.sin(t1)));
            v(g, m, new Vector3f(d0).mul(r0).add(c), col, alpha0);
            v(g, m, new Vector3f(d1).mul(r0).add(c), col, alpha0);
            v(g, m, new Vector3f(d1).mul(r1).add(c), col, alpha1);
            v(g, m, new Vector3f(d0).mul(r1).add(c), col, alpha1);
        }
    }

    private static Vector3f[] basis(Vector3f d) {
        Vector3f u = Math.abs(d.y) < 0.9f ? new Vector3f(0, 1, 0).cross(d, new Vector3f()) : new Vector3f(1, 0, 0).cross(d, new Vector3f());
        u.normalize();
        return new Vector3f[]{u, new Vector3f(d).cross(u, new Vector3f()).normalize()};
    }

    /** A plane facing the camera through point c (camera at the origin). */
    private static Vector3f[] camBasis(Vector3f c) {
        Vector3f d = new Vector3f(c);
        if (d.lengthSquared() < 1e-6f) d.set(0, 0, 1);
        return basis(d.normalize());
    }
}

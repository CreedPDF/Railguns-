package com.creedpdf.orbitalrailgun.client.reliquary.render;

import com.creedpdf.orbitalrailgun.client.krl.KrlCompat;
import com.creedpdf.orbitalrailgun.client.reliquary.ReliquaryClientState;
import com.creedpdf.orbitalrailgun.client.render.BeamRenderLayers;
import com.creedpdf.orbitalrailgun.reliquary.item.StarEaterGazeItem;
import com.creedpdf.orbitalrailgun.reliquary.logic.StarEaterBeams;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Every Cosmic Reliquary effect in the world, drawn from scratch as raw coloured vertex quads on the mod's additive
 * untextured layer - no particles, no textures, no vanilla effects:
 * <ul>
 *   <li>{@code stellar_ray_quads} - the Star-Eater's ray: pairs of crossed translucent quads stepped along the ray, each
 *   step turned a little further round the axis so they interlock into a twisting neon-blue helix, round a white-blue
 *   core, thickening and quickening as the charge builds;</li>
 *   <li>{@code spatial_ribbon} - the Void-Carver's swing: a violet ribbon laid in world space along the exact arc of the
 *   cut (the same plane the server cuts with), revealed over the swing and fading after;</li>
 *   <li>the rift's arcs - cubic Bezier curves from the rift to each marked entity, re-jittered every frame;</li>
 *   <li>{@code cosmic_shatter} - a disintegrated mob vaporising into expanding neon-blue rings at random tilts;</li>
 *   <li>the revolver's tracer, muzzle and impact flashes;</li>
 *   <li>{@code heat_distortion_voxels} - small shimmering cubes of heat whose corners wobble every frame, trailing
 *   behind anything burning on a molten scar and bursting from the revolver;</li>
 *   <li>the Orbital Link's column of light.</li>
 * </ul>
 */
public final class ReliquaryFx {
    private ReliquaryFx() {}

    static final float NR = 0.22f, NG = 0.62f, NB = 1.0f;          // neon blue
    static final float VR = 0.66f, VG = 0.2f, VB = 1.0f;           // void violet
    static final float HR = 1.0f, HG = 0.45f, HB = 0.08f;          // heat orange
    private static final VertexConsumerProvider.Immediate BUF = VertexConsumerProvider.immediate(new BufferBuilder(1 << 19));
    private static final Random RND = Random.create();

    record Ribbon(Vec3d eye, Vec3d f, Vec3d r, long at) {}

    record Arc(int rift, int target, long at, long seed) {}

    record Shatter(Vec3d at, float size, long at0, long seed) {}

    record Shot(Vec3d from, Vec3d to, long at, boolean wall) {}

    record Link(int player, long at) {}

    static final class Voxel {
        double x, y, z;
        float vx, vy, vz, size, age, life;
        int seed;
    }

    private static final List<Ribbon> RIBBONS = new ArrayList<>();
    private static final List<Arc> ARCS = new ArrayList<>();
    private static final List<Shatter> SHATTERS = new ArrayList<>();
    private static final List<Shot> SHOTS = new ArrayList<>();
    private static final List<Link> LINKS = new ArrayList<>();
    private static final List<Voxel> VOXELS = new ArrayList<>();
    /** Burning entity id -> burn until tick. */
    private static final Map<Integer, Long> BURNING = new java.util.HashMap<>();

    // ------------------------------------------------------------------------------------------- events in

    public static void slash(PlayerEntity p, float yaw, float pitch) {
        Vec3d f = Vec3d.fromPolar(pitch, yaw);
        Vec3d r = f.crossProduct(new Vec3d(0, 1, 0));
        if (r.lengthSquared() < 1e-4) r = Vec3d.fromPolar(0, yaw + 90);
        RIBBONS.add(new Ribbon(p.getEyePos(), f, r.normalize(), ReliquaryClientState.ticks));
    }

    public static void arcs(int rift, int[] targets) {
        for (int t : targets) ARCS.add(new Arc(rift, t, ReliquaryClientState.ticks, RND.nextLong()));
    }

    public static void shatter(Vec3d at, float size) {
        SHATTERS.add(new Shatter(at, size, ReliquaryClientState.ticks, RND.nextLong()));
    }

    public static void shot(PlayerEntity p, Vec3d end, boolean wall) {
        Vec3d from = muzzle(p, 1f);
        SHOTS.add(new Shot(from, end, ReliquaryClientState.ticks, wall));
        burst(from, 8, 0.05f, 0.12f);
        if (wall) burst(end, 22, 0.15f, 0.22f);
    }

    public static void burn(int[] ids) {
        for (int id : ids) BURNING.put(id, ReliquaryClientState.ticks + 20);
    }

    public static void link(int player) {
        LINKS.add(new Link(player, ReliquaryClientState.ticks));
    }

    public static void clear() {
        RIBBONS.clear();
        ARCS.clear();
        SHATTERS.clear();
        SHOTS.clear();
        LINKS.clear();
        VOXELS.clear();
        BURNING.clear();
    }

    /** Roughly where a player's weapon is: in front of the right hand. */
    static Vec3d muzzle(PlayerEntity p, float pt) {
        Vec3d eye = p.getCameraPosVec(pt), look = p.getRotationVec(pt);
        Vec3d right = look.crossProduct(new Vec3d(0, 1, 0));
        if (right.lengthSquared() < 1e-4) right = new Vec3d(1, 0, 0);
        right = right.normalize();
        Vec3d up = right.crossProduct(look).normalize();
        boolean firstPerson = p == MinecraftClient.getInstance().player && MinecraftClient.getInstance().options.getPerspective().isFirstPerson();
        return firstPerson ? eye.add(look.multiply(0.9)).add(right.multiply(0.32)).subtract(up.multiply(0.24))
                : eye.add(look.multiply(0.9)).add(right.multiply(0.38)).subtract(up.multiply(0.42));
    }

    // ------------------------------------------------------------------------------------------- voxels

    static void burst(Vec3d at, int n, float speed, float size) {
        for (int i = 0; i < n; i++) {
            Voxel v = new Voxel();
            v.x = at.x;
            v.y = at.y;
            v.z = at.z;
            v.vx = (RND.nextFloat() - 0.5f) * speed * 2;
            v.vy = RND.nextFloat() * speed * 1.5f + 0.01f;
            v.vz = (RND.nextFloat() - 0.5f) * speed * 2;
            v.size = size * (0.5f + RND.nextFloat());
            v.life = 14 + RND.nextInt(14);
            v.seed = RND.nextInt(1000);
            if (VOXELS.size() < 1500) VOXELS.add(v);
        }
    }

    public static void tick(MinecraftClient mc) {
        long now = ReliquaryClientState.ticks;
        RIBBONS.removeIf(r -> now - r.at() > 18);
        ARCS.removeIf(a -> now - a.at() > 7);
        SHATTERS.removeIf(s -> now - s.at0() > 24);
        SHOTS.removeIf(s -> now - s.at() > 6);
        LINKS.removeIf(l -> now - l.at() > 40);
        for (Iterator<Voxel> it = VOXELS.iterator(); it.hasNext(); ) {
            Voxel v = it.next();
            if (++v.age >= v.life) {
                it.remove();
                continue;
            }
            v.x += v.vx;
            v.y += v.vy;
            v.z += v.vz;
            v.vx *= 0.9f;
            v.vz *= 0.9f;
            v.vy = v.vy * 0.92f + 0.004f;   // heat rises
        }
        if (mc.world == null) return;
        BURNING.entrySet().removeIf(e -> e.getValue() < now);
        for (Map.Entry<Integer, Long> e : BURNING.entrySet()) {
            Entity en = mc.world.getEntityById(e.getKey());
            if (en == null || now % 2 != 0) continue;
            Vec3d back = en.getPos().subtract(en.getVelocity().multiply(3)).add(0, 0.15, 0);
            for (int i = 0; i < 2; i++) {
                Voxel v = new Voxel();
                v.x = back.x + (RND.nextFloat() - 0.5) * en.getWidth();
                v.y = back.y + RND.nextFloat() * 0.3;
                v.z = back.z + (RND.nextFloat() - 0.5) * en.getWidth();
                v.vy = 0.02f + RND.nextFloat() * 0.03f;
                v.size = 0.08f + RND.nextFloat() * 0.1f;
                v.life = 16 + RND.nextInt(10);
                v.seed = RND.nextInt(1000);
                if (VOXELS.size() < 1500) VOXELS.add(v);
            }
        }
    }

    // ------------------------------------------------------------------------------------------- drawing

    public static void render(WorldRenderContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || KrlCompat.shadowPass()) return;
        boolean any = !ReliquaryClientState.BEAMS.isEmpty() || !RIBBONS.isEmpty() || !ARCS.isEmpty() || !SHATTERS.isEmpty() || !SHOTS.isEmpty()
                || !LINKS.isEmpty() || !VOXELS.isEmpty();
        if (!any) return;
        float pt = ctx.tickDelta();
        Camera cam = ctx.camera();
        Vec3d c = cam.getPos();
        Matrix4f m = ctx.matrixStack().peek().getPositionMatrix();
        VertexConsumer g = BUF.getBuffer(BeamRenderLayers.GLOW);
        float time = ReliquaryClientState.ticks + pt;

        for (Map.Entry<Integer, Long> e : ReliquaryClientState.BEAMS.entrySet())
            if (mc.world.getEntityById(e.getKey()) instanceof PlayerEntity p) ray(g, m, c, p, ReliquaryClientState.ticks - e.getValue() + pt, time, pt);
        for (Ribbon r : RIBBONS) ribbon(g, m, c, r, ReliquaryClientState.ticks - r.at() + pt);
        for (Arc a : ARCS) arc(g, m, c, mc, a, time, pt);
        for (Shatter s : SHATTERS) shatter(g, m, c, s, ReliquaryClientState.ticks - s.at0() + pt);
        for (Shot s : SHOTS) shot(g, m, c, s, ReliquaryClientState.ticks - s.at() + pt);
        for (Link l : LINKS)
            if (mc.world.getEntityById(l.player()) instanceof PlayerEntity p) link(g, m, c, p, ReliquaryClientState.ticks - l.at() + pt, time, pt);
        for (Voxel v : VOXELS) voxel(g, m, c, v, pt, time);

        float fs = RenderSystem.getShaderFogStart(), fe = RenderSystem.getShaderFogEnd();
        RenderSystem.setShaderFogStart(1e7f);
        RenderSystem.setShaderFogEnd(2e7f);
        BUF.draw();
        RenderSystem.setShaderFogStart(fs);
        RenderSystem.setShaderFogEnd(fe);
    }

    // ---- primitives

    static void v(VertexConsumer g, Matrix4f m, Vector3f p, float r, float gg, float b, float a) {
        g.vertex(m, p.x, p.y, p.z).color(r, gg, b, a).next();
    }

    static void quad(VertexConsumer g, Matrix4f m, Vector3f a, Vector3f b, Vector3f c, Vector3f d, float r, float gg, float bl, float al) {
        v(g, m, a, r, gg, bl, al);
        v(g, m, b, r, gg, bl, al);
        v(g, m, c, r, gg, bl, al);
        v(g, m, d, r, gg, bl, al);
    }

    static Vector3f rel(Vec3d p, Vec3d c) {
        return new Vector3f((float) (p.x - c.x), (float) (p.y - c.y), (float) (p.z - c.z));
    }

    /** A strip from a to b, turned about its own axis to face the camera (the camera is the origin). */
    static void strip(VertexConsumer g, Matrix4f m, Vector3f a, Vector3f b, float w, float r, float gg, float bl, float al) {
        Vector3f d = new Vector3f(b).sub(a);
        Vector3f mid = new Vector3f(a).add(b).mul(0.5f);
        Vector3f side = d.cross(mid, new Vector3f());
        if (side.lengthSquared() < 1e-10f) return;
        side.normalize().mul(w * 0.5f);
        quad(g, m, new Vector3f(a).sub(side), new Vector3f(b).sub(side), new Vector3f(b).add(side), new Vector3f(a).add(side), r, gg, bl, al);
    }

    /** A flat ring band (inner to outer radius) round centre c in the plane spanned by u, v. */
    static void ring(VertexConsumer g, Matrix4f m, Vector3f c, Vector3f u, Vector3f v, float r0, float r1, int segs, float r, float gg, float bl,
                     float a0, float a1) {
        for (int i = 0; i < segs; i++) {
            float t0 = MathHelper.TAU * i / segs, t1 = MathHelper.TAU * (i + 1) / segs;
            Vector3f d0 = new Vector3f(u).mul(MathHelper.cos(t0)).add(new Vector3f(v).mul(MathHelper.sin(t0)));
            Vector3f d1 = new Vector3f(u).mul(MathHelper.cos(t1)).add(new Vector3f(v).mul(MathHelper.sin(t1)));
            v(g, m, new Vector3f(d0).mul(r0).add(c), r, gg, bl, a0);
            v(g, m, new Vector3f(d1).mul(r0).add(c), r, gg, bl, a0);
            v(g, m, new Vector3f(d1).mul(r1).add(c), r, gg, bl, a1);
            v(g, m, new Vector3f(d0).mul(r1).add(c), r, gg, bl, a1);
        }
    }

    static Vector3f[] basis(Vector3f d) {
        Vector3f u = Math.abs(d.y) < 0.9f ? new Vector3f(0, 1, 0).cross(d, new Vector3f()) : new Vector3f(1, 0, 0).cross(d, new Vector3f());
        u.normalize();
        Vector3f w = d.cross(u, new Vector3f()).normalize();
        return new Vector3f[]{u, w};
    }

    // ---- stellar_ray_quads

    private static void ray(VertexConsumer g, Matrix4f m, Vec3d c, PlayerEntity p, float held, float time, float pt) {
        float k = MathHelper.clamp(held / StarEaterGazeItem.COLLAPSE_TICKS, 0f, 1f);
        Vec3d from = muzzle(p, pt), to = StarEaterBeams.rayEnd(p, pt);
        Vector3f a = rel(from, c), b = rel(to, c);
        Vector3f dir = new Vector3f(b).sub(a);
        float len = dir.length();
        if (len < 0.1f) return;
        dir.normalize();
        Vector3f[] uv = basis(dir);
        float step = 0.45f;
        int n = (int) Math.ceil(len / step);
        float w = 0.16f + 0.22f * k + 0.03f * MathHelper.sin(time * 0.9f);
        float spin = time * (0.25f + 0.5f * k);
        float in = Math.min(1f, held / 4f);
        for (int i = 0; i < n; i++) {
            float s0 = i * step, s1 = Math.min(len, s0 + step);
            Vector3f p0 = new Vector3f(dir).mul(s0).add(a), p1 = new Vector3f(dir).mul(s1).add(a);
            for (int pair = 0; pair < 2; pair++) {
                float t0 = spin + i * 0.42f + pair * MathHelper.HALF_PI, t1 = t0 + 0.42f;
                Vector3f o0 = new Vector3f(uv[0]).mul(MathHelper.cos(t0)).add(new Vector3f(uv[1]).mul(MathHelper.sin(t0))).mul(w);
                Vector3f o1 = new Vector3f(uv[0]).mul(MathHelper.cos(t1)).add(new Vector3f(uv[1]).mul(MathHelper.sin(t1))).mul(w);
                float al = (0.28f + 0.2f * k) * in * (0.75f + 0.25f * MathHelper.sin(time * 0.7f - i * 0.6f));
                quad(g, m, new Vector3f(p0).sub(o0), new Vector3f(p1).sub(o1), new Vector3f(p1).add(o1), new Vector3f(p0).add(o0), NR, NG, NB, al);
            }
        }
        strip(g, m, a, b, 0.07f + 0.08f * k, 0.75f, 0.9f, 1f, 0.9f * in);
        strip(g, m, a, b, 0.5f + 0.4f * k, NR, NG, NB, 0.12f * in);
        // the gathering knot at the far end
        Vector3f[] eb = basis(dir);
        ring(g, m, b, eb[0], eb[1], 0.1f, 0.6f + 1.2f * k, 18, NR, NG, NB, 0.55f * in, 0f);
    }

    // ---- spatial_ribbon

    private static void ribbon(VertexConsumer g, Matrix4f m, Vec3d c, Ribbon r, float age) {
        float reveal = MathHelper.clamp(age / 4f, 0f, 1f);
        float fade = age < 4 ? 1f : Math.max(0f, 1f - (age - 4) / 14f);
        if (fade <= 0) return;
        int segs = 28;
        float a0 = (float) Math.toRadians(80), span = (float) Math.toRadians(160) * reveal;
        float rIn = 0.9f, rOut = 3.9f;
        Vector3f eye = rel(r.eye(), c);
        Vector3f f = new Vector3f((float) r.f().x, (float) r.f().y, (float) r.f().z), rt = new Vector3f((float) r.r().x, (float) r.r().y, (float) r.r().z);
        for (int i = 0; i < segs; i++) {
            float t0 = a0 - span * i / segs, t1 = a0 - span * (i + 1) / segs;
            Vector3f d0 = new Vector3f(f).mul(MathHelper.cos(t0)).add(new Vector3f(rt).mul(MathHelper.sin(t0)));
            Vector3f d1 = new Vector3f(f).mul(MathHelper.cos(t1)).add(new Vector3f(rt).mul(MathHelper.sin(t1)));
            float lead = (i + 1f) / segs;                       // brighter toward the blade's leading edge
            float al = fade * (0.25f + 0.6f * lead);
            v(g, m, new Vector3f(d0).mul(rIn).add(eye), VR, VG, VB, 0f);
            v(g, m, new Vector3f(d1).mul(rIn).add(eye), VR, VG, VB, 0f);
            v(g, m, new Vector3f(d1).mul(rOut).add(eye), VR, VG, VB, al);
            v(g, m, new Vector3f(d0).mul(rOut).add(eye), VR, VG, VB, al);
            // the cutting edge: a thin white line along the outer rim
            strip(g, m, new Vector3f(d0).mul(rOut).add(eye), new Vector3f(d1).mul(rOut).add(eye), 0.05f, 1f, 0.9f, 1f, al);
        }
    }

    // ---- rift arcs (Bezier)

    private static void arc(VertexConsumer g, Matrix4f m, Vec3d c, MinecraftClient mc, Arc a, float time, float pt) {
        Entity rift = mc.world.getEntityById(a.rift()), t = mc.world.getEntityById(a.target());
        if (rift == null || t == null) return;
        Vec3d p0 = rift.getLerpedPos(pt).add(0, 1.1, 0), p3 = t.getLerpedPos(pt).add(0, t.getHeight() * 0.55, 0);
        Random r = Random.create(a.seed());
        Vec3d d = p3.subtract(p0);
        Vec3d side = d.crossProduct(new Vec3d(0, 1, 0));
        if (side.lengthSquared() < 1e-4) side = new Vec3d(1, 0, 0);
        side = side.normalize();
        Vec3d up = side.crossProduct(d).normalize();
        double L = d.length();
        Vec3d p1 = p0.add(d.multiply(0.33)).add(side.multiply((r.nextDouble() - 0.5) * L * 0.6)).add(up.multiply(r.nextDouble() * L * 0.4));
        Vec3d p2 = p0.add(d.multiply(0.66)).add(side.multiply((r.nextDouble() - 0.5) * L * 0.6)).add(up.multiply((r.nextDouble() - 0.3) * L * 0.3));
        float age = ReliquaryClientState.ticks - a.at() + pt;
        float al = Math.max(0f, 1f - age / 7f) * (RND.nextFloat() < 0.15f ? 0.4f : 1f);
        int n = 18;
        Vector3f prev = rel(p0, c);
        for (int i = 1; i <= n; i++) {
            double s = i / (double) n, u = 1 - s;
            Vec3d b = p0.multiply(u * u * u).add(p1.multiply(3 * u * u * s)).add(p2.multiply(3 * u * s * s)).add(p3.multiply(s * s * s));
            if (i < n) b = b.add((RND.nextDouble() - 0.5) * 0.25, (RND.nextDouble() - 0.5) * 0.25, (RND.nextDouble() - 0.5) * 0.25);
            Vector3f cur = rel(b, c);
            strip(g, m, prev, cur, 0.32f, VR, VG, VB, 0.45f * al);
            strip(g, m, prev, cur, 0.07f, 1f, 0.92f, 1f, al);
            prev = cur;
        }
    }

    // ---- cosmic_shatter

    private static void shatter(VertexConsumer g, Matrix4f m, Vec3d c, Shatter s, float age) {
        Random r = Random.create(s.seed());
        Vector3f at = rel(s.at(), c);
        float k = age / 24f;
        float fade = 1f - k;
        for (int i = 0; i < 6; i++) {
            Vector3f n = new Vector3f(r.nextFloat() - 0.5f, r.nextFloat() - 0.5f, r.nextFloat() - 0.5f).normalize();
            Vector3f[] uv = basis(n);
            float rad = s.size() * (0.4f + (1.2f + i * 0.35f) * (1 - (1 - k) * (1 - k)));
            ring(g, m, at, uv[0], uv[1], rad, rad + 0.12f + 0.1f * s.size() * fade, 32, NR, NG, NB, 0.9f * fade, 0.0f);
        }
        if (age < 6) {
            float f = 1f - age / 6f;
            Vector3f[] uv = basis(new Vector3f(0, 1, 0));
            ring(g, m, at, uv[0], uv[1], 0f, s.size() * (0.8f + age * 0.3f), 24, 0.8f, 0.92f, 1f, f, 0f);
        }
    }

    // ---- revolver

    private static void shot(VertexConsumer g, Matrix4f m, Vec3d c, Shot s, float age) {
        float f = Math.max(0f, 1f - age / 6f);
        Vector3f a = rel(s.from(), c), b = rel(s.to(), c);
        strip(g, m, a, b, 0.5f * f + 0.1f, HR, HG, HB, 0.45f * f);
        strip(g, m, a, b, 0.09f, 1f, 0.88f, 0.6f, f);
        Vector3f d = new Vector3f(b).sub(a).normalize();
        Vector3f[] uv = basis(d);
        if (age < 3) ring(g, m, a, uv[0], uv[1], 0f, 0.5f + age * 0.2f, 16, 1f, 0.7f, 0.3f, f, 0f);
        if (s.wall()) ring(g, m, b, uv[0], uv[1], 0.05f, 0.4f + age * 0.25f, 16, HR, HG, HB, f, 0f);
    }

    // ---- heat_distortion_voxels

    private static void voxel(VertexConsumer g, Matrix4f m, Vec3d c, Voxel v, float pt, float time) {
        float k = (v.age + pt) / v.life;
        float al = (k < 0.15f ? k / 0.15f : 1f - (k - 0.15f) / 0.85f) * 0.55f;
        if (al <= 0) return;
        float s = v.size * (1f - 0.4f * k) * 0.5f;
        Vector3f o = new Vector3f((float) (v.x - c.x + v.vx * pt), (float) (v.y - c.y + v.vy * pt), (float) (v.z - c.z + v.vz * pt));
        Vector3f[] cr = new Vector3f[8];
        for (int i = 0; i < 8; i++) {
            float ph = time * 0.9f + v.seed + i * 1.7f;
            float j = s * 0.35f;   // corner shimmer: the cube never holds still
            cr[i] = new Vector3f(((i & 1) == 0 ? -s : s) + MathHelper.sin(ph) * j, ((i & 2) == 0 ? -s : s) + MathHelper.sin(ph * 1.3f) * j,
                    ((i & 4) == 0 ? -s : s) + MathHelper.cos(ph * 0.8f) * j).add(o);
        }
        float hot = 1f - k;
        float r = 1f, gg = HG + 0.4f * hot, b = HB + 0.25f * hot;
        int[][] faces = {{0, 1, 3, 2}, {4, 6, 7, 5}, {0, 4, 5, 1}, {2, 3, 7, 6}, {0, 2, 6, 4}, {1, 5, 7, 3}};
        for (int[] fc : faces) quad(g, m, cr[fc[0]], cr[fc[1]], cr[fc[2]], cr[fc[3]], r, gg, b, al);
    }

    // ---- orbital link

    private static void link(VertexConsumer g, Matrix4f m, Vec3d c, PlayerEntity p, float age, float time, float pt) {
        float f = age < 6 ? age / 6f : Math.max(0f, 1f - (age - 6) / 34f);
        Vector3f base = rel(p.getLerpedPos(pt), c);
        Vector3f top = new Vector3f(base).add(0, 320, 0);
        strip(g, m, base, top, 0.25f, 0.85f, 0.95f, 1f, f);
        strip(g, m, base, top, 1.6f, NR, NG, NB, 0.35f * f);
        Vector3f[] uv = basis(new Vector3f(0, 1, 0));
        for (int i = 0; i < 4; i++) {
            float h = ((age * 0.9f + i * 6f) % 24f);
            ring(g, m, new Vector3f(base).add(0, h, 0), uv[0], uv[1], 0.6f + h * 0.05f, 0.9f + h * 0.08f, 24, NR, NG, NB, f * (1f - h / 24f), 0f);
        }
    }
}

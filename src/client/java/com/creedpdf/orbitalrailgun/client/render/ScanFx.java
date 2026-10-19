package com.creedpdf.orbitalrailgun.client.render;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.registry.ModSounds;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Scan array pulse: a holographic wall races out across the terrain from the railgun; every ore
 * it passes lights up as a colour-coded marker visible through rock, and hostile mobs get red
 * target brackets. Markers hold for ~20 seconds, then fade.
 */
public final class ScanFx {
    private ScanFx() {}

    public static final Identifier WALL = RailgunMod.id("textures/effect/scan_wall.png");
    public static final Identifier RING = RailgunMod.id("textures/effect/scan_ring.png");
    public static final Identifier MARKER = RailgunMod.id("textures/effect/scan_marker.png");
    public static final Identifier BRACKET = RailgunMod.id("textures/effect/scan_bracket.png");

    static final float WAVE_SPEED = 1.5f;        // blocks per tick
    static final float RADIUS = 48f, MOB_RADIUS = 64f;
    static final int LIFE = 440;

    /** coal, iron, copper, gold, redstone, lapis, diamond, emerald, quartz, debris, other */
    static final int[] KIND_COLOR = {0x9AA3AD, 0xE8C2A4, 0xF0884A, 0xFFD23A, 0xFF3A3A, 0x3A6BFF,
            0x5AF6FF, 0x35FF7A, 0xF4EEE6, 0xB0703A, 0xE070FF};

    private static final class Marker {
        final BlockPos pos;
        final int kind;
        final float dist;
        boolean pinged;

        Marker(BlockPos pos, int kind, float dist) {
            this.pos = pos;
            this.kind = kind;
            this.dist = dist;
        }
    }

    private static final class Scan {
        final Vec3d origin;
        final double groundY;
        final long start;
        final List<Marker> markers = new ArrayList<>();
        final int[] mobs;
        int pings;

        Scan(Vec3d origin, double groundY, long start, int[] mobs) {
            this.origin = origin;
            this.groundY = groundY;
            this.start = start;
            this.mobs = mobs;
        }
    }

    private static final List<Scan> SCANS = new ArrayList<>();
    private static final VertexConsumerProvider.Immediate BUFFER = VertexConsumerProvider.immediate(new BufferBuilder(1 << 20));

    public static void register() {
        WorldRenderEvents.AFTER_TRANSLUCENT.register(ScanFx::render);
    }

    public static void add(ClientWorld world, Vec3d head, double groundY, long[] pos, byte[] kind, int[] mobs) {
        Scan s = new Scan(head, groundY, world.getTime(), mobs);
        for (int i = 0; i < pos.length; i++) {
            BlockPos p = BlockPos.fromLong(pos[i]);
            s.markers.add(new Marker(p, MathHelper.clamp(kind[i], 0, KIND_COLOR.length - 1),
                    (float) Math.sqrt(p.getSquaredDistance(head.x, groundY, head.z))));
        }
        SCANS.add(s);
        if (SCANS.size() > 4) SCANS.remove(0);
    }

    private static void render(WorldRenderContext ctx) {
        ClientWorld world = ctx.world();
        if (world == null || SCANS.isEmpty()) return;
        long now = world.getTime();
        float td = ctx.tickDelta();
        SCANS.removeIf(s -> now < s.start || now - s.start > LIFE);
        if (SCANS.isEmpty()) return;
        Vec3d cam = ctx.camera().getPos();
        Matrix4f m = ctx.matrixStack().peek().getPositionMatrix();
        Quaternionf rot = ctx.camera().getRotation();
        Vector3f right = rot.transform(new Vector3f(1, 0, 0));
        Vector3f up = rot.transform(new Vector3f(0, 1, 0));
        MinecraftClient mc = MinecraftClient.getInstance();

        for (Scan s : SCANS) {
            float e = now - s.start + td;
            float fade = e > LIFE - 60 ? Math.max(0f, (LIFE - e) / 60f) : 1f;
            float r = e * WAVE_SPEED;

            // ---- the wave: a holographic wall + ground ring, then a faint afterglow ring ----
            if (r < RADIUS + 6) {
                float k = 1f - smooth((r - RADIUS + 6) / 6f);
                wall(BUFFER.getBuffer(BeamRenderLayers.textured(WALL)), m, s, cam, r, 6f, 0.35f, 0.9f, 1f, 0.85f * k, e);
                ring(BUFFER.getBuffer(BeamRenderLayers.textured(RING)), m, s, cam, Math.max(0.1f, r - 2.5f), r + 0.6f,
                        0.4f, 0.95f, 1f, 0.9f * k, e * 0.01f);
                ring(BUFFER.getBuffer(BeamRenderLayers.textured(RING)), m, s, cam, Math.max(0.1f, r * 0.6f - 1f), r * 0.6f,
                        0.6f, 0.4f, 1f, 0.35f * k, -e * 0.006f);
            }
            if (e < 10) {
                float k = 1f - e / 10f;
                Vector3f o = rel(s.origin, cam);
                sprite(BUFFER.getBuffer(BeamRenderLayers.xray(MARKER)), m, o, 6f + e * 1.6f, right, up, 0.5f, 0.95f, 1f, k, e * 0.2f);
            }

            // ---- ore markers ----
            VertexConsumer mk = BUFFER.getBuffer(BeamRenderLayers.xray(MARKER));
            for (Marker mrk : s.markers) {
                float revealAt = mrk.dist / WAVE_SPEED;
                if (e < revealAt) continue;
                float a = e - revealAt;
                if (!mrk.pinged) {
                    mrk.pinged = true;
                    if ((mrk.kind == 3 || mrk.kind == 6 || mrk.kind == 7 || mrk.kind == 9) && s.pings < 12) {
                        s.pings++;
                        float pitch = mrk.kind == 6 ? 1.5f : mrk.kind == 7 ? 1.35f : mrk.kind == 9 ? 0.8f : 1.15f;
                        mc.getSoundManager().play(new PositionedSoundInstance(ModSounds.SCAN_PING, SoundCategory.BLOCKS, 0.6f, pitch,
                                Random.create(), mrk.pos.getX() + 0.5, mrk.pos.getY() + 0.5, mrk.pos.getZ() + 0.5));
                    }
                }
                int c = KIND_COLOR[mrk.kind];
                float cr = ((c >> 16) & 255) / 255f, cg = ((c >> 8) & 255) / 255f, cb = (c & 255) / 255f;
                float pop = a < 8 ? 1f + 0.9f * (1f - a / 8f) : 1f;
                float alpha = Math.min(1f, a / 5f) * fade;
                float bob = MathHelper.sin((e + mrk.pos.getX() * 3 + mrk.pos.getZ()) * 0.12f) * 0.06f;
                Vector3f p = new Vector3f((float) (mrk.pos.getX() + 0.5 - cam.x), (float) (mrk.pos.getY() + 0.5 - cam.y) + bob,
                        (float) (mrk.pos.getZ() + 0.5 - cam.z));
                boolean rare = mrk.kind == 3 || mrk.kind == 6 || mrk.kind == 7 || mrk.kind == 9;
                float size = (rare ? 0.8f : 0.55f) * pop;
                sprite(mk, m, p, size, right, up, cr, cg, cb, alpha, rare ? e * 0.05f : 0f);
                if (rare) sprite(mk, m, p, size * 2.4f, right, up, cr, cg, cb, alpha * 0.25f, -e * 0.03f);
            }

            // ---- hostile brackets ----
            VertexConsumer br = BUFFER.getBuffer(BeamRenderLayers.xray(BRACKET));
            for (int id : s.mobs) {
                Entity ent = world.getEntityById(id);
                if (ent == null || !ent.isAlive()) continue;
                Vec3d c = ent.getLerpedPos(td);
                double d = Math.sqrt(c.squaredDistanceTo(s.origin.x, s.groundY, s.origin.z));
                float revealAt = (float) d / WAVE_SPEED;
                if (e < revealAt) continue;
                float a = e - revealAt;
                Box b = ent.getBoundingBox();
                Vector3f p = new Vector3f((float) (c.x - cam.x), (float) (c.y + b.getYLength() / 2 - cam.y), (float) (c.z - cam.z));
                float size = (float) Math.max(b.getYLength(), b.getXLength()) * 1.5f * (a < 6 ? 1.6f - 0.6f * a / 6f : 1f);
                float pulse = 0.8f + 0.2f * MathHelper.sin(e * 0.4f);
                sprite(br, m, p, size, right, up, 1f, 0.22f, 0.18f, Math.min(1f, a / 4f) * fade * pulse, 0f);
            }
        }
        BUFFER.draw();
    }

    private static Vector3f rel(Vec3d p, Vec3d cam) {
        return new Vector3f((float) (p.x - cam.x), (float) (p.y - cam.y), (float) (p.z - cam.z));
    }

    /** Vertical cylinder band of the wave (U around, V up). */
    private static void wall(VertexConsumer vc, Matrix4f m, Scan s, Vec3d cam, float r, float h,
                             float cr, float cg, float cb, float a, float time) {
        int seg = Math.max(24, Math.min(160, (int) (r * 3)));
        float y0 = (float) (s.groundY - 2.0 - cam.y), y1 = y0 + h;
        float ox = (float) (s.origin.x - cam.x), oz = (float) (s.origin.z - cam.z);
        for (int i = 0; i < seg; i++) {
            float a0 = i / (float) seg * MathHelper.TAU, a1 = (i + 1) / (float) seg * MathHelper.TAU;
            float u0 = i / (float) seg * r * 0.25f + time * 0.02f, u1 = (i + 1) / (float) seg * r * 0.25f + time * 0.02f;
            Vector3f p0 = new Vector3f(ox + MathHelper.cos(a0) * r, y0, oz + MathHelper.sin(a0) * r);
            Vector3f p1 = new Vector3f(ox + MathHelper.cos(a1) * r, y0, oz + MathHelper.sin(a1) * r);
            Vector3f p2 = new Vector3f(p1.x, y1, p1.z), p3 = new Vector3f(p0.x, y1, p0.z);
            BeamRenderer.texVertRaw(vc, m, p0, cr, cg, cb, a, u0, 1);
            BeamRenderer.texVertRaw(vc, m, p1, cr, cg, cb, a, u1, 1);
            BeamRenderer.texVertRaw(vc, m, p2, cr, cg, cb, a, u1, 0);
            BeamRenderer.texVertRaw(vc, m, p3, cr, cg, cb, a, u0, 0);
        }
    }

    /** Flat annulus on the ground (U around, V inner to outer). */
    private static void ring(VertexConsumer vc, Matrix4f m, Scan s, Vec3d cam, float inner, float outer,
                             float cr, float cg, float cb, float a, float spin) {
        int seg = Math.max(24, Math.min(160, (int) (outer * 3)));
        float y = (float) (s.groundY + 0.15 - cam.y);
        float ox = (float) (s.origin.x - cam.x), oz = (float) (s.origin.z - cam.z);
        for (int i = 0; i < seg; i++) {
            float a0 = i / (float) seg * MathHelper.TAU + spin, a1 = (i + 1) / (float) seg * MathHelper.TAU + spin;
            float u0 = i / (float) seg * 8f, u1 = (i + 1) / (float) seg * 8f;
            BeamRenderer.texVertRaw(vc, m, new Vector3f(ox + MathHelper.cos(a0) * inner, y, oz + MathHelper.sin(a0) * inner), cr, cg, cb, a, u0, 0);
            BeamRenderer.texVertRaw(vc, m, new Vector3f(ox + MathHelper.cos(a1) * inner, y, oz + MathHelper.sin(a1) * inner), cr, cg, cb, a, u1, 0);
            BeamRenderer.texVertRaw(vc, m, new Vector3f(ox + MathHelper.cos(a1) * outer, y, oz + MathHelper.sin(a1) * outer), cr, cg, cb, a, u1, 1);
            BeamRenderer.texVertRaw(vc, m, new Vector3f(ox + MathHelper.cos(a0) * outer, y, oz + MathHelper.sin(a0) * outer), cr, cg, cb, a, u0, 1);
        }
    }

    private static void sprite(VertexConsumer vc, Matrix4f m, Vector3f c, float size, Vector3f right, Vector3f up,
                               float r, float g, float b, float a, float roll) {
        float cs = MathHelper.cos(roll), sn = MathHelper.sin(roll);
        Vector3f rr = new Vector3f(right).mul(cs).add(new Vector3f(up).mul(sn)).mul(size * 0.5f);
        Vector3f uu = new Vector3f(up).mul(cs).sub(new Vector3f(right).mul(sn)).mul(size * 0.5f);
        BeamRenderer.texVertRaw(vc, m, new Vector3f(c).sub(rr).sub(uu), r, g, b, a, 0, 1);
        BeamRenderer.texVertRaw(vc, m, new Vector3f(c).add(rr).sub(uu), r, g, b, a, 1, 1);
        BeamRenderer.texVertRaw(vc, m, new Vector3f(c).add(rr).add(uu), r, g, b, a, 1, 0);
        BeamRenderer.texVertRaw(vc, m, new Vector3f(c).sub(rr).add(uu), r, g, b, a, 0, 0);
    }

    private static float smooth(float f) {
        f = MathHelper.clamp(f, 0f, 1f);
        return f * f * (3f - 2f * f);
    }
}

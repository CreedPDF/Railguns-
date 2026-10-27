package com.creedpdf.orbitalrailgun.client.station;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.client.hud.RailgunHud;
import com.creedpdf.orbitalrailgun.client.krl.KrlMesh;
import com.creedpdf.orbitalrailgun.client.krl.KrlModels;
import com.creedpdf.orbitalrailgun.client.render.BeamRenderLayers;
import com.creedpdf.orbitalrailgun.station.ScStrike;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Space Command effects, drawn from each event's age alone:
 * <ul>
 *   <li>station railgun shots - a white-hot needle from the muzzle to the rock with a red sheath, a muzzle flare and a
 *   flash and a little shock ring where the rock bursts;</li>
 *   <li>orbital strikes - a red targeting reticle spins up on the ground while the satellite (drawn high overhead,
 *   emitter down) charges; it fires, the beam tears down through the sky and hits: a white flash, two shockwaves racing
 *   out across the ground, and a column of light - halo, body, twisting plasma ribbons and a white core - boring into
 *   the crater, ground glow, sparks, flame and smoke; then it cuts out and leaves a smoking scorch scar.</li>
 * </ul>
 */
public final class ScFx {
    private ScFx() {}

    static final Identifier BEAM = fx("sc_beam"), CORE = fx("sc_core"), SPIRAL = fx("sc_spiral"), RETICLE = fx("sc_reticle"),
            SHOCK = fx("sc_shock"), FLARE = fx("sc_flare"), TRACER = fx("sc_tracer"), SCORCH = fx("sc_scorch");
    /** The satellite hangs this high above the target in the effects and the cutscene. */
    public static final double SAT_ALT = 260;
    private static final int FULL = 0xF000F0;
    private static final VertexConsumerProvider.Immediate BUF = VertexConsumerProvider.immediate(new BufferBuilder(1 << 20));
    private static final Random RND = Random.create();

    private static Identifier fx(String n) {
        return RailgunMod.id("textures/station/fx/" + n + ".png");
    }

    record Shot(Vec3d from, Vec3d to, long at) {}

    static final class Strike {
        final int id;
        final Vec3d target;
        final boolean mine;
        final long at;
        boolean impacted;

        Strike(int id, Vec3d target, boolean mine, long at) {
            this.id = id;
            this.target = target;
            this.mine = mine;
            this.at = at;
        }

        float age(float pt) {
            return ScClient.ticks - at + pt;
        }

        Vec3d sat() {
            return target.add(0, SAT_ALT, 0);
        }
    }

    private static final List<Shot> SHOTS = new ArrayList<>();
    static final List<Strike> STRIKES = new ArrayList<>();
    private static final int SCORCH_LIFE = 1200;

    static void shot(Vec3d from, Vec3d to) {
        SHOTS.add(new Shot(from, to, ScClient.ticks));
    }

    static void strike(int id, Vec3d target, boolean mine) {
        STRIKES.add(new Strike(id, target, mine, ScClient.ticks));
        if (mine) ScCine.start(id);
    }

    static void clear() {
        SHOTS.clear();
        STRIKES.clear();
    }

    // ------------------------------------------------------------------------------------------- ticking (particles, shake)

    static void tick(MinecraftClient mc) {
        SHOTS.removeIf(s -> ScClient.ticks - s.at() > 14);
        for (Iterator<Strike> it = STRIKES.iterator(); it.hasNext(); ) {
            Strike s = it.next();
            float a = s.age(0);
            if (a > ScStrike.BEAM_END + SCORCH_LIFE) {
                it.remove();
                continue;
            }
            if (mc.world == null || mc.player == null) continue;
            Vec3d t = s.target;
            if (!s.impacted && a >= ScStrike.IMPACT) {
                s.impacted = true;
                double d = mc.player.getPos().distanceTo(t);
                RailgunHud.shake((float) Math.max(0, 1.6 - d / 140.0));
            }
            if (a >= ScStrike.IMPACT && a < ScStrike.BEAM_END) {
                RailgunHud.shake((float) Math.max(0, 0.35 - mc.player.getPos().distanceTo(t) / 300.0));
                for (int i = 0; i < 6; i++) {
                    double ang = RND.nextDouble() * Math.PI * 2, r = 2 + RND.nextDouble() * 6;
                    mc.world.addParticle(ParticleTypes.LAVA, true, t.x + Math.cos(ang) * r * 0.4, t.y + 0.5, t.z + Math.sin(ang) * r * 0.4, 0, 0, 0);
                    mc.world.addParticle(ParticleTypes.FLAME, true, t.x + Math.cos(ang) * r, t.y + 0.3, t.z + Math.sin(ang) * r,
                            Math.cos(ang) * 0.25, 0.15 + RND.nextDouble() * 0.3, Math.sin(ang) * 0.25);
                }
                if (((int) a) % 2 == 0) mc.world.addParticle(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, true, t.x + RND.nextGaussian() * 3, t.y + 1,
                        t.z + RND.nextGaussian() * 3, 0, 0.12, 0);
            }
            if (a >= ScStrike.BEAM_END && a < ScStrike.BEAM_END + 400 && ((int) a) % 5 == 0)
                mc.world.addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, true, t.x + RND.nextGaussian() * 2.5, t.y + 0.5, t.z + RND.nextGaussian() * 2.5, 0, 0.06, 0);
        }
    }

    // ------------------------------------------------------------------------------------------- drawing

    static void render(WorldRenderContext ctx) {
        if (SHOTS.isEmpty() && STRIKES.isEmpty()) return;
        float pt = ctx.tickDelta();
        Camera cam = ctx.camera();
        Vec3d c = cam.getPos();
        MatrixStack ms = ctx.matrixStack();
        Matrix4f m = ms.peek().getPositionMatrix();
        Quaternionf q = cam.getRotation();
        Vector3f right = q.transform(new Vector3f(1, 0, 0)), up = q.transform(new Vector3f(0, 1, 0));
        float fogStart = RenderSystem.getShaderFogStart(), fogEnd = RenderSystem.getShaderFogEnd();
        RenderSystem.setShaderFogStart(1e7f);
        RenderSystem.setShaderFogEnd(2e7f);

        for (Shot s : SHOTS) drawShot(m, c, s, ScClient.ticks - s.at() + pt, right, up);
        for (Strike s : STRIKES) {
            float a = s.age(pt);
            if (a < ScStrike.BEAM_END + 30) drawSatellite(ms, c, s, a);
        }
        BUF.draw();
        for (Strike s : STRIKES) drawStrike(m, c, s, s.age(pt), right, up);
        BUF.draw();

        RenderSystem.setShaderFogStart(fogStart);
        RenderSystem.setShaderFogEnd(fogEnd);
    }

    private static Vector3f rel(Vec3d p, Vec3d c) {
        return new Vector3f((float) (p.x - c.x), (float) (p.y - c.y), (float) (p.z - c.z));
    }

    private static void v(VertexConsumer vc, Matrix4f m, Vector3f p, float u, float vv, float r, float g, float b, float a) {
        vc.vertex(m, p.x, p.y, p.z).color(r, g, b, a).texture(u, vv).light(FULL).normal(0, 1, 0).next();
    }

    private static void v(VertexConsumer vc, Matrix4f m, float x, float y, float z, float u, float vv, float r, float g, float b, float a) {
        vc.vertex(m, x, y, z).color(r, g, b, a).texture(u, vv).light(FULL).normal(0, 1, 0).next();
    }

    /** A camera-facing sprite. */
    static void sprite(VertexConsumer vc, Matrix4f m, Vector3f p, float size, float rot, Vector3f right, Vector3f up, float r, float g, float b, float a) {
        float cs = MathHelper.cos(rot) * size, sn = MathHelper.sin(rot) * size;
        Vector3f rx = new Vector3f(right).mul(cs).add(new Vector3f(up).mul(sn));
        Vector3f ry = new Vector3f(up).mul(cs).sub(new Vector3f(right).mul(sn));
        v(vc, m, new Vector3f(p).sub(rx).sub(ry), 0, 1, r, g, b, a);
        v(vc, m, new Vector3f(p).add(rx).sub(ry), 1, 1, r, g, b, a);
        v(vc, m, new Vector3f(p).add(rx).add(ry), 1, 0, r, g, b, a);
        v(vc, m, new Vector3f(p).sub(rx).add(ry), 0, 0, r, g, b, a);
    }

    /** A flat ribbon from a to b, turned toward the camera; u runs along it. */
    static void ribbon(VertexConsumer vc, Matrix4f m, Vector3f a, Vector3f b, float w, float u0, float u1, float r, float g, float bl, float al) {
        Vector3f d = new Vector3f(b).sub(a);
        Vector3f mid = new Vector3f(a).add(b).mul(0.5f);
        Vector3f side = new Vector3f(d).cross(mid);
        if (side.lengthSquared() < 1e-9f) return;
        side.normalize().mul(w * 0.5f);
        v(vc, m, new Vector3f(a).sub(side), u0, 1, r, g, bl, al);
        v(vc, m, new Vector3f(b).sub(side), u1, 1, r, g, bl, al);
        v(vc, m, new Vector3f(b).add(side), u1, 0, r, g, bl, al);
        v(vc, m, new Vector3f(a).add(side), u0, 0, r, g, bl, al);
    }

    /** A flat disc decal on the ground, rotated by rot. */
    static void decal(VertexConsumer vc, Matrix4f m, Vector3f c, float rad, float rot, float r, float g, float b, float a) {
        float cs = MathHelper.cos(rot) * rad, sn = MathHelper.sin(rot) * rad;
        v(vc, m, c.x - cs + sn, c.y, c.z - sn - cs, 0, 0, r, g, b, a);
        v(vc, m, c.x - cs - sn, c.y, c.z - sn + cs, 0, 1, r, g, b, a);
        v(vc, m, c.x + cs - sn, c.y, c.z + sn + cs, 1, 1, r, g, b, a);
        v(vc, m, c.x + cs + sn, c.y, c.z + sn - cs, 1, 0, r, g, b, a);
    }

    /** An open cylinder along Y from y0 to y1 (camera-relative centre), texture v scrolling, u wrapping round it. */
    static void column(VertexConsumer vc, Matrix4f m, Vector3f base, float y1rel, float rad, int sides, float uRep, float v0, float v1,
                       float r, float g, float b, float a) {
        for (int i = 0; i < sides; i++) {
            float a0 = MathHelper.TAU * i / sides, a1 = MathHelper.TAU * (i + 1) / sides;
            float x0 = base.x + MathHelper.cos(a0) * rad, z0 = base.z + MathHelper.sin(a0) * rad;
            float x1 = base.x + MathHelper.cos(a1) * rad, z1 = base.z + MathHelper.sin(a1) * rad;
            float u0 = uRep * i / sides, u1 = uRep * (i + 1) / sides;
            v(vc, m, x0, base.y, z0, u0, v0, r, g, b, a);
            v(vc, m, x1, base.y, z1, u1, v0, r, g, b, a);
            v(vc, m, x1, base.y + y1rel, z1, u1, v1, r, g, b, a);
            v(vc, m, x0, base.y + y1rel, z0, u0, v1, r, g, b, a);
        }
    }

    /** A camera-facing beam (flat, turned about its axis toward the eye) from a to b, textured across its width. */
    static void beam(VertexConsumer vc, Matrix4f m, Vector3f a, Vector3f b, float w, float v0, float v1, float r, float g, float bl, float al) {
        Vector3f d = new Vector3f(b).sub(a);
        Vector3f mid = new Vector3f(a).add(b).mul(0.5f);
        Vector3f side = new Vector3f(d).cross(mid);
        if (side.lengthSquared() < 1e-9f) return;
        side.normalize().mul(w * 0.5f);
        v(vc, m, new Vector3f(a).sub(side), 0, v0, r, g, bl, al);
        v(vc, m, new Vector3f(a).add(side), 1, v0, r, g, bl, al);
        v(vc, m, new Vector3f(b).add(side), 1, v1, r, g, bl, al);
        v(vc, m, new Vector3f(b).sub(side), 0, v1, r, g, bl, al);
    }

    // ------------------------------------------------------------------------------------------- railgun shots

    private static void drawShot(Matrix4f m, Vec3d c, Shot s, float age, Vector3f right, Vector3f up) {
        float f = 1f - age / 12f;
        if (f <= 0) return;
        Vector3f a = rel(s.from(), c), b = rel(s.to(), c);
        float flash = age < 3 ? 1f : f * f;
        VertexConsumer tr = BUF.getBuffer(BeamRenderLayers.textured(TRACER));
        ribbon(tr, m, a, b, 0.9f * flash + 0.2f, 0, 1, 1f, 0.25f, 0.08f, 0.7f * f);
        ribbon(tr, m, a, b, 0.22f, 0, 1, 1f, 0.92f, 0.75f, flash);
        VertexConsumer fl = BUF.getBuffer(BeamRenderLayers.textured(FLARE));
        sprite(fl, m, a, 1.2f + 1.5f * flash, age * 0.1f, right, up, 1f, 0.45f, 0.2f, flash);
        sprite(fl, m, b, 2.5f + age * 0.6f, -age * 0.08f, right, up, 1f, 0.6f, 0.35f, f);
        VertexConsumer sh = BUF.getBuffer(BeamRenderLayers.textured(SHOCK));
        sprite(sh, m, b, 1f + age * 1.1f, 0, right, up, 1f, 0.5f, 0.3f, 0.8f * f);
    }

    // ------------------------------------------------------------------------------------------- the strike

    private static void drawSatellite(MatrixStack ms, Vec3d c, Strike s, float a) {
        KrlMesh mesh = KrlModels.SAT;
        if (mesh == null) return;
        Vec3d p = s.sat();
        // it is only worth drawing if you can resolve it: up close in the cutscene, or a few pixels from the ground
        float scale = satScale(p, c);
        float charge = MathHelper.clamp(a / ScStrike.FIRE, 0, 1);
        float k = a < ScStrike.FIRE ? 0.2f + 0.8f * charge * charge : a < ScStrike.BEAM_END ? 1f : Math.max(0.15f, 1f - (a - ScStrike.BEAM_END) / 30f);
        float spin = a < ScStrike.FIRE ? (1 - charge) * 1.6f : 0f;
        ms.push();
        ms.translate(p.x - c.x, p.y - c.y - 1.75 * scale, p.z - c.z);
        ms.scale(scale, scale, scale);
        ms.multiply(RotationAxis.POSITIVE_Y.rotation(spin + s.id));
        String[] cur = {""};
        KrlMesh.Pose pose = (part, st) -> {
            cur[0] = part;
            return !part.equals("mount");
        };
        mesh.render(ms, BUF.getBuffer(RenderLayer.getEntityCutoutNoCull(KrlModels.SAT_TEX)), FULL, pose, null);
        mesh.glow(0).render(ms, BUF.getBuffer(RenderLayer.getEyes(KrlModels.SAT_GLOW)), FULL, pose, (nx, ny, nz, o) -> {
            float kk = cur[0].equals("emitter") ? k : 0.9f;
            o[0] = kk;
            o[1] = kk;
            o[2] = kk;
            o[3] = 1f;
        });
        ms.pop();
    }

    /** The satellite is drawn at least this big, and bigger with distance so it never vanishes. */
    static float satScale(Vec3d sat, Vec3d cam) {
        return (float) Math.max(2.2, sat.distanceTo(cam) / 70.0);
    }

    /** Where the emitter's lens is (the beam starts there). */
    static Vec3d emitter(Strike s, Vec3d cam) {
        return s.sat().add(0, -0.75 * satScale(s.sat(), cam), 0);
    }

    private static void drawStrike(Matrix4f m, Vec3d c, Strike s, float a, Vector3f right, Vector3f up) {
        Vec3d t = s.target;
        Vector3f g = rel(t, c);
        Vector3f gs = new Vector3f(g).add(0, 0.06f, 0);
        Vector3f sat = rel(s.sat(), c);
        Vector3f emitter = rel(emitter(s, c), c);
        float time = ScClient.ticks;

        // ---- reticle on the ground (spins up, tightens, gone with the impact)
        if (a < ScStrike.IMPACT + 12) {
            float in = MathHelper.clamp(a / 14f, 0, 1);
            float out = a > ScStrike.IMPACT ? 1f - (a - ScStrike.IMPACT) / 12f : 1f;
            float tighten = a < ScStrike.FIRE ? 1f - 0.35f * MathHelper.clamp((a - 20) / 30f, 0, 1) : 0.65f;
            float pulse = 0.75f + 0.25f * MathHelper.sin(a * (a < ScStrike.FIRE ? 0.4f + a * 0.012f : 1.5f));
            float rad = (6f + 10f * in) * tighten;
            VertexConsumer rv = BUF.getBuffer(BeamRenderLayers.textured(RETICLE));
            decal(rv, m, gs, rad, a * 0.03f, 1f, 0.12f, 0.06f, 0.85f * in * out * pulse);
            decal(rv, m, new Vector3f(gs).add(0, 0.02f, 0), rad * 0.62f, -a * 0.06f, 1f, 0.4f, 0.2f, 0.55f * in * out);
            // a thin laser designator line up into the sky
            VertexConsumer cv = BUF.getBuffer(BeamRenderLayers.textured(CORE));
            beam(cv, m, gs, emitter, 0.25f, 0, 3, 1f, 0.1f, 0.05f, 0.3f * in * out);
        }

        // ---- satellite flare overhead (charge, then the shot)
        if (a < ScStrike.BEAM_END + 20) {
            float charge = MathHelper.clamp(a / ScStrike.FIRE, 0, 1);
            float fire = a >= ScStrike.FIRE ? Math.max(0, 1 - (a - ScStrike.FIRE) / 20f) : 0;
            float beamOn = a >= ScStrike.FIRE && a < ScStrike.BEAM_END ? 1 : 0;
            VertexConsumer fl = BUF.getBuffer(BeamRenderLayers.textured(FLARE));
            float dist = sat.length();
            float sz = dist / 30f * (0.6f + charge * 0.8f + fire * 2.5f + beamOn * 0.6f);
            sprite(fl, m, emitter, sz, time * 0.01f, right, up, 1f, 0.3f + 0.4f * fire, 0.15f + 0.5f * fire, 0.4f + 0.6f * Math.max(charge, fire));
        }

        // ---- the beam
        if (a >= ScStrike.FIRE && a < ScStrike.BEAM_END + 8) {
            float front;   // how far down it has reached (0 = at the satellite, 1 = at the ground)
            front = MathHelper.clamp((a - ScStrike.FIRE) / (ScStrike.IMPACT - ScStrike.FIRE), 0, 1);
            front = front * front;
            float fade = a > ScStrike.BEAM_END ? 1f - (a - ScStrike.BEAM_END) / 8f : 1f;
            float swell = a < ScStrike.IMPACT ? 0.55f : 1f + 0.35f * Math.max(0, 1 - (a - ScStrike.IMPACT) / 10f);
            float flick = 0.9f + 0.1f * MathHelper.sin(time * 2.3f) * MathHelper.sin(time * 1.1f);
            float len = emitter.y - gs.y;
            Vector3f bottom = new Vector3f(emitter.x, emitter.y - len * front, emitter.z);
            float scroll = -time * 0.35f;
            float rep = len / 12f;
            float h = bottom.y - emitter.y;   // negative: the column runs down from the emitter
            VertexConsumer bv = BUF.getBuffer(BeamRenderLayers.textured(BEAM));
            column(bv, m, emitter, h, 4.8f * swell * flick, 14, 1, scroll, scroll + rep, 1f, 0.1f, 0.04f, 0.42f * fade);
            column(bv, m, emitter, h, 2.6f * swell, 12, 1, scroll * 1.4f, scroll * 1.4f + rep, 1f, 0.32f, 0.12f, 0.75f * fade);
            VertexConsumer sv = BUF.getBuffer(BeamRenderLayers.textured(SPIRAL));
            column(sv, m, emitter, h, 3.3f * swell, 16, 2, scroll * 2f, scroll * 2f + rep * 0.6f, 1f, 0.5f, 0.25f, 0.6f * fade);
            VertexConsumer cv = BUF.getBuffer(BeamRenderLayers.textured(CORE));
            beam(cv, m, emitter, bottom, 2.2f * swell, scroll * 3, scroll * 3 + rep, 1f, 0.95f, 0.85f, fade);
            beam(cv, m, emitter, bottom, 5f * swell, scroll * 2, scroll * 2 + rep, 1f, 0.6f, 0.35f, 0.6f * fade);
            // leading edge flare while it falls
            if (a < ScStrike.IMPACT) {
                VertexConsumer fl = BUF.getBuffer(BeamRenderLayers.textured(FLARE));
                float dist = new Vector3f(bottom).length();
                sprite(fl, m, bottom, Math.max(6f, dist / 25f), time * 0.05f, right, up, 1f, 0.8f, 0.6f, 1f);
            }
        }

        // ---- impact: flash, ground glow, shockwaves
        if (a >= ScStrike.IMPACT) {
            float ia = a - ScStrike.IMPACT;
            VertexConsumer fl = BUF.getBuffer(BeamRenderLayers.textured(FLARE));
            if (ia < 20) {
                float f = 1 - ia / 20f;
                sprite(fl, m, new Vector3f(g).add(0, 3, 0), 30f * (0.6f + 0.4f * f), 0.3f, right, up, 1f, 0.95f, 0.85f, f * f);
            }
            if (a < ScStrike.BEAM_END + 20) {
                float on = a < ScStrike.BEAM_END ? 1f : 1f - (a - ScStrike.BEAM_END) / 20f;
                decal(fl, m, gs, 11f + 1.5f * MathHelper.sin(time * 0.7f), time * 0.02f, 1f, 0.35f, 0.12f, 0.9f * on);
                sprite(fl, m, new Vector3f(g).add(0, 1.5f, 0), 9f, time * 0.03f, right, up, 1f, 0.5f, 0.2f, 0.7f * on);
            }
            VertexConsumer sh = BUF.getBuffer(BeamRenderLayers.textured(SHOCK));
            for (int k = 0; k < 2; k++) {
                float sa = ia - k * 7;
                if (sa < 0 || sa > 34) continue;
                float f = 1 - sa / 34f;
                decal(sh, m, new Vector3f(gs).add(0, 0.3f + k * 0.2f, 0), 4 + sa * (2.2f - k * 0.5f), k * 1.3f, 1f, 0.55f, 0.3f, f * 0.9f);
            }
            // the scorch scar stays behind
            float sf = ia < 10 ? ia / 10f : a > ScStrike.BEAM_END + SCORCH_LIFE - 200 ? Math.max(0, (ScStrike.BEAM_END + SCORCH_LIFE - a) / 200f) : 1f;
            VertexConsumer sc = BUF.getBuffer(BeamRenderLayers.translucent(SCORCH));
            decal(sc, m, new Vector3f(gs).add(0, -0.03f, 0), 13f, s.id * 0.7f, 1f, 1f, 1f, sf);
            // embers glowing in the scar after the beam
            if (a >= ScStrike.BEAM_END && a < ScStrike.BEAM_END + 600) {
                float e = 1f - (a - ScStrike.BEAM_END) / 600f;
                decal(fl, m, gs, 7f, -time * 0.004f, 1f, 0.25f, 0.06f, 0.55f * e * (0.85f + 0.15f * MathHelper.sin(time * 0.3f)));
            }
        }
    }
}

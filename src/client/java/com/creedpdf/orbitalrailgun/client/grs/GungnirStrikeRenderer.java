package com.creedpdf.orbitalrailgun.client.grs;

import com.creedpdf.orbitalrailgun.client.hud.RailgunHud;
import com.creedpdf.orbitalrailgun.client.render.BeamRenderLayers;
import com.creedpdf.orbitalrailgun.grs.Grs;
import com.creedpdf.orbitalrailgun.grs.GungnirStrikeEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Draws one Gungnir strike, all from the entity's tick counter:
 * <ol>
 *   <li><b>Release</b> - next to Jupiter in the sky a point of orange light starts to strobe: the spear breaking from
 *   its lap round the station. It swells and flares.</li>
 *   <li><b>Descent</b> - a few ticks of light: an orb with a white-hot core and a long orange wake tears down out of
 *   Jupiter's place in the sky. The target glows and the air ripples just before it lands.</li>
 *   <li><b>Impact</b> - flash, two ground shockwaves, a pillar of fire up the shaft, rock and burning debris thrown
 *   out on arcs, a dust wall rolling outward.</li>
 *   <li><b>Planted</b> - 179 blocks of black and orange metal driven 24 blocks into the crater, its rings turning,
 *   seams glowing, embers climbing it, the ground cracked molten round its foot.</li>
 *   <li><b>Recall</b> - the seams blaze, it rips itself out of the ground and accelerates back up into the sky as
 *   light.</li>
 * </ol>
 */
public class GungnirStrikeRenderer extends EntityRenderer<GungnirStrikeEntity> {
    private static final float SKY = 380f;
    private static final Vector3f SUN = new Vector3f(0.35f, 0.85f, 0.4f).normalize();

    private VertexConsumerProvider vcp;

    public GungnirStrikeRenderer(EntityRendererFactory.Context ctx) {
        super(ctx);
    }

    @Override
    public boolean shouldRender(GungnirStrikeEntity e, Frustum frustum, double x, double y, double z) {
        return true;
    }

    @Override
    public Identifier getTexture(GungnirStrikeEntity e) {
        return GrsModels.SPEAR_TEX;
    }

    private VertexConsumer add(Identifier tex) {
        return vcp.getBuffer(BeamRenderLayers.textured(tex));
    }

    private VertexConsumer blend(Identifier tex) {
        return vcp.getBuffer(BeamRenderLayers.translucent(tex));
    }

    private static Vector3f v3(Vec3d d) {
        return new Vector3f((float) d.x, (float) d.y, (float) d.z);
    }

    @Override
    public void render(GungnirStrikeEntity e, float yaw, float pt, MatrixStack ms, VertexConsumerProvider vcp, int light) {
        this.vcp = vcp;
        GrsDraw.camera(dispatcher.camera);
        Vec3d cam = dispatcher.camera.getPos();
        Vec3d basePos = e.getLerpedPos(pt);
        // the eye, relative to the strike's origin
        Vector3f eye = new Vector3f((float) (cam.x - basePos.x), (float) (cam.y - basePos.y), (float) (cam.z - basePos.z));
        float t = e.ticks(pt);
        int power = e.power();
        Vector3f up = v3(e.plantAxis());
        Vector3f sky = v3(e.skyDir());
        float time = e.age + pt;

        ms.push();
        Matrix4f m = ms.peek().getPositionMatrix();
        if (t < Grs.IMPACT) {
            descent(m, eye, sky, t, power, time);
        } else {
            float since = t - Grs.IMPACT;
            float recall = t >= Grs.RECALL ? MathHelper.clamp((t - Grs.RECALL) / Grs.RECALL_LEN, 0, 1) : 0f;
            spear(ms, up, since, recall, time);
            m = ms.peek().getPositionMatrix();
            ground(m, up, power, since, recall, time);
            impactFx(m, eye, up, since, power, time);
            if (recall <= 0) planted(m, up, since, time);
            else recallFx(m, eye, up, sky, recall, time);
        }
        ms.pop();

        // camera shake near the strike
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) {
            double d = mc.player.getPos().distanceTo(basePos);
            float near = (float) Math.max(0, 1 - d / 220.0);
            if (t > Grs.IMPACT - 10 && t < Grs.IMPACT) RailgunHud.shake(0.4f * near);
            if (e.ticks() == Grs.IMPACT && near > 0) {
                RailgunHud.triggerFireFlash(Math.min(1f, 0.3f + near));
                RailgunHud.shake(1.5f + 3.5f * near * (power / 10f + 0.4f));
            }
            if (e.ticks() > Grs.IMPACT && e.ticks() <= Grs.IMPACT + Grs.CARVE_TICKS && near > 0) RailgunHud.shake(0.6f * near);
            if (e.ticks() > Grs.RECALL + 20 && e.ticks() < Grs.RECALL + 34 && near > 0) RailgunHud.shake(0.8f * near);
        }
    }

    // --------------------------------------------------------------------------- release and descent

    private void descent(Matrix4f m, Vector3f eye, Vector3f sky, float t, int power, float time) {
        Vector3f far = new Vector3f(sky).mul(SKY);
        if (t < Grs.RELEASE) {
            // the strobe of the lapping spear beside Jupiter, swelling as it breaks orbit
            float k = GrsDraw.smooth(t / Grs.RELEASE);
            float lap = (time % 7f) / 7f;
            float strobe = (float) Math.exp(-lap * 6) * (1 - k) + k;
            float s = (2f + 9f * k * k) * (0.7f + 0.3f * strobe);
            GrsDraw.sprite(add(GrsDraw.ORB), m, far.x, far.y, far.z, s, 0, 1f, 0.55f + 0.4f * k, 0.15f + 0.5f * k, 1);
            GrsDraw.sprite(add(GrsDraw.FLARE), m, far.x, far.y, far.z, 6f + 34f * k * k, time * 0.04f, k, 0.5f * k, 0.12f * k, 1);
            // the target answers: a faint ring of light on the ground, tightening
            float tk = GrsDraw.smooth((t - Grs.RELEASE + 26) / 26f);
            if (tk > 0) targetGlow(m, power, tk, time);
            return;
        }
        // a few ticks of light down out of the sky
        float f = MathHelper.clamp((t - Grs.RELEASE) / Grs.DESCENT, 0, 1);
        float fe = f * f;
        Vector3f head = new Vector3f(sky).mul(SKY * (1 - fe));
        Vector3f tail = new Vector3f(sky).mul(SKY);
        float w = 2.6f + power * 0.45f;
        VertexConsumer beam = add(GrsDraw.BEAM);
        GrsDraw.beam(beam, m, head, tail, eye, w * 2.4f, time * 0.5f, 40f, 1f, 0.38f, 0.05f, 0.75f);
        GrsDraw.beam(beam, m, head, tail, eye, w * 1.1f, time * 0.8f, 26f, 1f, 0.65f, 0.25f, 0.9f);
        GrsDraw.beam(beam, m, head, tail, eye, w * 0.35f, time, 18f, 1f, 0.95f, 0.85f, 1f);
        GrsDraw.streak(add(GrsDraw.STREAK), m, new Vector3f(head).add(new Vector3f(sky).mul(60)), head, eye, w * 1.6f, 1f, 0.8f, 0.5f, 1);
        GrsDraw.sprite(add(GrsDraw.ORB), m, head.x, head.y, head.z, 8f + power * 1.2f, 0, 1f, 0.85f, 0.6f, 1);
        GrsDraw.sprite(add(GrsDraw.FLARE), m, head.x, head.y, head.z, 30f + power * 3f, time * 0.2f, 1f, 0.55f, 0.15f, 1);
        // the ground lights up under it
        targetGlow(m, power, 1f + f, time);
    }

    private void targetGlow(Matrix4f m, int power, float k, float time) {
        float R = (float) Grs.blastRadius(power);
        Vector3f c = new Vector3f(0, 0.15f, 0), a = new Vector3f(1, 0, 0), b = new Vector3f(0, 0, 1);
        float pulse = 0.75f + 0.25f * MathHelper.sin(time * 1.3f);
        GrsDraw.flat(add(GrsDraw.SHOCK), m, c, a, b, R * (1.6f - 0.6f * Math.min(1, k)), 1f * k * pulse * 0.6f, 0.4f * k * pulse * 0.6f,
                0.06f * k * 0.6f, 1);
        GrsDraw.sprite(add(GrsDraw.FLARE), m, 0, 1.5f, 0, 4 + 10 * k, time * 0.1f, 0.9f * k * 0.5f, 0.4f * k * 0.5f, 0.08f * k * 0.5f, 1);
    }

    // --------------------------------------------------------------------------- the spear

    private void spear(MatrixStack ms, Vector3f up, float since, float recall, float time) {
        if (GrsModels.SPEAR == null) return;
        float settle = GrsDraw.smooth(since / 5f);
        // on recall the spear is pulled out of the ground and accelerates away up its own axis
        float out = recall < 0.25f ? 0 : (recall - 0.25f) / 0.75f;
        float lift = out * out * out * 900f;
        if (lift > 700f) return;
        ms.push();
        // tip first: the model runs from its tip (y = 0) up to the pommel (y = 179)
        Quaternionf q = new Quaternionf().rotationTo(new Vector3f(0, 1, 0), up);
        q.rotateY((1 - settle) * 2.4f);
        float depth = Grs.PLANT_DEPTH * (0.25f + 0.75f * settle);
        ms.translate(up.x * (lift - depth), up.y * (lift - depth), up.z * (lift - depth));
        ms.multiply(q);
        // the quiver of impact
        if (since < 30 && recall <= 0) {
            float wob = (float) Math.exp(-since / 8.0) * 0.02f * MathHelper.sin(since * 3.1f);
            ms.multiply(new Quaternionf().rotateX(wob).rotateZ(wob * 0.7f));
        }
        Vector3f lv = new Vector3f(SUN.dot(GrsDraw.R), SUN.dot(GrsDraw.U), -SUN.dot(GrsDraw.F));
        GrsMesh.Shade shade = (nx, ny, nz, o) -> {
            float d = Math.max(0, nx * lv.x + ny * lv.y + nz * lv.z);
            float k = 0.32f + 0.75f * d;
            o[0] = k * 1.02f;
            o[1] = k;
            o[2] = k * 0.96f;
            o[3] = 1;
        };
        GrsMesh.Pose pose = (part, mst) -> {
            if (part.equals("rings")) {
                float sp = recall > 0 ? 0.04f + recall * 0.6f : 0.04f;
                mst.multiply(new Quaternionf().rotateY(time * sp));
            }
            return true;
        };
        GrsModels.SPEAR.renderNoOverlay(ms, vcp.getBuffer(BeamRenderLayers.solid(GrsModels.SPEAR_TEX)), GrsDraw.FULL, pose, shade);
        float heat = since < 40 ? 1.6f * (float) Math.exp(-since / 14.0) : 0;
        float glowK = (0.75f + 0.25f * MathHelper.sin(time * 0.25f)) + heat + recall * 3f;
        GrsMesh.Shade glow = (nx, ny, nz, o) -> {
            o[0] = glowK;
            o[1] = glowK;
            o[2] = glowK;
            o[3] = 1f;
        };
        GrsModels.SPEAR.renderNoOverlay(ms, vcp.getBuffer(BeamRenderLayers.textured(GrsModels.SPEAR_GLOW)), GrsDraw.FULL, pose, glow);
        if (recall > 0.25f) GrsModels.SPEAR.renderNoOverlay(ms, vcp.getBuffer(BeamRenderLayers.textured(GrsModels.SPEAR_GLOW)), GrsDraw.FULL, pose, glow);
        ms.pop();
    }

    // --------------------------------------------------------------------------- impact

    private static Vector3f[] groundBasis() {
        return new Vector3f[]{new Vector3f(1, 0, 0), new Vector3f(0, 0, 1)};
    }

    private void impactFx(Matrix4f m, Vector3f eye, Vector3f up, float since, int power, float time) {
        if (since > 140) return;
        float R = (float) Grs.blastRadius(power);
        Vector3f[] g = groundBasis();
        // the flash
        float flash = (float) Math.exp(-since / 4.0);
        if (flash > 0.01f) {
            GrsDraw.sprite(add(GrsDraw.FLARE), m, 0, R * 0.25f, 0, R * (1.4f + 2.6f * (1 - flash)), time * 0.05f, flash, 0.75f * flash, 0.4f * flash, 1);
            GrsDraw.sprite(add(GrsDraw.ORB), m, 0, R * 0.15f, 0, R * 0.9f * flash + 4, 0, 1f, 0.8f * flash, 0.5f * flash, 1);
        }
        // shockwaves along the ground
        for (int i = 0; i < 3; i++) {
            float st = since - i * 5;
            if (st < 0) continue;
            float k = st / (16f + power * 1.5f);
            if (k >= 1) continue;
            float rad = R * (0.25f + (1.8f + i * 0.5f) * k);
            float al = (float) Math.pow(1 - k, 1.6) * (i == 0 ? 1f : 0.6f);
            GrsDraw.flat(add(GrsDraw.SHOCK), m, new Vector3f(0, 0.3f + i * 0.4f, 0), g[0], g[1], rad, 1f * al, 0.48f * al, 0.1f * al, 1);
        }
        // a pillar of fire up the shaft
        float col = (float) Math.exp(-since / 12.0);
        if (col > 0.02f) {
            Vector3f top = new Vector3f(up).mul(30f + power * 6f);
            VertexConsumer b = add(GrsDraw.BEAM);
            GrsDraw.beam(b, m, new Vector3f(0, 0.2f, 0), top, eye, (4f + power * 0.6f) * col, time * 0.3f, 18f, 1f, 0.42f, 0.06f, col * 0.9f);
            GrsDraw.beam(b, m, new Vector3f(0, 0.2f, 0), new Vector3f(top).mul(0.7f), eye, (1.5f + power * 0.2f) * col, time * 0.5f, 12f, 1f, 0.85f, 0.6f, col);
        }
        // rock and burning debris on ballistic arcs (one pass per buffer: never interleave two layers)
        for (int pass = 0; pass < 2; pass++) {
            Random r = Random.create(0x6A9 + power * 31L);
            int n = 70 + power * 14;
            VertexConsumer vc = pass == 0 ? blend(GrsDraw.DUST) : add(GrsDraw.ORB);
            for (int i = 0; i < n; i++) {
                float ang = r.nextFloat() * MathHelper.TAU;
                float sp = (0.6f + r.nextFloat() * 1.4f) * (1.2f + power * 0.12f);
                float vy = 0.9f + r.nextFloat() * 1.8f + power * 0.08f;
                float size = 0.5f + r.nextFloat() * 1.3f;
                boolean hot = i % 3 == 0;
                if (hot != (pass == 1)) continue;
                float s = since * 0.9f;
                float x = MathHelper.cos(ang) * sp * s, z = MathHelper.sin(ang) * sp * s;
                float y = vy * s - 0.045f * s * s + 1f;
                if (y < -2 || since > 90) continue;
                float life = (float) Math.exp(-since / (hot ? 25.0 : 60.0));
                if (hot) GrsDraw.sprite(vc, m, x, y, z, size * 0.9f, 0, life, 0.5f * life, 0.1f * life, 1);
                else GrsDraw.sprite(vc, m, x, y, z, size, ang + since * 0.1f, 0.12f, 0.1f, 0.09f, Math.min(1, life * 1.4f));
            }
        }
        // the dust wall rolling outward and settling
        float dust = (float) Math.exp(-since / 60.0);
        if (dust > 0.03f) {
            Random d = Random.create(((long) power << 8) ^ 0x51ED);
            int k = 22 + power * 4;
            VertexConsumer db = blend(GrsDraw.DUST);
            for (int i = 0; i < k; i++) {
                float ang = i / (float) k * MathHelper.TAU + d.nextFloat() * 0.2f;
                float rr = R * (0.6f + 0.9f * d.nextFloat()) * (1 - (float) Math.exp(-since / 14.0)) + 2;
                float h = 2f + 5f * d.nextFloat() + since * 0.04f;
                float s = R * 0.3f + since * 0.12f;
                GrsDraw.sprite(db, m, MathHelper.cos(ang) * rr, h, MathHelper.sin(ang) * rr, s, ang + since * 0.004f, 0.28f, 0.22f, 0.18f, dust * 0.55f);
            }
        }
    }

    // --------------------------------------------------------------------------- planted / ground

    private void planted(Matrix4f m, Vector3f up, float since, float time) {
        float above = Grs.SPEAR_LENGTH - Grs.PLANT_DEPTH;
        float in = GrsDraw.smooth((since - 10) / 40f);
        if (in <= 0) return;
        // embers climbing the spear
        Random r = Random.create(0x9A71);
        VertexConsumer em = add(GrsDraw.ORB);
        for (int i = 0; i < 90; i++) {
            float sp = 0.004f + r.nextFloat() * 0.006f;
            float ph = (r.nextFloat() + time * sp) % 1f;
            float h = ph * above * 0.9f;
            float a = r.nextFloat() * MathHelper.TAU + time * 0.02f;
            float rad = 2.2f + r.nextFloat() * 4f + ph * 3f;
            Vector3f p = new Vector3f(up).mul(h).add(MathHelper.cos(a) * rad, 0, MathHelper.sin(a) * rad);
            float al = in * MathHelper.sin(ph * MathHelper.PI) * (0.6f + 0.4f * MathHelper.sin(time * 0.5f + i));
            GrsDraw.sprite(em, m, p.x, p.y, p.z, 0.35f + r.nextFloat() * 0.3f, 0, al, 0.45f * al, 0.08f * al, 1);
        }
        // heat at the foot and a glow at the head
        float pulse = 0.8f + 0.2f * MathHelper.sin(time * 0.15f);
        GrsDraw.sprite(add(GrsDraw.FLARE), m, 0, 2.5f, 0, 16f * pulse, time * 0.01f, 0.7f * in, 0.28f * in, 0.05f * in, 1);
        Vector3f head = new Vector3f(up).mul(above);
        GrsDraw.sprite(add(GrsDraw.ORB), m, head.x, head.y, head.z, 3.5f * pulse, 0, 1f * in, 0.55f * in, 0.15f * in, 1);
        GrsDraw.sprite(add(GrsDraw.FLARE), m, head.x, head.y, head.z, 14f * pulse, -time * 0.02f, 0.6f * in, 0.25f * in, 0.05f * in, 1);
        // smoke drifting off the crater
        Random s = Random.create(0x5E0C);
        VertexConsumer sm = blend(GrsDraw.DUST);
        for (int i = 0; i < 24; i++) {
            float ph = (s.nextFloat() + time * 0.0025f) % 1f;
            float a = s.nextFloat() * MathHelper.TAU;
            float rad = 3 + s.nextFloat() * 12;
            GrsDraw.sprite(sm, m, MathHelper.cos(a) * rad + ph * 8, 2 + ph * 40, MathHelper.sin(a) * rad, 3 + ph * 9, a + ph, 0.1f, 0.09f, 0.085f,
                    in * 0.4f * MathHelper.sin(ph * MathHelper.PI));
        }
    }

    private void ground(Matrix4f m, Vector3f up, int power, float since, float recall, float time) {
        float R = (float) Grs.blastRadius(power) * 0.95f * GrsDraw.smooth(Math.min(1f, since / Grs.CARVE_TICKS));
        float fade = 1 - recall;
        Vector3f[] g = groundBasis();
        if (R <= 0.5f) return;
        float crater = Grs.radius(power) * 0.55f;
        // the scorch sits on the crater floor's rim height; cracks glow and slowly cool
        float cool = 0.55f + 0.45f * (float) Math.exp(-since / 300.0);
        float flick = 0.85f + 0.15f * MathHelper.sin(time * 0.21f);
        GrsDraw.flat(blend(GrsDraw.SCORCH), m, new Vector3f(0, 0.06f, 0), g[0], g[1], R, 1, 1, 1, fade);
        GrsDraw.flat(add(GrsDraw.CRACKS), m, new Vector3f(0, 0.09f, 0), g[0], g[1], R, cool * flick * fade, 0.42f * cool * flick * fade,
                0.06f * fade, 1);
        if (crater > 1) GrsDraw.flat(add(GrsDraw.CRACKS), m, new Vector3f(0, -crater + 0.4f, 0), g[0], g[1], R * 0.6f, cool * fade, 0.4f * cool * fade,
                0.05f * fade, 1);
    }

    private void recallFx(Matrix4f m, Vector3f eye, Vector3f up, Vector3f sky, float recall, float time) {
        Vector3f far = new Vector3f(sky).mul(SKY);
        // the seams blaze and a column of light opens back to the sky
        float open = GrsDraw.smooth(recall * 4f) * (1 - GrsDraw.smooth((recall - 0.8f) / 0.2f));
        VertexConsumer b = add(GrsDraw.BEAM);
        GrsDraw.beam(b, m, new Vector3f(0, 0, 0), far, eye, 3.5f * open, -time * 0.9f, 40f, 1f, 0.45f, 0.08f, open * 0.8f);
        GrsDraw.beam(b, m, new Vector3f(0, 0, 0), far, eye, 1.0f * open, -time * 1.2f, 30f, 1f, 0.9f, 0.7f, open);
        // motes stream up after it
        Random r = Random.create(0x2E11);
        VertexConsumer em = add(GrsDraw.ORB);
        for (int i = 0; i < 60; i++) {
            float ph = (r.nextFloat() + time * 0.03f) % 1f;
            float h = ph * 260f;
            Vector3f p = new Vector3f(up).mul(h).add((r.nextFloat() - 0.5f) * 6, 0, (r.nextFloat() - 0.5f) * 6);
            GrsDraw.sprite(em, m, p.x, p.y, p.z, 0.6f, 0, open, 0.6f * open, 0.2f * open, 1);
        }
        // the flash as it leaves the ground
        float rip = (float) Math.exp(-Math.abs(recall - 0.3f) * 30);
        GrsDraw.sprite(add(GrsDraw.FLARE), m, 0, 3, 0, 40 * rip, time * 0.1f, rip, 0.6f * rip, 0.2f * rip, 1);
    }
}

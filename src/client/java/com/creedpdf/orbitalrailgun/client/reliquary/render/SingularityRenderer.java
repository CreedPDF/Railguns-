package com.creedpdf.orbitalrailgun.client.reliquary.render;

import com.creedpdf.orbitalrailgun.client.krl.KrlCompat;
import com.creedpdf.orbitalrailgun.client.render.BeamRenderLayers;
import com.creedpdf.orbitalrailgun.client.space.PlanetBlastFx;
import com.creedpdf.orbitalrailgun.reliquary.CosmicReliquary;
import com.creedpdf.orbitalrailgun.reliquary.entity.CustomSingularityEntity;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.random.Random;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.List;

/**
 * The singularity: a lightless event-horizon sphere, a tilted accretion disk of neon-blue light whose spiral arms
 * wind inward, a thin photon ring, and streaks of matter falling in from the edge of its 10-block reach. All of it
 * scales with the singularity's strength (grows in, holds, collapses).
 */
public class SingularityRenderer extends EntityRenderer<CustomSingularityEntity> {
    private static final Identifier BLANK = CosmicReliquary.id("textures/fx/blank.png");
    private static final List<Vector3f[]> SPHERE = PlanetBlastFx.icosphere(2);

    public SingularityRenderer(EntityRendererFactory.Context ctx) {
        super(ctx);
        shadowRadius = 0;
    }

    @Override
    public Identifier getTexture(CustomSingularityEntity e) {
        return BLANK;
    }

    @Override
    public boolean shouldRender(CustomSingularityEntity e, net.minecraft.client.render.Frustum f, double x, double y, double z) {
        return true;
    }

    @Override
    public void render(CustomSingularityEntity e, float yaw, float pt, MatrixStack ms, VertexConsumerProvider vcp, int light) {
        if (KrlCompat.shadowPass()) return;
        float s = e.strength(pt);
        if (s <= 0.01f) return;
        float time = e.age + pt;
        ms.push();
        Matrix4f m = ms.peek().getPositionMatrix();
        // event horizon: an unlit black sphere
        VertexConsumer solid = vcp.getBuffer(BeamRenderLayers.solid(BLANK));
        float R = 0.85f * s;
        for (Vector3f[] tri : SPHERE) {
            for (int k = 0; k < 4; k++) {
                Vector3f p = tri[Math.min(k, 2)];
                solid.vertex(m, p.x * R, p.y * R, p.z * R).color(0f, 0f, 0f, 1f).texture(0.5f, 0.5f).light(0xF000F0).normal(0, 1, 0).next();
            }
        }
        VertexConsumer g = vcp.getBuffer(BeamRenderLayers.GLOW);
        // accretion disk, tilted and turning, with spiral arms
        float tilt = 0.45f, rot = e.spin(pt);
        Vector3f u = new Vector3f(MathHelper.cos(rot), 0, MathHelper.sin(rot));
        Vector3f v = new Vector3f(-MathHelper.sin(rot) * MathHelper.cos(tilt), MathHelper.sin(tilt), MathHelper.cos(rot) * MathHelper.cos(tilt));
        int segs = 64, rings = 6;
        float r0 = 1.05f * s, r1 = 4.2f * s;
        for (int j = 0; j < rings; j++) {
            float ra = r0 + (r1 - r0) * j / rings, rb = r0 + (r1 - r0) * (j + 1) / rings;
            for (int i = 0; i < segs; i++) {
                float t0 = MathHelper.TAU * i / segs, t1 = MathHelper.TAU * (i + 1) / segs;
                float arm0 = 0.55f + 0.45f * MathHelper.sin(3 * t0 - ra * 1.6f + time * 0.3f);
                float arm1 = 0.55f + 0.45f * MathHelper.sin(3 * t1 - ra * 1.6f + time * 0.3f);
                float fallA = 1f - (float) j / rings, fallB = 1f - (float) (j + 1) / rings;
                vtx(g, m, u, v, t0, ra, 0.3f + 0.7f * fallA, arm0 * fallA * 0.75f * s);
                vtx(g, m, u, v, t1, ra, 0.3f + 0.7f * fallA, arm1 * fallA * 0.75f * s);
                vtx(g, m, u, v, t1, rb, 0.3f + 0.7f * fallB, arm1 * fallB * 0.75f * s);
                vtx(g, m, u, v, t0, rb, 0.3f + 0.7f * fallB, arm0 * fallB * 0.75f * s);
            }
        }
        // photon ring (camera-facing glow just outside the horizon)
        PlanetBlastFx.glowBall(g, m, new Vector3f(0, 0, 0), 1.25f * s, ReliquaryFx.NR, ReliquaryFx.NG, ReliquaryFx.NB, 0.5f * s);
        // matter streaking inward from the edge of its reach
        Random r = Random.create(e.getId());
        for (int i = 0; i < 26; i++) {
            Vector3f dir = new Vector3f(r.nextFloat() - 0.5f, (r.nextFloat() - 0.5f) * 0.6f, r.nextFloat() - 0.5f).normalize();
            float ph = ((time * 0.035f + r.nextFloat()) % 1f);
            float dist = (1f - ph * ph) * 9.5f * s + 1.1f * s;
            Vector3f head = new Vector3f(dir).mul(dist), tail = new Vector3f(dir).mul(dist + 0.9f + 1.5f * ph);
            ReliquaryFx.strip(g, m, head, tail, 0.06f, 0.6f, 0.85f, 1f, 0.6f * ph * s);
        }
        ms.pop();
        super.render(e, yaw, pt, ms, vcp, light);
    }

    private static void vtx(VertexConsumer g, Matrix4f m, Vector3f u, Vector3f v, float t, float r, float hot, float a) {
        float x = (u.x * MathHelper.cos(t) + v.x * MathHelper.sin(t)) * r, y = (u.y * MathHelper.cos(t) + v.y * MathHelper.sin(t)) * r,
                z = (u.z * MathHelper.cos(t) + v.z * MathHelper.sin(t)) * r;
        g.vertex(m, x, y, z).color(ReliquaryFx.NR + 0.6f * hot * hot, ReliquaryFx.NG + 0.3f * hot, 1f, a).next();
    }
}

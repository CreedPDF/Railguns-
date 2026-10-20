package com.creedpdf.orbitalrailgun.client.render;

import com.creedpdf.orbitalrailgun.client.space.BodyDraw;
import com.creedpdf.orbitalrailgun.client.space.PlanetBlastFx;
import com.creedpdf.orbitalrailgun.entity.MeteorEntity;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;

/**
 * A falling planet fragment: a tumbling, lumpy rock in the planet's own colours, wrapped in a
 * white-hot shock layer, dragging a long trail of fire and smoke.
 */
public class MeteorRenderer extends EntityRenderer<MeteorEntity> {
    private static final int LIGHT = LightmapTextureManager.MAX_LIGHT_COORDINATE;
    private static final List<Vector3f[]> ROCK = PlanetBlastFx.icosphere(2);

    public MeteorRenderer(EntityRendererFactory.Context ctx) {
        super(ctx);
    }

    @Override
    public boolean shouldRender(MeteorEntity entity, Frustum frustum, double x, double y, double z) {
        return true;
    }

    @Override
    public void render(MeteorEntity e, float yaw, float pt, MatrixStack matrices, VertexConsumerProvider vcp, int light) {
        Vec3d cam = dispatcher.camera.getPos();
        Vec3d pos = e.getLerpedPos(pt);
        Vector3f c = new Vector3f((float) (pos.x - cam.x), (float) (pos.y - cam.y), (float) (pos.z - cam.z));
        matrices.push();
        // render in camera-relative world space (camera at the origin) so billboards face it
        matrices.translate(-c.x, -c.y, -c.z);
        Matrix4f m = matrices.peek().getPositionMatrix();
        float s = e.size();
        float time = e.age + pt;
        Vec3d v = e.getVelocity();
        Vector3f dir = new Vector3f((float) v.x, (float) v.y, (float) v.z);
        if (dir.lengthSquared() < 1e-6f) dir.set(0, -1, 0);
        dir.normalize();
        Vector3f back = new Vector3f(dir).negate();

        // the rock
        VertexConsumer rock = vcp.getBuffer(BeamRenderLayers.solid(BodyDraw.albedo(e.body())));
        Quaternionf q = new Quaternionf().rotationXYZ(time * 0.11f, time * 0.07f + e.getId(), time * 0.05f);
        long seed = e.getUuid().getLeastSignificantBits();
        for (Vector3f[] t : ROCK) {
            Vector3f[] p = new Vector3f[3];
            float[] uu = new float[3], vv = new float[3];
            for (int k = 0; k < 3; k++) {
                float lump = 0.75f + 0.45f * hash(t[k], seed);
                p[k] = q.transform(new Vector3f(t[k]).mul(s * lump)).add(c);
                uu[k] = 0.5f + t[k].x * 0.12f;
                vv[k] = 0.5f + t[k].y * 0.12f;
            }
            Vector3f n = q.transform(new Vector3f(t[0]).add(t[1]).add(t[2]).normalize());
            // the leading face glows from the heat of entry
            float heat = Math.max(0f, n.dot(dir));
            float rr = 0.35f + 0.65f * heat, gg = 0.3f + 0.35f * heat, bb = 0.28f + 0.05f * heat;
            for (int k = 0; k < 4; k++) {
                int i = Math.min(k, 2);
                rock.vertex(m, p[i].x, p[i].y, p[i].z).color(rr, gg, bb, 1f).texture(uu[i], vv[i]).light(LIGHT).normal(0, 1, 0).next();
            }
        }

        // shock layer and glow
        VertexConsumer g = vcp.getBuffer(BeamRenderLayers.GLOW);
        float flick = 0.85f + 0.15f * MathHelper.sin(time * 2.3f);
        PlanetBlastFx.glowBall(g, m, new Vector3f(dir).mul(s * 0.4f).add(c), s * 1.5f, 1f, 0.95f, 0.8f, 0.8f * flick);
        PlanetBlastFx.glowBall(g, m, c, s * 3.2f, 1f, 0.55f, 0.18f, 0.45f * flick);
        PlanetBlastFx.glowBall(g, m, c, s * 7f, 1f, 0.4f, 0.1f, 0.15f * flick);

        // trail: white-hot core, orange plasma, long red tail
        VertexConsumer st = vcp.getBuffer(BeamRenderLayers.textured(PlanetBlastFx.STREAK));
        float len = 18f + s * 22f;
        Vector3f tail = new Vector3f(back).mul(len * 3f).add(c);
        PlanetBlastFx.streak(st, m, tail, dir, len * 3f, s * 2.2f, 0.9f, 0.18f, 0.04f, 0.5f);
        Vector3f tail2 = new Vector3f(back).mul(len * 1.4f).add(c);
        PlanetBlastFx.streak(st, m, tail2, dir, len * 1.4f, s * 1.5f, 1f, 0.5f, 0.1f, 0.85f);
        Vector3f tail3 = new Vector3f(back).mul(len * 0.6f).add(c);
        PlanetBlastFx.streak(st, m, tail3, dir, len * 0.6f, s * 0.9f, 1f, 0.92f, 0.75f, 1f);

        // churning fire behind it
        VertexConsumer fire = vcp.getBuffer(BeamRenderLayers.textured(PlanetBlastFx.FIRE));
        float[] col = new float[3];
        for (int i = 0; i < 18; i++) {
            float f = (i + (time * 0.6f) % 1f) / 18f;
            Vector3f p = new Vector3f(back).mul(f * len * 2.2f).add(c);
            PlanetBlastFx.blackbodyPublic(1f - f * 0.8f, col);
            float size = s * (1.2f + f * 4f);
            PlanetBlastFx.billboardPublic(fire, m, p, size, i * 1.7f + time * 0.05f, col[0], col[1], col[2], (1f - f) * 0.5f);
        }
        // and smoke beyond that
        VertexConsumer smoke = vcp.getBuffer(BeamRenderLayers.translucent(PlanetBlastFx.SMOKE));
        for (int i = 0; i < 26; i++) {
            float f = (i + (time * 0.3f) % 1f) / 26f;
            Vector3f p = new Vector3f(back).mul(len * (1.2f + f * 5f)).add(c);
            float size = s * (2.5f + f * 9f);
            PlanetBlastFx.billboardPublic(smoke, m, p, size, i * 2.3f, 0.16f, 0.14f, 0.13f, (1f - f) * 0.45f);
        }
        matrices.pop();
        super.render(e, yaw, pt, matrices, vcp, light);
    }

    private static float hash(Vector3f v, long seed) {
        long h = seed ^ (long) (v.x * 7919f) * 73856093L ^ (long) (v.y * 7919f) * 19349663L ^ (long) (v.z * 7919f) * 83492791L;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return (h & 0xFFFF) / 65535f;
    }

    @Override
    public Identifier getTexture(MeteorEntity entity) {
        return BodyDraw.albedo(entity.body());
    }
}

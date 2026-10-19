package com.creedpdf.orbitalrailgun.client.orbital;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.VertexSorter;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

import java.util.HashMap;
import java.util.Map;

/**
 * Low-level drawing for the orbital space scene: a perspective projection of our own, the
 * starfield, the sun, and the Earth (Blender sphere with day, city lights, ocean glint, a
 * drifting cloud shell and an atmospheric limb), all lit on the CPU per vertex.
 */
public final class SpaceRenderer {
    private SpaceRenderer() {}

    public static final Identifier DAY = RailgunMod.id("textures/environment/earth_day.png");
    public static final Identifier NIGHT = RailgunMod.id("textures/environment/earth_night.png");
    public static final Identifier OCEAN = RailgunMod.id("textures/environment/earth_ocean.png");
    public static final Identifier CLOUDS = RailgunMod.id("textures/environment/earth_clouds.png");
    public static final Identifier STARS = RailgunMod.id("textures/environment/stars.png");
    public static final Identifier SUN = RailgunMod.id("textures/environment/sun.png");
    public static final Identifier GLINT = RailgunMod.id("textures/environment/glint.png");
    public static final Identifier LENS = RailgunMod.id("textures/environment/lens_ring.png");
    public static final Identifier PUFF = RailgunMod.id("textures/environment/rcs_puff.png");
    public static final Identifier HEAD = RailgunMod.id("textures/environment/beam_head.png");

    private static final Map<Identifier, Integer> MIPPED = new HashMap<>();

    /** Builds mipmaps once per texture upload so the 4K maps stay crisp and shimmer-free. */
    public static void ensureMipmaps() {
        for (Identifier id : new Identifier[]{DAY, NIGHT, CLOUDS, STARS, OCEAN}) {
            AbstractTexture t = MinecraftClient.getInstance().getTextureManager().getTexture(id);
            int gl = t.getGlId();
            Integer done = MIPPED.get(id);
            if (done != null && done == gl) continue;
            GlStateManager._bindTexture(gl);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 12);
            GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            MIPPED.put(id, gl);
        }
    }

    public static void projection(float fovDeg, float aspect, float near, float far) {
        Matrix4f proj = new Matrix4f().setPerspective((float) Math.toRadians(fovDeg), aspect, near, far);
        RenderSystem.setProjectionMatrix(proj, VertexSorter.BY_DISTANCE);
    }

    public static void clearDepth() {
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, MinecraftClient.IS_SYSTEM_MAC);
    }

    // ---- starfield + sun --------------------------------------------------------------------------

    public static void stars(Matrix4f view, float brightness) {
        EarthMesh mesh = EarthMesh.get();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.disableBlend();
        RenderSystem.setShader(GameRenderer::getPositionTexColorProgram);
        RenderSystem.setShaderTexture(0, STARS);
        RenderSystem.setShaderColor(1, 1, 1, 1);
        BufferBuilder bb = Tessellator.getInstance().getBuffer();
        bb.begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_TEXTURE_COLOR);
        float R = 5000f;
        int c = (int) (MathHelper.clamp(brightness, 0, 1) * 255);
        for (int idx : mesh.tris) {
            bb.vertex(view, mesh.pos[idx * 3] * R, mesh.pos[idx * 3 + 1] * R, mesh.pos[idx * 3 + 2] * R)
                    .texture(mesh.uv[idx * 2], mesh.uv[idx * 2 + 1]).color(c, c, c, 255).next();
        }
        BufferRenderer.drawWithGlobalProgram(bb.end());
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
    }

    /** Camera-facing sprite. {@code right}/{@code up} are the camera axes in world space. */
    public static void sprite(Matrix4f view, Identifier tex, Vector3f c, float size, Vector3f right, Vector3f up,
                              float r, float g, float b, float a, boolean additive, boolean depthTest, float roll) {
        RenderSystem.enableBlend();
        if (additive) RenderSystem.blendFunc(GlStateManager.SrcFactor.SRC_ALPHA, GlStateManager.DstFactor.ONE);
        else RenderSystem.defaultBlendFunc();
        if (depthTest) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionTexColorProgram);
        RenderSystem.setShaderTexture(0, tex);
        BufferBuilder bb = Tessellator.getInstance().getBuffer();
        bb.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE_COLOR);
        float cr = MathHelper.cos(roll), sr = MathHelper.sin(roll);
        Vector3f rr = new Vector3f(right).mul(cr).add(new Vector3f(up).mul(sr)).mul(size * 0.5f);
        Vector3f uu = new Vector3f(up).mul(cr).sub(new Vector3f(right).mul(sr)).mul(size * 0.5f);
        int R = col(r), G = col(g), B = col(b), A = col(a);
        bb.vertex(view, c.x - rr.x - uu.x, c.y - rr.y - uu.y, c.z - rr.z - uu.z).texture(0, 1).color(R, G, B, A).next();
        bb.vertex(view, c.x + rr.x - uu.x, c.y + rr.y - uu.y, c.z + rr.z - uu.z).texture(1, 1).color(R, G, B, A).next();
        bb.vertex(view, c.x + rr.x + uu.x, c.y + rr.y + uu.y, c.z + rr.z + uu.z).texture(1, 0).color(R, G, B, A).next();
        bb.vertex(view, c.x - rr.x + uu.x, c.y - rr.y + uu.y, c.z - rr.z + uu.z).texture(0, 0).color(R, G, B, A).next();
        BufferRenderer.drawWithGlobalProgram(bb.end());
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
    }

    /** A glowing ribbon from a to b that always faces the camera (camera at the origin). */
    public static void ribbon(Matrix4f view, Vector3f a, Vector3f b, float widthA, float widthB,
                              float r, float g, float bl, float alphaA, float alphaB) {
        Vector3f d = new Vector3f(b).sub(a);
        Vector3f mid = new Vector3f(a).add(b).mul(0.5f);
        Vector3f side = new Vector3f(d).cross(mid);
        if (side.lengthSquared() < 1e-12f) return;
        side.normalize();
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SrcFactor.SRC_ALPHA, GlStateManager.DstFactor.ONE);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        BufferBuilder bb = Tessellator.getInstance().getBuffer();
        bb.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        for (float k : new float[]{1f, 2.6f, 6f}) {
            float fa = k == 1f ? 1f : k == 2.6f ? 0.35f : 0.12f;
            Vector3f sa = new Vector3f(side).mul(widthA * k * 0.5f), sb = new Vector3f(side).mul(widthB * k * 0.5f);
            int R = col(r), G = col(g), B = col(bl);
            bb.vertex(view, a.x - sa.x, a.y - sa.y, a.z - sa.z).color(R, G, B, col(alphaA * fa)).next();
            bb.vertex(view, a.x + sa.x, a.y + sa.y, a.z + sa.z).color(R, G, B, col(alphaA * fa)).next();
            bb.vertex(view, b.x + sb.x, b.y + sb.y, b.z + sb.z).color(R, G, B, col(alphaB * fa)).next();
            bb.vertex(view, b.x - sb.x, b.y - sb.y, b.z - sb.z).color(R, G, B, col(alphaB * fa)).next();
        }
        BufferRenderer.drawWithGlobalProgram(bb.end());
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        RenderSystem.depthMask(true);
    }

    // ---- the Earth ---------------------------------------------------------------------------------

    /**
     * @param center Earth centre relative to the camera (km)
     * @param rot    planet rotation (axial tilt + spin)
     * @param sun    unit vector toward the sun
     * @param cloudSpin extra rotation of the cloud shell (radians about the planet axis)
     */
    public static void earth(Matrix4f view, Vector3f center, float radius, Quaternionf rot, Quaternionf cloudRot, Vector3f sun) {
        ensureMipmaps();
        EarthMesh mesh = EarthMesh.get();
        int nv = mesh.vertexCount();
        float[] wx = new float[nv], wy = new float[nv], wz = new float[nv];   // world normals
        float[] lam = new float[nv], spec = new float[nv], rim = new float[nv];
        Vector3f n = new Vector3f();
        for (int i = 0; i < nv; i++) {
            n.set(mesh.pos[i * 3], mesh.pos[i * 3 + 1], mesh.pos[i * 3 + 2]);
            rot.transform(n);
            wx[i] = n.x; wy[i] = n.y; wz[i] = n.z;
            lam[i] = n.dot(sun);
            // view vector: from the surface point to the camera (camera at the origin)
            float px = center.x + n.x * radius, py = center.y + n.y * radius, pz = center.z + n.z * radius;
            float vl = (float) Math.sqrt(px * px + py * py + pz * pz);
            float vx = -px / vl, vy = -py / vl, vz = -pz / vl;
            float ndv = n.x * vx + n.y * vy + n.z * vz;
            rim[i] = ndv;
            // Blinn-Phong half vector
            float hx = sun.x + vx, hy = sun.y + vy, hz = sun.z + vz;
            float hl = (float) Math.sqrt(hx * hx + hy * hy + hz * hz);
            float ndh = Math.max(0, (n.x * hx + n.y * hy + n.z * hz) / hl);
            spec[i] = lam[i] > 0 ? (float) Math.pow(ndh, 60) * 1.6f + (float) Math.pow(ndh, 8) * 0.12f : 0f;
        }
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.disableCull();
        BufferBuilder bb = Tessellator.getInstance().getBuffer();

        // 1. day side (opaque)
        RenderSystem.disableBlend();
        RenderSystem.depthMask(true);
        RenderSystem.setShader(GameRenderer::getPositionTexColorProgram);
        RenderSystem.setShaderTexture(0, DAY);
        bb.begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_TEXTURE_COLOR);
        for (int idx : mesh.tris) {
            float l = lam[idx];
            float d = smooth(-0.12f, 0.35f, l);
            float k = 0.03f + 1.12f * d * (0.55f + 0.45f * Math.max(0, l));
            // warm the terminator slightly, cool the deep night
            float tr = (float) Math.exp(-(l / 0.12f) * (l / 0.12f)) * 0.25f;
            bb.vertex(view, center.x + wx[idx] * radius, center.y + wy[idx] * radius, center.z + wz[idx] * radius)
                    .texture(mesh.uv[idx * 2], mesh.uv[idx * 2 + 1])
                    .color(col(k + tr), col(k + tr * 0.45f), col(k * 1.02f + 0.01f), 255).next();
        }
        BufferRenderer.drawWithGlobalProgram(bb.end());

        // 2. city lights on the night side (additive)
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SrcFactor.SRC_ALPHA, GlStateManager.DstFactor.ONE);
        RenderSystem.depthMask(false);
        RenderSystem.setShaderTexture(0, NIGHT);
        bb.begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_TEXTURE_COLOR);
        for (int idx : mesh.tris) {
            float night = 1f - smooth(-0.18f, 0.06f, lam[idx]);
            bb.vertex(view, center.x + wx[idx] * radius, center.y + wy[idx] * radius, center.z + wz[idx] * radius)
                    .texture(mesh.uv[idx * 2], mesh.uv[idx * 2 + 1]).color(255, 255, 255, col(night * 1.0f)).next();
        }
        BufferRenderer.drawWithGlobalProgram(bb.end());

        // 3. sun glint on the oceans (ocean mask texture: white, alpha = water)
        RenderSystem.setShaderTexture(0, OCEAN);
        RenderSystem.setShaderColor(1, 1, 1, 1);
        bb.begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_TEXTURE_COLOR);
        for (int idx : mesh.tris) {
            float s = Math.min(1f, spec[idx]);
            bb.vertex(view, center.x + wx[idx] * radius, center.y + wy[idx] * radius, center.z + wz[idx] * radius)
                    .texture(mesh.uv[idx * 2], mesh.uv[idx * 2 + 1]).color(col(s), col(s * 0.96f), col(s * 0.88f), 255).next();
        }
        BufferRenderer.drawWithGlobalProgram(bb.end());

        // 4. cloud shell
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderTexture(0, CLOUDS);
        float cr = radius * 1.006f;
        bb.begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_TEXTURE_COLOR);
        for (int idx : mesh.tris) {
            n.set(mesh.pos[idx * 3], mesh.pos[idx * 3 + 1], mesh.pos[idx * 3 + 2]);
            cloudRot.transform(n);
            float l = n.dot(sun);
            float d = 0.04f + 1.05f * smooth(-0.1f, 0.3f, l);
            float tr = (float) Math.exp(-(l / 0.1f) * (l / 0.1f)) * 0.35f;
            bb.vertex(view, center.x + n.x * cr, center.y + n.y * cr, center.z + n.z * cr)
                    .texture(mesh.uv[idx * 2], mesh.uv[idx * 2 + 1])
                    .color(col(d + tr), col(d + tr * 0.5f), col(d), col(0.95f)).next();
        }
        BufferRenderer.drawWithGlobalProgram(bb.end());

        // 5. atmosphere: a limb glow, blue on the day side, orange at the terminator
        RenderSystem.blendFunc(GlStateManager.SrcFactor.SRC_ALPHA, GlStateManager.DstFactor.ONE);
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        float ar = radius * 1.022f;
        bb.begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_COLOR);
        for (int idx : mesh.tris) {
            float px = center.x + wx[idx] * ar, py = center.y + wy[idx] * ar, pz = center.z + wz[idx] * ar;
            float vl = (float) Math.sqrt(px * px + py * py + pz * pz);
            float ndv = -(wx[idx] * px + wy[idx] * py + wz[idx] * pz) / vl;
            float edge = (float) Math.pow(1f - Math.abs(ndv), 2.6f);
            float l = lam[idx];
            float light = smooth(-0.3f, 0.35f, l);
            float term = (float) Math.exp(-((l - 0.02f) / 0.14f) * ((l - 0.02f) / 0.14f));
            float a = edge * (0.15f + 0.95f * light) + 0.05f * light;
            bb.vertex(view, px, py, pz).color(col(0.32f + 0.6f * term), col(0.58f + 0.1f * term), col(1.0f - 0.5f * term), col(Math.min(1f, a))).next();
        }
        BufferRenderer.drawWithGlobalProgram(bb.end());

        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
    }

    // ---------------------------------------------------------------------------------------------

    static float smooth(float e0, float e1, float x) {
        float t = MathHelper.clamp((x - e0) / (e1 - e0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    static int col(float v) {
        return MathHelper.clamp((int) (v * 255f), 0, 255);
    }
}

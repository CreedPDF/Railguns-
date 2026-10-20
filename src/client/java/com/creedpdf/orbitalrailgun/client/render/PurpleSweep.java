package com.creedpdf.orbitalrailgun.client.render;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.entity.RailgunEntity;
import com.creedpdf.orbitalrailgun.entity.RailgunState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.renderer.layer.AutoGlowingGeoLayer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;

import java.io.InputStream;

/**
 * While ARES-9 charges a rift, every red part of it - paint and lights - turns dark purple, the
 * colour sweeping slowly down from the tip of the rail to the ground. It stays purple while the
 * rift is open and bleeds back to red from the bottom up when it closes.
 *
 * <p>Purple versions of the albedo and the glowmask are generated once from the real textures.
 * The model is re-rendered with them through a vertex consumer that fades each vertex in by its
 * height, and the red glow layer is faded out by the same front.</p>
 */
public final class PurpleSweep {
    private PurpleSweep() {}

    private static final Identifier SRC = RailgunMod.id("textures/entity/railgun.png");
    private static final Identifier SRC_GLOW = RailgunMod.id("textures/entity/railgun_glowmask.png");
    public static final Identifier PURPLE = RailgunMod.id("dynamic/railgun_purple");
    public static final Identifier PURPLE_GLOW = RailgunMod.id("dynamic/railgun_purple_glow");
    /** Model height swept (blocks): rail tip when it points straight up. */
    private static final float TOP = 27f;
    private static boolean ready, failed;

    /** 0 = all red, 1 = all purple (the front position). */
    public static float progress(RailgunEntity e, float pt) {
        if (e.getState() != RailgunState.RIFT) return 0f;
        float t = e.getStateTicks() + pt;
        if (t < RailgunEntity.RIFT_FIRE + 6) return MathHelper.clamp(t / (RailgunEntity.RIFT_FIRE + 6f), 0f, 1f);
        if (t < RailgunEntity.RIFT_SNAP) return 1f;
        return MathHelper.clamp(1f - (t - RailgunEntity.RIFT_SNAP) / (RailgunEntity.RIFT_TICKS - RailgunEntity.RIFT_SNAP - 10f), 0f, 1f);
    }

    private static boolean ensure() {
        if (ready) return true;
        if (failed) return false;
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            mc.getTextureManager().registerTexture(PURPLE, new NativeImageBackedTexture(recolour(SRC, false)));
            mc.getTextureManager().registerTexture(PURPLE_GLOW, new NativeImageBackedTexture(recolour(SRC_GLOW, true)));
            ready = true;
        } catch (Exception ex) {
            RailgunMod.LOGGER.warn("ARES-9 purple sweep textures unavailable", ex);
            failed = true;
        }
        return ready;
    }

    /** Keeps only the red pixels, recoloured to dark purple (glow: a brighter violet). */
    private static NativeImage recolour(Identifier id, boolean glow) throws Exception {
        MinecraftClient mc = MinecraftClient.getInstance();
        NativeImage src;
        try (InputStream in = mc.getResourceManager().getResource(id).orElseThrow().getInputStream()) {
            src = NativeImage.read(in);
        }
        int w = src.getWidth(), h = src.getHeight();
        NativeImage out = new NativeImage(w, h, true);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = src.getColor(x, y); // ABGR
                int a = (c >>> 24) & 255, b = (c >> 16) & 255, g = (c >> 8) & 255, r = c & 255;
                int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
                boolean red = a > 8 && max > 50 && r == max && (max - min) > max * 0.45f && g < r * 0.62f && b < r * 0.7f;
                if (!red) {
                    out.setColor(x, y, 0);
                    continue;
                }
                float v = max / 255f;
                float pr, pg, pb;
                if (glow) {
                    pr = 0.62f * v + 0.08f; pg = 0.18f * v; pb = 0.95f * v + 0.05f;
                } else {
                    pr = 0.34f * v; pg = 0.08f * v; pb = 0.52f * v;
                }
                int R = (int) (MathHelper.clamp(pr, 0, 1) * 255), G = (int) (MathHelper.clamp(pg, 0, 1) * 255), B = (int) (MathHelper.clamp(pb, 0, 1) * 255);
                out.setColor(x, y, (a << 24) | (B << 16) | (G << 8) | R);
            }
        }
        src.close();
        return out;
    }

    // ---- layers --------------------------------------------------------------------------------

    /** Purple paint + purple lights, revealed by the descending front. */
    public static final class Layer extends GeoRenderLayer<RailgunEntity> {
        public Layer(GeoRenderer<RailgunEntity> renderer) {
            super(renderer);
        }

        @Override
        public void render(MatrixStack poseStack, RailgunEntity e, BakedGeoModel model, RenderLayer renderType,
                           VertexConsumerProvider bufferSource, VertexConsumer buffer, float pt, int light, int overlay) {
            float p = progress(e, pt);
            if (p <= 0.001f || !ensure()) return;
            Front f = Front.of(poseStack, p);
            RenderLayer paint = RenderLayer.getEntityTranslucent(PURPLE);
            getRenderer().reRender(model, poseStack, bufferSource, e, paint,
                    new SweepConsumer(bufferSource.getBuffer(paint), f, false), pt, light, OverlayTexture.DEFAULT_UV, 1f, 1f, 1f, 1f);
            RenderLayer glow = RenderLayer.getEntityTranslucentEmissive(PURPLE_GLOW);
            getRenderer().reRender(model, poseStack, bufferSource, e, glow,
                    new SweepConsumer(bufferSource.getBuffer(glow), f, false), pt, 0xF000F0, OverlayTexture.DEFAULT_UV, 1f, 1f, 1f, 1f);
        }
    }

    /** The normal red glow, faded out behind the front. */
    public static final class RedGlowLayer extends AutoGlowingGeoLayer<RailgunEntity> {
        public RedGlowLayer(GeoRenderer<RailgunEntity> renderer) {
            super(renderer);
        }

        @Override
        public void render(MatrixStack poseStack, RailgunEntity e, BakedGeoModel model, RenderLayer renderType,
                           VertexConsumerProvider bufferSource, VertexConsumer buffer, float pt, int light, int overlay) {
            float p = progress(e, pt);
            if (p <= 0.001f || !ensure()) {
                super.render(poseStack, e, model, renderType, bufferSource, buffer, pt, light, overlay);
                return;
            }
            Front f = Front.of(poseStack, p);
            RenderLayer emissive = getRenderType(e);
            getRenderer().reRender(model, poseStack, bufferSource, e, emissive,
                    new SweepConsumer(bufferSource.getBuffer(emissive), f, true), pt, 0xF000F0, OverlayTexture.DEFAULT_UV, 1f, 1f, 1f, 1f);
        }
    }

    /** Height of the colour front, measured along the entity's up axis in render space. */
    record Front(float ox, float oy, float oz, float ux, float uy, float uz, float front) {
        static Front of(MatrixStack ms, float p) {
            Matrix4f m = ms.peek().getPositionMatrix();
            Vector4f o = m.transform(new Vector4f(0, 0, 0, 1));
            Vector4f u = m.transform(new Vector4f(0, 1, 0, 0));
            float len = (float) Math.sqrt(u.x * u.x + u.y * u.y + u.z * u.z);
            if (len < 1e-5f) len = 1f;
            float front = TOP - p * (TOP + 1.5f);
            return new Front(o.x, o.y, o.z, u.x / len, u.y / len, u.z / len, front);
        }

        /** 1 above the front (purple), 0 below, with a soft 1.2-block edge. */
        float purple(float x, float y, float z) {
            float h = (x - ox) * ux + (y - oy) * uy + (z - oz) * uz;
            float t = MathHelper.clamp((h - front + 0.6f) / 1.2f, 0f, 1f);
            return t * t * (3f - 2f * t);
        }
    }

    /** Delegating vertex consumer that scales alpha by the sweep. */
    static final class SweepConsumer implements VertexConsumer {
        private final VertexConsumer d;
        private final Front f;
        private final boolean inverse;

        SweepConsumer(VertexConsumer delegate, Front f, boolean inverse) {
            this.d = delegate;
            this.f = f;
            this.inverse = inverse;
        }

        @Override
        public void vertex(float x, float y, float z, float red, float green, float blue, float alpha, float u, float v,
                           int overlay, int light, float nx, float ny, float nz) {
            float k = f.purple(x, y, z);
            if (inverse) k = 1f - k;
            d.vertex(x, y, z, red, green, blue, alpha * k, u, v, overlay, light, nx, ny, nz);
        }

        @Override
        public VertexConsumer vertex(double x, double y, double z) {
            d.vertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer color(int r, int g, int b, int a) {
            d.color(r, g, b, a);
            return this;
        }

        @Override
        public VertexConsumer texture(float u, float v) {
            d.texture(u, v);
            return this;
        }

        @Override
        public VertexConsumer overlay(int u, int v) {
            d.overlay(u, v);
            return this;
        }

        @Override
        public VertexConsumer light(int u, int v) {
            d.light(u, v);
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            d.normal(x, y, z);
            return this;
        }

        @Override
        public void next() {
            d.next();
        }

        @Override
        public void fixedColor(int r, int g, int b, int a) {
            d.fixedColor(r, g, b, a);
        }

        @Override
        public void unfixColor() {
            d.unfixColor();
        }
    }
}

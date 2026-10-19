package com.creedpdf.orbitalrailgun.client.ares0;

import com.creedpdf.orbitalrailgun.client.render.BeamRenderLayers;
import com.creedpdf.orbitalrailgun.client.render.BeamRenderer;
import com.creedpdf.orbitalrailgun.entity.Ares0Entity;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.renderer.layer.AutoGlowingGeoLayer;

/** Renders ARES-0, its charge-up and the planet-killing beam that climbs into the sky. */
public class Ares0Renderer extends GeoEntityRenderer<Ares0Entity> {
    public Ares0Renderer(EntityRendererFactory.Context ctx) {
        super(ctx, new Ares0Model());
        addRenderLayer(new AutoGlowingGeoLayer<>(this));
        this.shadowRadius = 5.5f;
        this.shadowOpacity = 0.8f;
    }

    @Override
    public void render(Ares0Entity e, float yaw, float pt, MatrixStack ms, VertexConsumerProvider vcp, int light) {
        super.render(e, yaw, pt, ms, vcp, light);
    }

    @Override
    public boolean shouldRender(Ares0Entity e, Frustum frustum, double x, double y, double z) {
        return true;
    }
}

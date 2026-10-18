package com.creedpdf.orbitalrailgun.client.render;

import com.creedpdf.orbitalrailgun.entity.RailgunEntity;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.renderer.layer.AutoGlowingGeoLayer;

/**
 * Renders the railgun model through GeckoLib, then draws the beam on top.
 *
 * <p>Render order per frame:</p>
 * <ol>
 *   <li>{@code super.render} – GeckoLib evaluates the animation controller, applies
 *       {@link RailgunModel#setCustomAnimations} (turret aim) and draws the textured model.</li>
 *   <li>{@link AutoGlowingGeoLayer} – re-draws the pixels marked in
 *       {@code railgun_glowmask.png} full-bright (the red coils and status lights).</li>
 *   <li>{@link BeamRenderer} – while charging or firing, appends beam geometry to the custom
 *       additive layer. GeckoLib pops its own matrix push before returning, so the stack is back
 *       at the entity origin and beam coordinates are entity-relative.</li>
 * </ol>
 */
public class RailgunEntityRenderer extends GeoEntityRenderer<RailgunEntity> {

    public RailgunEntityRenderer(EntityRendererFactory.Context context) {
        super(context, new RailgunModel());
        addRenderLayer(new AutoGlowingGeoLayer<>(this));
        this.shadowRadius = 1.8f;
        this.shadowOpacity = 0.8f;
    }

    @Override
    public void render(RailgunEntity entity, float entityYaw, float partialTick, MatrixStack poseStack,
                       VertexConsumerProvider bufferSource, int packedLight) {
        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);

        if (entity.isBeamActive()) {
            poseStack.push();
            BeamRenderer.render(entity, partialTick, poseStack, bufferSource);
            poseStack.pop();
        }
    }

    /**
     * Vanilla culls an entity once its bounding box leaves the view frustum. The beam can extend
     * hundreds of blocks away from the platform, so while it is active we always render; the
     * beam's own geometry is still clipped by the GPU.
     */
    @Override
    public boolean shouldRender(RailgunEntity entity, Frustum frustum, double x, double y, double z) {
        return entity.isBeamActive() || super.shouldRender(entity, frustum, x, y, z);
    }
}

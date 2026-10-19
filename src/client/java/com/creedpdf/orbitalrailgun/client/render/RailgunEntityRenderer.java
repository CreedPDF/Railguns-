package com.creedpdf.orbitalrailgun.client.render;

import com.creedpdf.orbitalrailgun.entity.RailgunEntity;
import com.creedpdf.orbitalrailgun.entity.RailgunState;
import com.creedpdf.orbitalrailgun.util.RailgunConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.renderer.layer.AutoGlowingGeoLayer;

import java.util.List;
import java.util.Locale;

/**
 * Renders the railgun through GeckoLib, then the beam / charge effects, then the holographic
 * readout above its control panel.
 *
 * <p>Order per frame: {@code super.render} (animation, procedural aim, textured model),
 * {@link AutoGlowingGeoLayer} (emissive parts from the glowmask), {@link BeamRenderer} (only
 * while charging or firing; the actual beam only exists while firing), then the panel text.
 * GeckoLib pops its matrix push before returning, so the stack is back at the entity origin.</p>
 */
public class RailgunEntityRenderer extends GeoEntityRenderer<RailgunEntity> {

    public RailgunEntityRenderer(EntityRendererFactory.Context context) {
        super(context, new RailgunModel());
        addRenderLayer(new AutoGlowingGeoLayer<>(this));
        this.shadowRadius = 2.5f;
        this.shadowOpacity = 0.9f;
    }

    @Override
    public void render(RailgunEntity entity, float entityYaw, float partialTick, MatrixStack poseStack,
                       VertexConsumerProvider bufferSource, int packedLight) {
        this.shadowRadius = entity.getState().isPodForm() ? 2.4f : 7.0f;
        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);

        if (entity.isBeamActive() || entity.isChargeVisible()) {
            poseStack.push();
            BeamRenderer.render(entity, partialTick, poseStack, bufferSource);
            poseStack.pop();
        }
        poseStack.push();
        LoadoutVfx.always(entity, partialTick, poseStack, bufferSource);
        poseStack.pop();
        renderPanelReadout(entity, partialTick, poseStack, bufferSource);
    }

    /**
     * A small hologram above the control panel telling the player what using it will do
     * ("RETRACT PANEL", "DEPLOYING 42%", "RETRACT", "CONFIRM RETRACT?", "RETURN TO ORBIT"...).
     * Billboarded toward the camera like a name tag so it is always readable.
     */
    private void renderPanelReadout(RailgunEntity entity, float tickDelta, MatrixStack matrices, VertexConsumerProvider vcp) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || com.creedpdf.orbitalrailgun.client.panel.PanelScreen.active() != null) return;
        Vec3d panel = entity.getPanelPos();
        double dist = client.player.getEyePos().distanceTo(panel);
        if (dist > 16) return;
        List<String> lines = panelLines(entity);
        if (lines.isEmpty()) return;
        boolean inReach = dist <= RailgunConfig.PANEL_REACH;

        Vec3d origin = entity.getLerpedPos(tickDelta);
        matrices.push();
        matrices.translate(panel.x - origin.x, panel.y - origin.y + 1.25, panel.z - origin.z);
        matrices.multiply(this.dispatcher.getRotation());
        matrices.scale(-0.022f, -0.022f, 0.022f);
        Matrix4f m = matrices.peek().getPositionMatrix();
        TextRenderer tr = getTextRenderer();
        int light = LightmapTextureManager.MAX_LIGHT_COORDINATE;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            boolean action = line.startsWith("[");
            int color = action ? (inReach ? 0xFFFFC040 : 0xFF9A7A40) : 0xFFE0F4FF;
            float x = -tr.getWidth(line) / 2f;
            tr.draw(line, x, i * 11, color, false, m, vcp, TextRenderer.TextLayerType.NORMAL, 0x90081018, light);
        }
        matrices.pop();
    }

    private static List<String> panelLines(RailgunEntity e) {
        RailgunState s = e.getState();
        int t = e.getStateTicks();
        return switch (s) {
            case POD -> List.of("ARES-9 RAILGUN POD", "[ USE PANEL ]");
            case ARMING -> List.of("ORBITAL STORAGE", "PARTS DOCKING");
            case SCANNING -> List.of("SCAN ARRAY", "PULSE ACTIVE");
            case RIFT -> List.of("RIFT EMITTER", "SKY OPEN"); 
            case PANEL_OPEN -> List.of("PANEL RETRACTING");
            case TRANSFORMING -> List.of(String.format(Locale.ROOT, "DEPLOYING  %d%%",
                    Math.min(100, t * 100 / RailgunConfig.TRANSFORM_TICKS)));
            case IDLE, COOLDOWN -> List.of(s == RailgunState.IDLE ? "ONLINE" : "COOLING", "[ USE PANEL ]");
            case CHARGING, FIRING -> List.of("WEAPON HOT");
            case PACKING -> List.of(String.format(Locale.ROOT, "RETRACTING  %d%%",
                    Math.min(100, t * 100 / RailgunConfig.PACK_TICKS)));
            case POD_READY -> List.of("PACKED FOR ORBIT", "[ USE PANEL ]");
            default -> List.of();
        };
    }

    /** While the beam is active, always render: it extends far beyond the model's cull box. */
    @Override
    public boolean shouldRender(RailgunEntity entity, Frustum frustum, double x, double y, double z) {
        return entity.isBeamActive() || super.shouldRender(entity, frustum, x, y, z);
    }
}

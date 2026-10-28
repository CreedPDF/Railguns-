package com.creedpdf.orbitalrailgun.client.legion.render;

import com.creedpdf.orbitalrailgun.client.krl.KrlCompat;
import com.creedpdf.orbitalrailgun.client.krl.KrlMesh;
import com.creedpdf.orbitalrailgun.client.krl.KrlModels;
import com.creedpdf.orbitalrailgun.legion.SwarmPaths;
import com.creedpdf.orbitalrailgun.legion.capability.SwarmCapability;
import com.creedpdf.orbitalrailgun.legion.entity.AutonomousWeaponEntity;
import com.creedpdf.orbitalrailgun.legion.entity.SwarmState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.joml.Quaternionf;

/**
 * Draws a flying weapon: the weapon itself (its own item model) held in the Blender-built telekinetic harness
 * ({@code arsenal_legion:models/entity/floating_blade}: three gyroscope rings, a halo of rune plates, clamp jaws and a
 * focus crystal at the pommel). It is drawn at {@link LegionVisual#pose} - the exact per-frame point on its formation
 * or storm path - not at the tick position, and the blade turns smoothly toward where that pose aims (formation
 * direction, strike line, storm tangent). The rings and runes spin faster and burn brighter in attack and in a storm.
 */
public class AutonomousWeaponRenderer extends EntityRenderer<AutonomousWeaponEntity> {
    public AutonomousWeaponRenderer(EntityRendererFactory.Context ctx) {
        super(ctx);
        this.shadowRadius = 0f;
    }

    @Override
    public Identifier getTexture(AutonomousWeaponEntity e) {
        return PlayerScreenHandler.BLOCK_ATLAS_TEXTURE;
    }

    @Override
    public boolean shouldRender(AutonomousWeaponEntity e, Frustum frustum, double x, double y, double z) {
        return true;   // drawn off its tick position (see pose), so the frustum test on that would pop it
    }

    @Override
    public void render(AutonomousWeaponEntity e, float yaw, float pt, MatrixStack ms, VertexConsumerProvider vcp, int light) {
        ItemStack stack = e.getStack();
        if (stack.isEmpty()) return;
        SwarmState st = e.state();
        float time = e.age + pt;
        boolean fierce = st != SwarmState.ORBIT;
        SwarmPaths.Pose pose = LegionVisual.pose(e, pt);
        Vec3d at = e.getLerpedPos(pt);
        // ease the blade toward its aim (crisp in a strike, gentle in formation)
        Vec3d aim = pose.aim();
        double k = st == SwarmState.STRIKE || st == SwarmState.DEFEND ? 0.6 : 0.25;
        Vec3d d = e.renderDir.lerp(aim, k);
        e.renderDir = d.lengthSquared() > 1e-6 ? d.normalize() : aim;
        Vec3d dir = e.renderDir;
        boolean shield = SwarmCapability.isShield(stack);

        ms.push();
        ms.translate(pose.pos().x - at.x, pose.pos().y - at.y + 0.3, pose.pos().z - at.z);
        ms.multiply(new Quaternionf().rotationTo(0f, 1f, 0f, (float) dir.x, (float) dir.y, (float) dir.z));
        float roll = st == SwarmState.STORM ? time * 0.9f : st == SwarmState.ORBIT ? MathHelper.sin(time * 0.05f + e.getId()) * 0.6f : 0f;
        ms.multiply(RotationAxis.POSITIVE_Y.rotation(roll + e.getId()));

        KrlMesh mesh = KrlModels.BLADE;
        if (mesh != null) {
            float spin = time * (fierce ? 0.55f : 0.12f);
            KrlMesh.Pose p = (part, s) -> {
                switch (part) {
                    case "ring_outer" -> s.multiply(RotationAxis.POSITIVE_Y.rotation(spin));
                    case "ring_inner" -> s.multiply(RotationAxis.POSITIVE_X.rotation(-spin * 1.3f));
                    case "ring_third" -> s.multiply(RotationAxis.POSITIVE_Z.rotation(spin * 0.8f));
                    case "runes" -> {
                        s.multiply(RotationAxis.POSITIVE_Y.rotation(-spin * 0.6f));
                        float b = 1f + (fierce ? 0.25f : 0.05f) * MathHelper.sin(time * 0.4f);
                        s.scale(b, 1f, b);
                    }
                    default -> {
                    }
                }
                return true;
            };
            float glow = fierce ? 1f : 0.55f + 0.15f * MathHelper.sin(time * 0.2f + e.getId());
            int lod = KrlCompat.shadowPass() ? 2 : 0;
            mesh.lod(lod).render(ms, vcp.getBuffer(RenderLayer.getEntityCutoutNoCull(KrlModels.BLADE_TEX)), light, p, null);
            if (!KrlCompat.shadowPass()) mesh.glow(lod).render(ms, vcp.getBuffer(RenderLayer.getEyes(KrlModels.BLADE_GLOW)), light, p,
                    (nx, ny, nz, o) -> {
                        o[0] = glow;
                        o[1] = glow;
                        o[2] = glow;
                        o[3] = 1f;
                    });
        }

        // the weapon: blade along +Y, guard at the harness clamps
        ms.translate(0, 0.42, 0);
        if (shield) {
            ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees(90));
            ms.scale(1.1f, 1.1f, 1.1f);
        } else {
            ms.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(45));   // item sprites hold blades on the diagonal
            ms.scale(1.3f, 1.3f, 1.3f);
        }
        MinecraftClient.getInstance().getItemRenderer().renderItem(stack, ModelTransformationMode.FIXED, light, OverlayTexture.DEFAULT_UV,
                ms, vcp, e.getWorld(), e.getId());
        ms.pop();
    }
}

package com.creedpdf.orbitalrailgun.client.legion.render;

import com.creedpdf.orbitalrailgun.client.krl.KrlCompat;
import com.creedpdf.orbitalrailgun.client.krl.KrlMesh;
import com.creedpdf.orbitalrailgun.client.krl.KrlModels;
import com.creedpdf.orbitalrailgun.legion.capability.SwarmCapability;
import com.creedpdf.orbitalrailgun.legion.entity.AutonomousWeaponEntity;
import com.creedpdf.orbitalrailgun.legion.entity.SwarmState;
import net.minecraft.client.MinecraftClient;
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
 * Draws a flying weapon: the weapon itself (its own item model, so a netherite sword looks like one) held in the
 * Blender-built telekinetic harness ({@code arsenal_legion:models/entity/floating_blade}: two gyroscope rings, clamp
 * jaws, a focus crystal at the pommel). Weapon and harness are turned so the blade points along the flight path -
 * the orbit's tangent while circling, straight at the target during a strike - and the rings spin, faster and brighter
 * when it attacks.
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
    public void render(AutonomousWeaponEntity e, float yaw, float pt, MatrixStack ms, VertexConsumerProvider vcp, int light) {
        ItemStack stack = e.getStack();
        if (stack.isEmpty()) return;
        SwarmState st = e.state();
        float time = e.age + pt;
        boolean attacking = st != SwarmState.ORBIT;

        // flight direction from this tick's motion (or the last one seen while nearly still)
        Vec3d m = new Vec3d(e.getX() - e.prevX, e.getY() - e.prevY, e.getZ() - e.prevZ);
        Vec3d dir = m.lengthSquared() > 1e-4 ? m.normalize() : e.lastDir;
        boolean shield = SwarmCapability.isShield(stack);

        ms.push();
        ms.translate(0, 0.3, 0);
        ms.multiply(new Quaternionf().rotationTo(0f, 1f, 0f, (float) dir.x, (float) dir.y, (float) dir.z));
        // a slow roll about the blade while orbiting, locked straight while striking
        if (!attacking) ms.multiply(RotationAxis.POSITIVE_Y.rotation(time * 0.045f + e.getId()));

        // the harness
        KrlMesh mesh = KrlModels.BLADE;
        if (mesh != null) {
            float spin = time * (attacking ? 0.5f : 0.12f);
            KrlMesh.Pose pose = (part, s) -> {
                if (part.equals("ring_outer")) s.multiply(RotationAxis.POSITIVE_Y.rotation(spin));
                else if (part.equals("ring_inner")) s.multiply(RotationAxis.POSITIVE_X.rotation(-spin * 1.3f));
                return true;
            };
            float k = attacking ? 1f : 0.55f + 0.15f * MathHelper.sin(time * 0.2f + e.getId());
            int lod = KrlCompat.shadowPass() ? 2 : 0;
            mesh.lod(lod).render(ms, vcp.getBuffer(RenderLayer.getEntityCutoutNoCull(KrlModels.BLADE_TEX)), light, pose, null);
            if (!KrlCompat.shadowPass()) mesh.glow(lod).render(ms, vcp.getBuffer(RenderLayer.getEyes(KrlModels.BLADE_GLOW)), light, pose,
                    (nx, ny, nz, o) -> {
                        o[0] = k;
                        o[1] = k;
                        o[2] = k;
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
            ms.scale(1.25f, 1.25f, 1.25f);
        }
        MinecraftClient.getInstance().getItemRenderer().renderItem(stack, ModelTransformationMode.FIXED, light, OverlayTexture.DEFAULT_UV,
                ms, vcp, e.getWorld(), e.getId());
        ms.pop();
        super.render(e, yaw, pt, ms, vcp, light);
    }
}

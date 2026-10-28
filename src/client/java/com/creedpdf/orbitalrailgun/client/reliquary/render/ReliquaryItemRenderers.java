package com.creedpdf.orbitalrailgun.client.reliquary.render;

import com.creedpdf.orbitalrailgun.client.krl.KrlCompat;
import com.creedpdf.orbitalrailgun.client.krl.KrlMesh;
import com.creedpdf.orbitalrailgun.client.krl.KrlModels;
import com.creedpdf.orbitalrailgun.client.reliquary.input.ReliquaryInput;
import com.creedpdf.orbitalrailgun.reliquary.ModItems;
import com.creedpdf.orbitalrailgun.reliquary.item.StarEaterGazeItem;
import net.fabricmc.fabric.api.client.rendering.v1.BuiltinItemRendererRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;

/**
 * The three weapons are drawn from their Blender meshes (carried inside their item model files), posed live:
 * <ul>
 *   <li>Star-Eater's Gaze: the three hoops spin on independent axes (outer about the barrel, middle about the side axis,
 *   inner about the vertical), faster while its ray is held; the core brightens with the charge.</li>
 *   <li>Void-Carver's Edge: its fractures breathe.</li>
 *   <li>Core-Infused Revolver: the cylinder turns one chamber after each shot; the engine core flares, then cools.</li>
 * </ul>
 * The item model's display transforms put them in the hand; here the mesh is drawn round the grip at the cube centre.
 */
public final class ReliquaryItemRenderers {
    private ReliquaryItemRenderers() {}

    public static void register() {
        BuiltinItemRendererRegistry.INSTANCE.register(ModItems.STAR_EATER_GAZE, (stack, mode, ms, vcp, light, overlay) -> starEater(mode, ms, vcp, light));
        BuiltinItemRendererRegistry.INSTANCE.register(ModItems.VOID_CARVER_BLADE, (stack, mode, ms, vcp, light, overlay) -> voidCarver(mode, ms, vcp, light));
        BuiltinItemRendererRegistry.INSTANCE.register(ModItems.CORE_REVOLVER, (stack, mode, ms, vcp, light, overlay) -> revolver(mode, ms, vcp, light));
    }

    private static float time() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.world == null ? 0 : mc.world.getTime() + mc.getTickDelta();
    }

    private static boolean inHand(ModelTransformationMode m) {
        return m.isFirstPerson() || m == ModelTransformationMode.THIRD_PERSON_LEFT_HAND || m == ModelTransformationMode.THIRD_PERSON_RIGHT_HAND;
    }

    private static int lod(ModelTransformationMode m) {
        if (KrlCompat.shadowPass()) return 2;
        return m.isFirstPerson() ? 0 : m == ModelTransformationMode.GUI ? 1 : 1;
    }

    private static void draw(KrlMesh mesh, Identifier tex, Identifier glow, ModelTransformationMode mode, MatrixStack ms, VertexConsumerProvider vcp,
                             int light, KrlMesh.Pose pose, KrlMesh.Shade glowShade) {
        if (mesh == null) return;
        ms.push();
        ms.translate(0.5, 0.5, 0.5);
        int lod = lod(mode);
        mesh.lod(lod).render(ms, vcp.getBuffer(RenderLayer.getEntityCutoutNoCull(tex)), light, pose, null);
        if (!KrlCompat.shadowPass()) mesh.glow(lod).render(ms, vcp.getBuffer(RenderLayer.getEyes(glow)), light, pose, glowShade);
        ms.pop();
    }

    private static void starEater(ModelTransformationMode mode, MatrixStack ms, VertexConsumerProvider vcp, int light) {
        float t = time();
        float charge = inHand(mode) ? ReliquaryInput.localCharge() : 0f;
        float speed = 1f + 5f * charge;
        float a = t * 0.05f * speed;
        String[] cur = {""};
        KrlMesh.Pose pose = (part, s) -> {
            cur[0] = part;
            switch (part) {
                case "hoop_outer" -> s.multiply(RotationAxis.POSITIVE_Z.rotation(a));
                case "hoop_mid" -> s.multiply(RotationAxis.POSITIVE_X.rotation(a * 1.37f));
                case "hoop_inner" -> s.multiply(RotationAxis.POSITIVE_Y.rotation(-a * 1.9f));
                case "core" -> {
                    float k = 1f + 0.08f * MathHelper.sin(t * 0.3f) + 0.35f * charge;
                    s.scale(k, k, k);
                }
                default -> {
                }
            }
            return true;
        };
        float pulse = 0.75f + 0.15f * MathHelper.sin(t * 0.2f);
        draw(KrlModels.STAR_EATER, KrlModels.STAR_EATER_TEX, KrlModels.STAR_EATER_GLOW, mode, ms, vcp, light, pose, (nx, ny, nz, o) -> {
            float k = cur[0].equals("core") ? Math.min(1f, pulse + charge) : pulse;
            o[0] = k;
            o[1] = k;
            o[2] = k;
            o[3] = 1f;
        });
    }

    private static void voidCarver(ModelTransformationMode mode, MatrixStack ms, VertexConsumerProvider vcp, int light) {
        float t = time();
        float k = 0.7f + 0.3f * MathHelper.sin(t * 0.11f) * MathHelper.sin(t * 0.037f + 1f);
        draw(KrlModels.VOID_CARVER, KrlModels.VOID_CARVER_TEX, KrlModels.VOID_CARVER_GLOW, mode, ms, vcp, light, null, (nx, ny, nz, o) -> {
            o[0] = k;
            o[1] = k;
            o[2] = k;
            o[3] = 1f;
        });
    }

    private static void revolver(ModelTransformationMode mode, MatrixStack ms, VertexConsumerProvider vcp, int light) {
        float since = inHand(mode) ? ReliquaryInput.sinceFire() : 1000f;
        // the cylinder turns one chamber (60 degrees) over the 4 ticks after a shot
        float turn = MathHelper.clamp(since / 4f, 0f, 1f);
        int shots = ReliquaryInput.shots();
        float cyl = (shots - 1 + turn) * MathHelper.PI / 3f;
        float heat = Math.max(0.55f, 1f - since / 30f);
        String[] cur = {""};
        KrlMesh.Pose pose = (part, s) -> {
            cur[0] = part;
            if (part.equals("cylinder")) s.multiply(RotationAxis.POSITIVE_Z.rotation(cyl));
            return true;
        };
        draw(KrlModels.REVOLVER, KrlModels.REVOLVER_TEX, KrlModels.REVOLVER_GLOW, mode, ms, vcp, light, pose, (nx, ny, nz, o) -> {
            float k = cur[0].equals("core") ? heat : 0.8f;
            o[0] = k;
            o[1] = k;
            o[2] = k;
            o[3] = 1f;
        });
    }

    /** Star-Eater ray charge 0..1 for a given number of ticks held. */
    public static float charge(float held) {
        return MathHelper.clamp(held / StarEaterGazeItem.COLLAPSE_TICKS, 0f, 1f);
    }
}

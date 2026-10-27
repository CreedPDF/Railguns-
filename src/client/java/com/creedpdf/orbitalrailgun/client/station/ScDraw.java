package com.creedpdf.orbitalrailgun.client.station;

import com.creedpdf.orbitalrailgun.client.krl.KrlCompat;
import com.creedpdf.orbitalrailgun.client.krl.KrlMesh;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;

/** Draws a station mesh: the textured body, then (outside the shadow pass) its glow at a brightness. */
final class ScDraw {
    private ScDraw() {}

    static void draw(KrlMesh mesh, Identifier tex, Identifier glow, MatrixStack ms, VertexConsumerProvider vcp, int light, int lod,
                     KrlMesh.Pose pose, KrlMesh.Shade glowShade) {
        mesh.lod(lod).render(ms, vcp.getBuffer(RenderLayer.getEntityCutoutNoCull(tex)), light, pose, null);
        if (!KrlCompat.shadowPass()) mesh.glow(lod).render(ms, vcp.getBuffer(RenderLayer.getEyes(glow)), light, pose, glowShade);
    }

    static KrlMesh.Shade grey(float k) {
        return (nx, ny, nz, o) -> {
            o[0] = k;
            o[1] = k;
            o[2] = k;
            o[3] = 1f;
        };
    }

    /** Only these parts, unposed. */
    static KrlMesh.Pose only(String... parts) {
        return (part, s) -> {
            for (String p : parts) if (p.equals(part)) return true;
            return false;
        };
    }
}

package com.creedpdf.orbitalrailgun.client.reliquary.render;

import com.creedpdf.orbitalrailgun.client.krl.KrlCompat;
import com.creedpdf.orbitalrailgun.client.render.BeamRenderLayers;
import com.creedpdf.orbitalrailgun.reliquary.block.MoltenScarBlock;
import com.creedpdf.orbitalrailgun.reliquary.block.MoltenScarBlockEntity;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.random.Random;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * A molten scar on its surface: an irregular splash of fused rock drawn as a glowing fan - white-hot at the centre,
 * orange at the edges, with dark cracks - breathing with heat and cooling over its life.
 */
public class MoltenScarRenderer implements BlockEntityRenderer<MoltenScarBlockEntity> {
    public MoltenScarRenderer(BlockEntityRendererFactory.Context ctx) {}

    @Override
    public void render(MoltenScarBlockEntity be, float pt, MatrixStack ms, VertexConsumerProvider vcp, int light, int overlay) {
        if (KrlCompat.shadowPass() || be.getWorld() == null) return;
        Direction d = be.getCachedState().contains(MoltenScarBlock.FACING) ? be.getCachedState().get(MoltenScarBlock.FACING) : Direction.UP;
        float cool = 1f - Math.min(1f, (be.age() + ClientAge.of(be)) / (float) MoltenScarBlockEntity.LIFE);
        float time = be.getWorld().getTime() + pt;
        float breathe = 0.85f + 0.15f * MathHelper.sin(time * 0.2f + be.getPos().hashCode());
        ms.push();
        ms.translate(0.5, 0.5, 0.5);
        ms.multiply(new Quaternionf().rotationTo(0, 1, 0, d.getOffsetX(), d.getOffsetY(), d.getOffsetZ()));
        ms.translate(0, -0.48, 0);
        Matrix4f m = ms.peek().getPositionMatrix();
        VertexConsumer g = vcp.getBuffer(BeamRenderLayers.GLOW);
        Random r = Random.create(be.getPos().asLong());
        int n = 14;
        float[] rad = new float[n];
        for (int i = 0; i < n; i++) rad[i] = 0.22f + r.nextFloat() * 0.26f;
        float a = cool * breathe;
        for (int i = 0; i < n; i++) {
            float t0 = MathHelper.TAU * i / n, t1 = MathHelper.TAU * (i + 1) / n;
            float r0 = rad[i], r1 = rad[(i + 1) % n];
            g.vertex(m, 0, 0, 0).color(1f, 0.85f, 0.55f, a).next();
            g.vertex(m, MathHelper.cos(t0) * r0, 0, MathHelper.sin(t0) * r0).color(1f, 0.35f, 0.05f, a * 0.8f).next();
            g.vertex(m, MathHelper.cos(t1) * r1, 0, MathHelper.sin(t1) * r1).color(1f, 0.35f, 0.05f, a * 0.8f).next();
            g.vertex(m, 0, 0, 0).color(1f, 0.85f, 0.55f, a).next();
        }
        ms.pop();
    }

    /** Ticks a scar has been visible on this client since its last sync (its age is synced only when it changes). */
    static final class ClientAge {
        private static final java.util.Map<Long, Long> SEEN = new java.util.HashMap<>();

        static float of(MoltenScarBlockEntity be) {
            long now = be.getWorld().getTime();
            long key = be.getPos().asLong() ^ ((long) be.age() << 40);
            Long first = SEEN.computeIfAbsent(key, k -> now);
            if (SEEN.size() > 4096) SEEN.clear();
            return now - first;
        }
    }
}

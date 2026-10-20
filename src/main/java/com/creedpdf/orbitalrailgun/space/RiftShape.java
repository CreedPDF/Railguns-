package com.creedpdf.orbitalrailgun.space;

import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.random.Random;

/**
 * The outline of a rift: a long, slightly bent tear with pointed ends and a shredded, jagged
 * edge - not a circle. Deterministic from a seed so the portal (server) and the glowing rim
 * (client) always match. Points are (x, z) in blocks around the rift centre.
 */
public final class RiftShape {
    private RiftShape() {}

    public static final int POINTS = 160;
    /** Half-length of the tear relative to its half-width. */
    public static final float ASPECT = 2.7f;

    public static float[][] outline(long seed, float halfWidth) {
        Random r = Random.create(seed);
        float rot = r.nextFloat() * MathHelper.TAU;
        float bend = (r.nextFloat() - 0.5f) * 0.9f;
        float[] ph = new float[6];
        for (int i = 0; i < ph.length; i++) ph[i] = r.nextFloat() * MathHelper.TAU;
        // a handful of deep notches and long torn spikes
        int spikes = 9;
        float[] sAt = new float[spikes], sAmp = new float[spikes], sW = new float[spikes];
        for (int k = 0; k < spikes; k++) {
            sAt[k] = r.nextFloat() * MathHelper.TAU;
            sAmp[k] = (r.nextFloat() < 0.6f ? 1f : -0.6f) * (0.12f + r.nextFloat() * 0.22f);
            sW[k] = 0.04f + r.nextFloat() * 0.07f;
        }
        float W = halfWidth, L = halfWidth * ASPECT;
        float cr = MathHelper.cos(rot), sr = MathHelper.sin(rot);
        float[][] out = new float[POINTS][2];
        for (int i = 0; i < POINTS; i++) {
            float th = i / (float) POINTS * MathHelper.TAU;
            float s = MathHelper.sin(th), c = MathHelper.cos(th);
            float x = L * c;
            float z = W * Math.signum(s) * (float) Math.pow(Math.abs(s), 1.35);
            float jag = 1f + 0.07f * MathHelper.sin(7 * th + ph[0]) + 0.05f * MathHelper.sin(13 * th + ph[1])
                    + 0.035f * MathHelper.sin(23 * th + ph[2]) + 0.025f * MathHelper.sin(41 * th + ph[3]);
            for (int k = 0; k < spikes; k++) {
                float d = MathHelper.wrapDegrees((th - sAt[k]) * MathHelper.DEGREES_PER_RADIAN) * MathHelper.RADIANS_PER_DEGREE;
                float tri = Math.max(0f, 1f - Math.abs(d) / sW[k]);
                jag += sAmp[k] * tri;
            }
            // jag is applied mostly across the tear (its edges), less at the pointed ends
            float across = 0.35f + 0.65f * Math.abs(s);
            x *= 1f + (jag - 1f) * (1f - across) * 0.6f;
            z *= 1f + (jag - 1f) * across * 1.4f;
            z += bend * W * (c * c);
            out[i][0] = x * cr - z * sr;
            out[i][1] = x * sr + z * cr;
        }
        return out;
    }

    /** Bounding half-extents (x, z) of an outline. */
    public static float[] extent(float[][] pts) {
        float mx = 0, mz = 0;
        for (float[] p : pts) {
            mx = Math.max(mx, Math.abs(p[0]));
            mz = Math.max(mz, Math.abs(p[1]));
        }
        return new float[]{mx, mz};
    }
}

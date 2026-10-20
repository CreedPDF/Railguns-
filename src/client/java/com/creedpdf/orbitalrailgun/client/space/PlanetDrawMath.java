package com.creedpdf.orbitalrailgun.client.space;

import net.minecraft.util.math.MathHelper;
import org.joml.Vector3f;

final class PlanetDrawMath {
    private PlanetDrawMath() {}

    /** Unit vector for equirectangular (u, v): u = longitude 0..1, v = 0 at the north pole. */
    static Vector3f unit(float u, float v) {
        float lat = MathHelper.PI * (0.5f - v), lon = MathHelper.TAU * u;
        return new Vector3f(MathHelper.cos(lat) * MathHelper.cos(lon), MathHelper.sin(lat), MathHelper.cos(lat) * MathHelper.sin(lon));
    }

    static float smooth(float e0, float e1, float x) {
        float t = MathHelper.clamp((x - e0) / (e1 - e0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }
}

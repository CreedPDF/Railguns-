package com.creedpdf.orbitalrailgun.client.ui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import org.joml.Matrix4f;

/** Screen pixels per GUI unit at the current transform: how big text drawn now will really be. */
public final class Px {
    private Px() {}

    public static float of(DrawContext dc) {
        Matrix4f m = dc.getMatrices().peek().getPositionMatrix();
        float sx = (float) Math.sqrt(m.m00() * m.m00() + m.m01() * m.m01());
        return sx * (float) MinecraftClient.getInstance().getWindow().getScaleFactor();
    }

    /** Moves the current origin onto a whole screen pixel, so text starts on the pixel grid. */
    public static void snap(DrawContext dc) {
        Matrix4f m = dc.getMatrices().peek().getPositionMatrix();
        float g = (float) MinecraftClient.getInstance().getWindow().getScaleFactor();
        m.m30(Math.round(m.m30() * g) / g);
        m.m31(Math.round(m.m31() * g) / g);
    }
}

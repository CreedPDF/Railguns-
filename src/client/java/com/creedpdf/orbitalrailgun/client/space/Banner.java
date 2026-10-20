package com.creedpdf.orbitalrailgun.client.space;

import com.creedpdf.orbitalrailgun.client.ui.UiKit;
import com.creedpdf.orbitalrailgun.space.Body;
import com.creedpdf.orbitalrailgun.space.SpaceDims;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

/**
 * Cinematic title at the top of the screen: "DEEP SPACE" when you cross into space, the
 * planet's name when you land, and "JUPITER HAS BEEN DECIMATED" when ARES-0 destroys one.
 * Big spaced letters with a thin rule drawn out underneath.
 */
public final class Banner {
    private Banner() {}

    private static String title, sub;
    private static int color = 0xFFFFFFFF;
    private static long start = -1000;
    private static float titleScale = 2.6f, subScale = 1f;
    private static boolean glitch;
    private static final int LENGTH = 110;
    private static RegistryKey<World> lastWorld;

    public static void show(String t, String s, int argb) {
        show(t, s, argb, 2.6f, 1f, false);
    }

    /** A planet just died: a smaller, flickering two-line alert. */
    public static void planetDestroyed(Body b) {
        show("GRAVITATIONAL BINDING FAILED", "CELLULAR DISRUPTION IMMINENT", 0xFFFF5A3A, 1.7f, 0.85f, true);
    }

    public static void show(String t, String s, int argb, float tScale, float sScale, boolean flicker) {
        titleScale = tScale;
        subScale = sScale;
        glitch = flicker;
        MinecraftClient mc = MinecraftClient.getInstance();
        title = t;
        sub = s;
        color = argb;
        start = mc.world == null ? 0 : mc.world.getTime();
    }

    /** Called every client tick: announces where you just arrived. */
    static void tick(ClientWorld w) {
        RegistryKey<World> key = w.getRegistryKey();
        if (key == lastWorld) return;
        RegistryKey<World> prev = lastWorld;
        lastWorld = key;
        if (prev == null) return;
        if (key == SpaceDims.SPACE) show("DEEP SPACE", "ZERO GRAVITY  //  NO ATMOSPHERE", 0xFFE6F4FF);
        else {
            Body b = SpaceDims.planetOf(key);
            if (b != null) show(b.displayName(), String.format(java.util.Locale.ROOT, "SURFACE GRAVITY %.2f g", b.gravity), 0xFFFFE2C0);
            else if (key == World.OVERWORLD && prev == SpaceDims.SPACE) show("EARTH", "WELCOME HOME", 0xFFC8E6FF);
        }
    }

    public static String verb(Body b) {
        return switch (b) {
            case MOON -> "ERADICATED";
            case MARS -> "OBLITERATED";
            case VENUS -> "ANNIHILATED";
            case EUROPA -> "SHATTERED";
            case JUPITER -> "DECIMATED";
            case SATURN -> "VAPORIZED";
            default -> "DESTROYED";
        };
    }

    public static void render(DrawContext dc, float pt) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (title == null || mc.world == null) return;
        float t = mc.world.getTime() - start + pt;
        if (t < 0 || t > LENGTH) return;
        float in = MathHelper.clamp(t / 14f, 0f, 1f), out = MathHelper.clamp((LENGTH - t) / 24f, 0f, 1f);
        float a = Math.min(in, out);
        if (a <= 0.02f) return;
        TextRenderer tr = mc.textRenderer;
        int w = dc.getScaledWindowWidth();
        float scale = titleScale, spacing = 3.2f * titleScale / 2.6f;
        if (glitch) {
            // an unstable signal: brief dropouts and a jitter for the first second
            int k = (int) t;
            if (t < 26 && (k * 7919 % 5 == 0)) a *= 0.25f;
        }
        float tw = UiKit.spacedWidth(tr, title, spacing, scale);
        float y = 22;
        float jx = glitch && t < 20 && ((int) t) % 3 == 0 ? (((int) t * 31) % 5 - 2) : 0;
        UiKit.spaced(dc, tr, title, (w - tw) / 2f + jx, y, UiKit.alpha(color, a), spacing, scale, true);
        // the rule draws itself out from the centre
        float lineW = (tw + 40) * MathHelper.clamp((t - 6) / 22f, 0f, 1f);
        int ly = (int) (y + 9 * scale + 6);
        int x0 = (int) ((w - lineW) / 2f), x1 = (int) ((w + lineW) / 2f);
        dc.fill(x0, ly, x1, ly + 1, UiKit.alpha(color, a * 0.9f));
        dc.fill(x0 + (int) (lineW * 0.3f), ly + 2, x1 - (int) (lineW * 0.3f), ly + 3, UiKit.alpha(color, a * 0.35f));
        if (sub != null) {
            float ss = subScale, sp = 1.6f * ss;
            float sw = UiKit.spacedWidth(tr, sub, sp, ss);
            UiKit.spaced(dc, tr, sub, (w - sw) / 2f, ly + 7, UiKit.alpha(color, a * 0.8f * MathHelper.clamp((t - 16) / 14f, 0f, 1f)), sp, ss, true);
        }
    }
}

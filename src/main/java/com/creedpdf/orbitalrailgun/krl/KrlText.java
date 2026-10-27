package com.creedpdf.orbitalrailgun.krl;

import com.creedpdf.orbitalrailgun.RailgunMod;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.Identifier;

/** The KRL's own typefaces: Share Tech Mono for read-outs and Rajdhani Bold for headings (both SIL OFL, see font/). */
public final class KrlText {
    private KrlText() {}

    public static final Identifier FONT = RailgunMod.id("krl"), HEAD = RailgunMod.id("krl_head");
    public static final Style MONO = Style.EMPTY.withFont(FONT), TITLE = Style.EMPTY.withFont(HEAD);

    /** Body / read-out text. */
    public static MutableText t(String s) {
        return Text.literal(s).setStyle(MONO);
    }

    public static MutableText t(String s, int rgb) {
        return Text.literal(s).setStyle(MONO.withColor(TextColor.fromRgb(rgb & 0xFFFFFF)));
    }

    /** Heading text. */
    public static MutableText h(String s) {
        return Text.literal(s).setStyle(TITLE);
    }

    /** A translated line in the read-out font. */
    public static MutableText tr(String key, Object... args) {
        return Text.translatable(key, args).setStyle(MONO);
    }

    // ---- sharp text at any size: each face is loaded at several raster resolutions (font/krl_<n>.json, krl_head_<n>.json);
    //      a screen picks the one whose pixels match the text's real size on screen, so glyphs are never stretched.
    private static final int[] OS = {1, 2, 3, 4, 5, 6, 7, 8};
    private static final Style[] MONO_AT = new Style[OS.length], HEAD_AT = new Style[OS.length];

    static {
        for (int i = 0; i < OS.length; i++) {
            MONO_AT[i] = Style.EMPTY.withFont(RailgunMod.id("krl_" + OS[i]));
            HEAD_AT[i] = Style.EMPTY.withFont(RailgunMod.id("krl_head_" + OS[i]));
        }
    }

    private static int pick(float px) {
        int n = Math.round(px);
        return Math.max(0, Math.min(OS.length - 1, n - 1));
    }

    /** Read-out text for `px` screen pixels per text unit. */
    public static MutableText t(String s, float px) {
        return Text.literal(s).setStyle(MONO_AT[pick(px)]);
    }

    /** Heading text for `px` screen pixels per text unit. */
    public static MutableText h(String s, float px) {
        return Text.literal(s).setStyle(HEAD_AT[pick(px)]);
    }

    /** Action-bar / chat colours of the KRL. */
    public static final int AMBER = 0xFFB02E, GREY = 0xC8CCC4, GREEN = 0x7FD67A, RED = 0xFF5040;
}

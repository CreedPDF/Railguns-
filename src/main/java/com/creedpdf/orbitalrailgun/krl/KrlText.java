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

    /** Action-bar / chat colours of the KRL. */
    public static final int AMBER = 0xFFB02E, GREY = 0xC8CCC4, GREEN = 0x7FD67A, RED = 0xFF5040;
}

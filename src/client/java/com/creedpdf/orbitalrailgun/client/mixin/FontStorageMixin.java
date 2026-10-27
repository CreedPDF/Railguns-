package com.creedpdf.orbitalrailgun.client.mixin;

import com.creedpdf.orbitalrailgun.RailgunMod;
import net.minecraft.client.font.FontStorage;
import net.minecraft.client.font.GlyphAtlasTexture;
import net.minecraft.client.font.GlyphRenderer;
import net.minecraft.client.font.RenderableGlyph;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * The mod's own fonts (Share Tech Mono, Rajdhani) are TrueType glyphs drawn at many sizes on the KRL and ARES
 * screens. Vanilla samples font atlases with nearest filtering, which makes scaled TrueType text blocky; this mod's
 * atlases are switched to bilinear filtering (vanilla fonts are left alone).
 */
@Mixin(FontStorage.class)
public abstract class FontStorageMixin {
    @Shadow @Final private Identifier id;
    @Shadow @Final private List<GlyphAtlasTexture> glyphAtlases;

    @Inject(method = "getGlyphRenderer(Lnet/minecraft/client/font/RenderableGlyph;)Lnet/minecraft/client/font/GlyphRenderer;", at = @At("RETURN"))
    private void orbital_railgun$smoothAtlas(RenderableGlyph glyph, CallbackInfoReturnable<GlyphRenderer> cir) {
        if (!RailgunMod.MOD_ID.equals(id.getNamespace()) || glyphAtlases.isEmpty()) return;
        glyphAtlases.get(glyphAtlases.size() - 1).setFilter(true, false);
    }
}

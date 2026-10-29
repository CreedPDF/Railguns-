package com.creedpdf.orbitalrailgun.client.mixin;

import net.minecraft.client.gl.PostEffectPass;
import net.minecraft.client.gl.PostEffectProcessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/** HANGEKI's impact frames set their shader's uniforms per frame. */
@Mixin(PostEffectProcessor.class)
public interface PostEffectProcessorAccessor {
    @Accessor("passes")
    List<PostEffectPass> orbitalRailgun$passes();
}

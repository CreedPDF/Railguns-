package com.creedpdf.orbitalrailgun.client.render;

import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;

/**
 * Custom {@link RenderLayer} for the railgun beam.
 *
 * <h2>How the glow pipeline works</h2>
 * A RenderLayer is a bundle of GL state ("render phases") that every vertex written into its
 * buffer is drawn with. The beam layer combines:
 * <ul>
 *   <li><b>Format POSITION_COLOR</b> – no texture and no lightmap. The beam is pure vertex
 *       colour, so it is always full-bright regardless of day/night or block light.</li>
 *   <li><b>LIGHTNING_PROGRAM</b> – vanilla's {@code rendertype_lightning} core shader. It simply
 *       outputs {@code vertexColor * ColorModulator}: no fog, no lighting. Perfect for emissive
 *       energy. Using a vanilla program keeps us compatible with Sodium (which leaves entity
 *       rendering alone) and degrades gracefully under Iris shader packs.</li>
 *   <li><b>LIGHTNING_TRANSPARENCY</b> – additive blending ({@code SRC_ALPHA, ONE}). Each layer of
 *       geometry <i>adds</i> light on top of what's behind it instead of covering it. Overlapping
 *       shells therefore get brighter toward the centre, which is what makes a stack of
 *       concentric tubes read as one volumetric, glowing column instead of flat polygons.</li>
 *   <li><b>COLOR_MASK</b> (no depth write) – the beam is still depth-<i>tested</i> (terrain
 *       occludes it) but never writes depth, so inner shells don't block outer shells and the
 *       draw order of the shells doesn't matter. Additive blending is order-independent.</li>
 *   <li><b>DISABLE_CULLING</b> – both the front and back faces of every tube are drawn. Looking
 *       through a tube you see two walls, so the edges (where you look through more "material")
 *       end up dimmer than the centre: a cheap fake of volumetric density.</li>
 * </ul>
 *
 * <p>{@code RenderLayer.of} is not public in vanilla; it is opened by
 * {@code orbital_railgun.accesswidener}. Extending RenderLayer gives us access to the protected
 * render-phase constants. This class is never instantiated.</p>
 */
public final class BeamRenderLayers extends RenderLayer {

    public static final RenderLayer BEAM = RenderLayer.of(
            "orbital_railgun_beam",
            VertexFormats.POSITION_COLOR,
            VertexFormat.DrawMode.QUADS,
            1 << 16,
            false,
            true,
            RenderLayer.MultiPhaseParameters.builder()
                    .program(LIGHTNING_PROGRAM)
                    .transparency(LIGHTNING_TRANSPARENCY)
                    .writeMaskState(COLOR_MASK)
                    .cull(DISABLE_CULLING)
                    .build(false));

    private BeamRenderLayers(String name, VertexFormat format, VertexFormat.DrawMode mode, int bufferSize,
                             boolean crumbling, boolean translucent, Runnable setup, Runnable clear) {
        super(name, format, mode, bufferSize, crumbling, translucent, setup, clear);
        throw new UnsupportedOperationException("constants holder");
    }
}

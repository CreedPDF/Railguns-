package com.creedpdf.orbitalrailgun.client.render;

import com.creedpdf.orbitalrailgun.entity.OrbitalRailgunEntity;
import net.minecraft.client.render.entity.EntityRendererFactory;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.renderer.layer.AutoGlowingGeoLayer;

/** Draws the orbital platform (only ever inside the orbital uplink's space scene). */
public class OrbitalRailgunRenderer extends GeoEntityRenderer<OrbitalRailgunEntity> {
    public OrbitalRailgunRenderer(EntityRendererFactory.Context ctx) {
        super(ctx, new OrbitalRailgunModel());
        addRenderLayer(new AutoGlowingGeoLayer<>(this));
        this.shadowRadius = 0f;
    }
}

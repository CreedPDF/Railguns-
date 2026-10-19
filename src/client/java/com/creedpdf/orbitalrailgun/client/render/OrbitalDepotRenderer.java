package com.creedpdf.orbitalrailgun.client.render;

import com.creedpdf.orbitalrailgun.entity.OrbitalDepotEntity;
import net.minecraft.client.render.entity.EntityRendererFactory;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.renderer.layer.AutoGlowingGeoLayer;

public class OrbitalDepotRenderer extends GeoEntityRenderer<OrbitalDepotEntity> {
    public OrbitalDepotRenderer(EntityRendererFactory.Context ctx) {
        super(ctx, new OrbitalDepotModel());
        addRenderLayer(new AutoGlowingGeoLayer<>(this));
        this.shadowRadius = 1.6f;
    }
}

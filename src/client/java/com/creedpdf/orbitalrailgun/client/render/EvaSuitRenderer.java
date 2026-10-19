package com.creedpdf.orbitalrailgun.client.render;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.item.EvaSuitItem;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import software.bernie.geckolib.animatable.client.RenderProvider;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoArmorRenderer;
import software.bernie.geckolib.renderer.layer.AutoGlowingGeoLayer;

/** The ARES EVA suit: a Blender-built 3D GeckoLib armour model with glowing visor, lights and jetpack. */
public class EvaSuitRenderer extends GeoArmorRenderer<EvaSuitItem> {
    public EvaSuitRenderer() {
        super(new Model());
        addRenderLayer(new AutoGlowingGeoLayer<>(this));
    }

    private static final class Model extends GeoModel<EvaSuitItem> {
        private static final Identifier GEO = RailgunMod.id("geo/eva_suit.geo.json");
        private static final Identifier TEX = RailgunMod.id("textures/armor/eva_suit.png");
        private static final Identifier ANIM = RailgunMod.id("animations/eva_suit.animation.json");

        @Override
        public Identifier getModelResource(EvaSuitItem animatable) {
            return GEO;
        }

        @Override
        public Identifier getTextureResource(EvaSuitItem animatable) {
            return TEX;
        }

        @Override
        public Identifier getAnimationResource(EvaSuitItem animatable) {
            return ANIM;
        }
    }

    public static Object provider() {
        return new RenderProvider() {
            private GeoArmorRenderer<?> renderer;

            @Override
            public BipedEntityModel<LivingEntity> getHumanoidArmorModel(LivingEntity entity, ItemStack stack, EquipmentSlot slot,
                                                                       BipedEntityModel<LivingEntity> original) {
                if (renderer == null) renderer = new EvaSuitRenderer();
                renderer.prepForRender(entity, stack, slot, original);
                return renderer;
            }
        };
    }
}

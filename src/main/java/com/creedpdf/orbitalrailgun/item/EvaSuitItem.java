package com.creedpdf.orbitalrailgun.item;

import com.creedpdf.orbitalrailgun.RailgunMod;
import net.minecraft.item.ArmorItem;
import software.bernie.geckolib.animatable.GeoItem;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * One piece of the ARES EVA suit, a 3D GeckoLib armour model built in Blender. The full set is
 * the only thing that keeps you alive in space and on other planets; the chest piece carries
 * the jetpack.
 */
public class EvaSuitItem extends ArmorItem implements GeoItem {
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private final Supplier<Object> renderProvider = GeoItem.makeRenderer(this);

    public EvaSuitItem(ArmorItem.Type type, Settings settings) {
        super(EvaArmorMaterial.INSTANCE, type, settings);
    }

    @Override
    public void createRenderer(Consumer<Object> consumer) {
        consumer.accept(RailgunMod.client().evaRenderProvider());
    }

    @Override
    public Supplier<Object> getRenderProvider() {
        return renderProvider;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {}

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }
}

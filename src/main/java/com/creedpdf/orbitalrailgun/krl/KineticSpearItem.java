package com.creedpdf.orbitalrailgun.krl;

import net.minecraft.client.item.TooltipContext;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** A kinetic spear: a tungsten penetrator on a solid-rocket motor. Load it into a KRL-1 launch rail. */
public class KineticSpearItem extends Item {
    public KineticSpearItem(Settings settings) {
        super(settings);
    }

    @Override
    public void appendTooltip(ItemStack stack, @Nullable World world, List<Text> tooltip, TooltipContext context) {
        tooltip.add(Text.translatable("item.orbital_railgun.kinetic_spear.tooltip.1").formatted(Formatting.GRAY));
        tooltip.add(Text.translatable("item.orbital_railgun.kinetic_spear.tooltip.2").formatted(Formatting.DARK_RED));
    }
}

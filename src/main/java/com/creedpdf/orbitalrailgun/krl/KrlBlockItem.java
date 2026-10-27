package com.creedpdf.orbitalrailgun.krl;

import net.minecraft.block.Block;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** A KRL block's item: its name and tooltip in the KRL typeface. */
public class KrlBlockItem extends BlockItem {
    private final String info;

    public KrlBlockItem(Block block, Settings settings, String info) {
        super(block, settings);
        this.info = info;
    }

    @Override
    public Text getName(ItemStack stack) {
        return KrlText.tr(getTranslationKey(stack));
    }

    @Override
    public Text getName() {
        return KrlText.tr(getTranslationKey());
    }

    @Override
    public void appendTooltip(ItemStack stack, @Nullable World world, List<Text> tooltip, TooltipContext context) {
        tooltip.add(KrlText.t(info, 0x9AA29A));
    }
}

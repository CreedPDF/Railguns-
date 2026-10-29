package com.creedpdf.orbitalrailgun.hangeki;

import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SwordItem;
import net.minecraft.item.ToolMaterial;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** The sword. Right-click is the parry (the same as the parry key). */
public class HangekiItem extends SwordItem {
    public HangekiItem(ToolMaterial material, int attackDamage, float attackSpeed, Settings settings) {
        super(material, attackDamage, attackSpeed, settings);
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        if (hand != Hand.MAIN_HAND) return TypedActionResult.pass(stack);
        if (user instanceof ServerPlayerEntity p) ParryServer.press(p);
        return TypedActionResult.consume(stack);
    }

    @Override
    public void appendTooltip(ItemStack stack, @Nullable World world, List<Text> tooltip, TooltipContext context) {
        tooltip.add(Text.translatable("item.orbital_railgun.hangeki.desc1").formatted(Formatting.GOLD));
        tooltip.add(Text.translatable("item.orbital_railgun.hangeki.desc2").formatted(Formatting.GRAY));
        tooltip.add(Text.translatable("item.orbital_railgun.hangeki.desc3").formatted(Formatting.GRAY));
        tooltip.add(Text.translatable("item.orbital_railgun.hangeki.desc4").formatted(Formatting.DARK_RED));
    }
}

package com.creedpdf.orbitalrailgun.krl;

import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A kinetic missile: a tungsten penetrator on a solid-rocket motor. Load it into a KRL-1 launch rail. It carries a
 * paint scheme (livery); sneak-use it in the air to cycle through them.
 */
public class KineticSpearItem extends Item {
    public KineticSpearItem(Settings settings) {
        super(settings);
    }

    public static int livery(ItemStack stack) {
        return stack.hasNbt() ? Krl.livery(stack.getNbt().getInt("livery")) : 0;
    }

    public static ItemStack withLivery(ItemStack stack, int livery) {
        stack.getOrCreateNbt().putInt("livery", Krl.livery(livery));
        return stack;
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        if (!user.isSneaking()) return TypedActionResult.pass(stack);
        if (!world.isClient) {
            int next = Krl.livery(livery(stack) + 1);
            withLivery(stack, next);
            world.playSound(null, user.getBlockPos(), Krl.S_CLICK, SoundCategory.PLAYERS, 0.8f, 1.2f);
            user.sendMessage(Text.literal("Livery: " + Krl.LIVERY_NAMES[next]).formatted(Formatting.GRAY), true);
        }
        return TypedActionResult.success(stack, world.isClient);
    }

    @Override
    public void appendTooltip(ItemStack stack, @Nullable World world, List<Text> tooltip, TooltipContext context) {
        tooltip.add(Text.literal("Livery: " + Krl.LIVERY_NAMES[livery(stack)]).formatted(Formatting.WHITE));
        tooltip.add(Text.translatable("item.orbital_railgun.kinetic_spear.tooltip.1").formatted(Formatting.GRAY));
        tooltip.add(Text.translatable("item.orbital_railgun.kinetic_spear.tooltip.2").formatted(Formatting.DARK_GRAY));
        tooltip.add(Text.literal("Sneak-use to change the livery.").formatted(Formatting.DARK_GRAY));
    }
}

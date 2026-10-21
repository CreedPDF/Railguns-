package com.creedpdf.orbitalrailgun.item;

import com.creedpdf.orbitalrailgun.space.ShipRegistry;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The ARES starship's command key. Using it commissions the ship: it is parked in orbit and linked
 * to your uplink tablet, from which you call it down ({@code ~/hangar/starship}) and send it back.
 */
public class StarshipItem extends Item {
    public StarshipItem(Settings settings) {
        super(settings);
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        if (world.isClient || !(user instanceof ServerPlayerEntity sp)) return TypedActionResult.success(stack);
        return link(sp, stack) ? TypedActionResult.consume(stack) : TypedActionResult.fail(stack);
    }

    @Override
    public ActionResult useOnBlock(ItemUsageContext ctx) {
        if (ctx.getPlayer() == null) return ActionResult.PASS;
        if (ctx.getWorld().isClient) return ActionResult.SUCCESS;
        return link((ServerPlayerEntity) ctx.getPlayer(), ctx.getStack()) ? ActionResult.CONSUME : ActionResult.FAIL;
    }

    private static boolean link(ServerPlayerEntity p, ItemStack stack) {
        ShipRegistry reg = ShipRegistry.get(p.getServer());
        if (reg.entry(p.getUuid()).state != ShipRegistry.NONE) {
            p.sendMessage(Text.translatable("message.orbital_railgun.ship.already").formatted(Formatting.RED), true);
            return false;
        }
        reg.setOrbit(p.getUuid());
        if (!p.isCreative()) stack.decrement(1);
        p.sendMessage(Text.translatable("message.orbital_railgun.ship.linked").formatted(Formatting.RED), false);
        p.playSound(com.creedpdf.orbitalrailgun.registry.ModSounds.UI_TARGET_LOCK, net.minecraft.sound.SoundCategory.PLAYERS, 1f, 0.6f);
        return true;
    }

    @Override
    public void appendTooltip(ItemStack stack, @Nullable World world, List<Text> tooltip, TooltipContext context) {
        tooltip.add(Text.translatable("item.orbital_railgun.starship.tooltip").formatted(Formatting.GRAY));
    }
}

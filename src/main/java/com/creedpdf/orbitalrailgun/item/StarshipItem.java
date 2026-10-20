package com.creedpdf.orbitalrailgun.item;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.entity.ShipEntity;
import com.creedpdf.orbitalrailgun.space.SpaceDims;
import net.minecraft.item.Item;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/** Calls the ARES starship down from orbit onto the block you use it on; it lands facing away from you. */
public class StarshipItem extends Item {
    public StarshipItem(Settings settings) {
        super(settings);
    }

    @Override
    public ActionResult useOnBlock(ItemUsageContext ctx) {
        if (!(ctx.getWorld() instanceof ServerWorld world)) return ActionResult.SUCCESS;
        if (SpaceDims.isSpace(world)) {
            deny(ctx, "space");
            return ActionResult.FAIL;
        }
        BlockPos ground = ctx.getBlockPos().up();
        Direction face = ctx.getHorizontalPlayerFacing();
        int f = ShipEntity.facingOf(face);
        // needs room: count solid blocks in the hull volume
        int solid = 0;
        for (int lx = -4; lx <= 4; lx++) {
            for (int ly = 1; ly <= 6; ly++) {
                for (int lz = -9; lz <= 9; lz++) {
                    BlockPos p = ShipEntity.cellPos(ground, f, lx, ly, lz);
                    if (!world.getBlockState(p).getCollisionShape(world, p).isEmpty()) solid++;
                }
            }
        }
        if (solid > 14) {
            deny(ctx, "room");
            return ActionResult.FAIL;
        }
        ShipEntity ship = new ShipEntity(RailgunMod.SHIP, world);
        ship.dropIn(world, ground, f);
        world.spawnEntity(ship);
        if (ctx.getPlayer() != null && !ctx.getPlayer().isCreative()) ctx.getStack().decrement(1);
        if (ctx.getPlayer() != null) {
            ctx.getPlayer().sendMessage(Text.translatable("message.orbital_railgun.ship.inbound").formatted(Formatting.AQUA), true);
        }
        return ActionResult.CONSUME;
    }

    @Override
    public void appendTooltip(net.minecraft.item.ItemStack stack, @org.jetbrains.annotations.Nullable net.minecraft.world.World world,
                              java.util.List<Text> tooltip, net.minecraft.client.item.TooltipContext context) {
        tooltip.add(Text.translatable("item.orbital_railgun.starship.tooltip").formatted(Formatting.GRAY));
    }

    private static void deny(ItemUsageContext ctx, String why) {
        if (ctx.getPlayer() != null) {
            ctx.getPlayer().sendMessage(Text.translatable("message.orbital_railgun.ship.deny." + why).formatted(Formatting.RED), true);
        }
    }
}

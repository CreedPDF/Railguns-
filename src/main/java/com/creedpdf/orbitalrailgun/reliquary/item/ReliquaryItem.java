package com.creedpdf.orbitalrailgun.reliquary.item;

import com.creedpdf.orbitalrailgun.RailgunMod;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * A Cosmic Reliquary weapon. Its attack is not a vanilla attack: the client stops the attack key from hitting or
 * breaking anything while one is held and sends the weapon's own action instead (see client/reliquary/ReliquaryInput).
 * Using it (right-click) while looking straight up with a full Aetheric Charge fires the Orbital Link.
 */
public abstract class ReliquaryItem extends Item {
    /** Look at least this far up (pitch is negative upward) to fire the Orbital Link. */
    public static final float LINK_PITCH = -75f;

    protected ReliquaryItem(Settings settings) {
        super(settings);
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        if (user.getPitch() > LINK_PITCH) return TypedActionResult.pass(stack);
        if (world.isClient) RailgunMod.client().reliquaryLink();
        return TypedActionResult.success(stack, world.isClient);
    }

    @Override
    public boolean canMine(BlockState state, World world, BlockPos pos, PlayerEntity miner) {
        return false;
    }

    @Override
    public boolean isEnchantable(ItemStack stack) {
        return false;
    }
}

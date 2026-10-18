package com.creedpdf.orbitalrailgun.item;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.util.RailgunConfig;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.UseAction;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.world.World;

/**
 * The ARES-9 uplink tablet (item id kept as {@code railgun_remote} so existing items survive).
 *
 * <p>Held in both hands (see the client mixins). Using it zooms the view into the tablet's
 * display ({@code TabletScreen}), where the player can call the railgun down onto the block they
 * were looking at, or, once it is deployed, set targets and fire it remotely.</p>
 *
 * <p>The raycast happens on the client at the moment of use, so "the block you were aiming at"
 * is exactly what you saw. The server re-validates every command the tablet sends.</p>
 */
public class RailgunRemoteItem extends Item {

    public RailgunRemoteItem(Settings settings) {
        super(settings);
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        if (hand != Hand.MAIN_HAND) return TypedActionResult.pass(stack);
        if (world.isClient) {
            HitResult hit = user.raycast(RailgunConfig.MAX_FIRE_RANGE, 1.0f, false);
            RailgunMod.client().openTablet(hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult bh
                    ? bh.getBlockPos() : null);
        }
        return TypedActionResult.success(stack, false); // no arm swing: both hands hold the tablet
    }

    @Override
    public UseAction getUseAction(ItemStack stack) {
        return UseAction.NONE;
    }

    @Override
    public boolean hasGlint(ItemStack stack) {
        return false;
    }
}

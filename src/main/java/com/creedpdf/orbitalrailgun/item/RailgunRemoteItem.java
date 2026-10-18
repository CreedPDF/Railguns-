package com.creedpdf.orbitalrailgun.item;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.registry.ModSounds;
import com.creedpdf.orbitalrailgun.util.RailgunConfig;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.world.World;

/**
 * Orbital uplink remote.
 *
 * <p>Flow: the <b>client</b> raycasts from the player's eyes (long range, using the client's
 * interpolated view so what you see is what you target) and sends
 * {@code ModPackets.REQUEST_DEPLOY} with the hit block. The <b>server</b> re-validates
 * everything (holding the remote, cooldown, range, sky access) before scheduling the drop and
 * answering with a countdown packet that drives the HUD and the local UI sound.</p>
 *
 * <p>The server never trusts the client's block choice blindly; it only trusts that the player
 * <i>wants</i> that block, then checks it is reachable and legal.</p>
 */
public class RailgunRemoteItem extends Item {

    public RailgunRemoteItem(Settings settings) {
        super(settings);
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);

        if (user.getItemCooldownManager().isCoolingDown(this)) {
            return TypedActionResult.fail(stack);
        }

        if (world.isClient) {
            HitResult hit = user.raycast(RailgunConfig.REMOTE_RANGE, 1.0f, false);
            if (hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult blockHit) {
                RailgunMod.client().requestDeploy(blockHit.getBlockPos());
            } else {
                user.sendMessage(Text.translatable("message.orbital_railgun.no_lock").formatted(Formatting.RED), true);
                user.playSound(ModSounds.UI_DENIED, 1.0f, 1.0f);
                return TypedActionResult.fail(stack);
            }
        }
        return TypedActionResult.success(stack, world.isClient);
    }

    @Override
    public boolean hasGlint(ItemStack stack) {
        return true;
    }
}

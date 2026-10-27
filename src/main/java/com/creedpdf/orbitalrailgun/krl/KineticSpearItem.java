package com.creedpdf.orbitalrailgun.krl;

import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A kinetic missile: a tungsten penetrator on a solid-rocket motor. Load it into a KRL-1 launch rail. Its warhead,
 * charge, motor and paint are fitted at a Missile Integration Station ({@link KrlLoadout}); sneak-use it in the air to
 * cycle the paint scheme.
 */
public class KineticSpearItem extends Item {
    public KineticSpearItem(Settings settings) {
        super(settings);
    }

    public static int livery(ItemStack stack) {
        return KrlLoadout.of(stack).livery();
    }

    public static ItemStack withLivery(ItemStack stack, int livery) {
        KrlLoadout l = KrlLoadout.of(stack);
        return new KrlLoadout(l.warhead(), l.charge(), l.motor(), livery).applyTo(stack);
    }

    @Override
    public Text getName(ItemStack stack) {
        return KrlText.tr(getTranslationKey(stack));
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        if (!user.isSneaking()) return TypedActionResult.pass(stack);
        if (!world.isClient) {
            int next = Krl.livery(livery(stack) + 1);
            withLivery(stack, next);
            world.playSound(null, user.getBlockPos(), Krl.S_CLICK, SoundCategory.PLAYERS, 0.8f, 1.2f);
            user.sendMessage(KrlText.t("LIVERY  " + Krl.LIVERY_NAMES[next].toUpperCase(java.util.Locale.ROOT), KrlText.GREY), true);
        }
        return TypedActionResult.success(stack, world.isClient);
    }

    @Override
    public void appendTooltip(ItemStack stack, @Nullable World world, List<Text> tooltip, TooltipContext context) {
        KrlLoadout l = KrlLoadout.of(stack);
        tooltip.add(KrlText.t("WARHEAD  " + KrlLoadout.WARHEADS[l.warhead()] + (l.warhead() == KrlLoadout.KINETIC ? "" : "  CHARGE " + l.charge() + "/5"), KrlText.AMBER));
        tooltip.add(KrlText.t("MOTOR    " + KrlLoadout.MOTORS[l.motor()] + String.format(java.util.Locale.ROOT, "  %.0f m  MACH %.0f", l.range(), l.mach()), KrlText.GREY));
        tooltip.add(KrlText.t("LIVERY   " + Krl.LIVERY_NAMES[l.livery()].toUpperCase(java.util.Locale.ROOT), KrlText.GREY));
        tooltip.add(KrlText.t("Load into a KRL-1 rail. Refit at a Missile Station.", 0x7E867E));
        tooltip.add(KrlText.t("Sneak-use to change the livery.", 0x5E665F));
    }
}

package com.creedpdf.orbitalrailgun.legion.capability;

import com.creedpdf.orbitalrailgun.legion.ArsenalLegion;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.AxeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ShieldItem;
import net.minecraft.item.SwordItem;
import net.minecraft.item.TridentItem;
import net.minecraft.util.math.MathHelper;

/**
 * Access to the swarm "capability" and the rules about what may join it.
 * <ul>
 *   <li>Eligible: swords, axes, tridents, shields, and anything in the item tag {@code arsenal_legion:swarm_weapons}.</li>
 *   <li>Defenders (may intercept projectiles): shields, axes, tridents, and any weapon hitting for 7 or more.</li>
 *   <li>Strikers: every eligible weapon except shields.</li>
 * </ul>
 */
public final class SwarmCapability {
    private SwarmCapability() {}

    public static IWeaponSwarm of(PlayerEntity player) {
        return ((SwarmHolder) player).arsenalLegion$swarm();
    }

    /** How many weapons may be bound and fly at once (attribute arsenal_legion:swarm_capacity). */
    public static int capacity(PlayerEntity player) {
        double v = player.getAttributes().hasAttribute(ArsenalLegion.SWARM_CAPACITY) ? player.getAttributeValue(ArsenalLegion.SWARM_CAPACITY) : 6;
        return MathHelper.clamp((int) Math.floor(v), 1, IWeaponSwarm.MAX_SLOTS);
    }

    public static boolean eligible(ItemStack s) {
        if (s.isEmpty()) return false;
        return s.getItem() instanceof SwordItem || s.getItem() instanceof AxeItem || s.getItem() instanceof TridentItem
                || s.getItem() instanceof ShieldItem || s.isIn(ArsenalLegion.SWARM_WEAPONS);
    }

    public static boolean isShield(ItemStack s) {
        return s.getItem() instanceof ShieldItem;
    }

    public static boolean canStrike(ItemStack s) {
        return eligible(s) && !isShield(s);
    }

    public static boolean canDefend(ItemStack s) {
        return isShield(s) || s.getItem() instanceof AxeItem || s.getItem() instanceof TridentItem || baseDamage(s) >= 7;
    }

    /** Attack damage the stack gives in the main hand (1 + its additive modifiers, like a player's attack). */
    public static float baseDamage(ItemStack s) {
        double d = 1;
        for (EntityAttributeModifier m : s.getAttributeModifiers(EquipmentSlot.MAINHAND).get(EntityAttributes.GENERIC_ATTACK_DAMAGE))
            if (m.getOperation() == EntityAttributeModifier.Operation.ADDITION) d += m.getValue();
        return (float) Math.max(1, d);
    }
}

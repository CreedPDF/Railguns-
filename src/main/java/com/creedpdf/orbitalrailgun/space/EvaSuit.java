package com.creedpdf.orbitalrailgun.space;

import com.creedpdf.orbitalrailgun.item.EvaSuitItem;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.ItemStack;

/** Queries about what an entity is wearing. */
public final class EvaSuit {
    private EvaSuit() {}

    public static boolean wearing(LivingEntity e, EquipmentSlot slot) {
        ItemStack s = e.getEquippedStack(slot);
        return s.getItem() instanceof EvaSuitItem;
    }

    /** All four pieces: sealed and pressurised. */
    public static boolean full(LivingEntity e) {
        return wearing(e, EquipmentSlot.HEAD) && wearing(e, EquipmentSlot.CHEST)
                && wearing(e, EquipmentSlot.LEGS) && wearing(e, EquipmentSlot.FEET);
    }

    public static boolean jetpack(LivingEntity e) {
        ItemStack s = e.getEquippedStack(EquipmentSlot.CHEST);
        return s.getItem() instanceof EvaSuitItem a && a.getType() == ArmorItem.Type.CHESTPLATE;
    }

    public static boolean boots(LivingEntity e) {
        return wearing(e, EquipmentSlot.FEET);
    }
}

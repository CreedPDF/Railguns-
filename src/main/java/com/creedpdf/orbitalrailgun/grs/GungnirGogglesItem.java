package com.creedpdf.orbitalrailgun.grs;

import net.minecraft.item.ArmorItem;
import net.minecraft.item.ArmorMaterial;
import net.minecraft.item.Items;
import net.minecraft.recipe.Ingredient;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;

/**
 * The Gungnir goggles: worn on the head, they show the GRS fire-control display over your view. The spear is commanded
 * from them (fire / yield keys), wherever you are on Earth.
 */
public class GungnirGogglesItem extends ArmorItem {
    public GungnirGogglesItem(Settings settings) {
        super(Material.INSTANCE, Type.HELMET, settings);
    }

    public static final class Material implements ArmorMaterial {
        public static final Material INSTANCE = new Material();

        @Override
        public int getDurability(Type type) {
            return 480;
        }

        @Override
        public int getProtection(Type type) {
            return 2;
        }

        @Override
        public int getEnchantability() {
            return 10;
        }

        @Override
        public SoundEvent getEquipSound() {
            return SoundEvents.ITEM_ARMOR_EQUIP_IRON;
        }

        @Override
        public Ingredient getRepairIngredient() {
            return Ingredient.ofItems(Items.NETHERITE_SCRAP);
        }

        @Override
        public String getName() {
            return "orbital_railgun_gungnir";
        }

        @Override
        public float getToughness() {
            return 1f;
        }

        @Override
        public float getKnockbackResistance() {
            return 0f;
        }
    }
}

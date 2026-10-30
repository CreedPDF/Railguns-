package com.creedpdf.orbitalrailgun.astraea;

import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import net.minecraft.block.BlockState;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * ASTRAEA // The Worldsplitter. A spear of heaven: a fine weapon in the hand (and it will not break), but its purpose
 * is the ability key - Ascension charges it with one throw, and the throw splits the Earth.
 */
public class AstraeaItem extends Item {
    private static final String CHARGE = "AstraeaCharge";
    private final Multimap<EntityAttribute, EntityAttributeModifier> attributes;

    public AstraeaItem(Settings settings) {
        super(settings);
        this.attributes = ImmutableMultimap.<EntityAttribute, EntityAttributeModifier>builder()
                .put(EntityAttributes.GENERIC_ATTACK_DAMAGE, new EntityAttributeModifier(ATTACK_DAMAGE_MODIFIER_ID, "Lance", 13.0, EntityAttributeModifier.Operation.ADDITION))
                .put(EntityAttributes.GENERIC_ATTACK_SPEED, new EntityAttributeModifier(ATTACK_SPEED_MODIFIER_ID, "Lance", -2.7, EntityAttributeModifier.Operation.ADDITION))
                .build();
    }

    public static boolean charged(ItemStack s) {
        return s.hasNbt() && s.getNbt().getBoolean(CHARGE);
    }

    public static void setCharged(ItemStack s, boolean c) {
        if (c) s.getOrCreateNbt().putBoolean(CHARGE, true);
        else if (s.hasNbt()) s.getNbt().remove(CHARGE);
    }

    @Override
    public Multimap<EntityAttribute, EntityAttributeModifier> getAttributeModifiers(EquipmentSlot slot) {
        return slot == EquipmentSlot.MAINHAND ? attributes : super.getAttributeModifiers(slot);
    }

    @Override
    public boolean canMine(BlockState state, World world, BlockPos pos, PlayerEntity miner) {
        return !miner.isCreative();
    }

    @Override
    public boolean postHit(ItemStack stack, LivingEntity target, LivingEntity attacker) {
        return true;
    }

    @Override
    public boolean hasGlint(ItemStack stack) {
        return charged(stack) || super.hasGlint(stack);
    }

    @Override
    public void appendTooltip(ItemStack stack, @Nullable World world, List<Text> tooltip, TooltipContext context) {
        tooltip.add(Text.translatable("item.orbital_railgun.astraea.desc1").formatted(Formatting.WHITE));
        tooltip.add(Text.translatable(charged(stack) ? "item.orbital_railgun.astraea.charged" : "item.orbital_railgun.astraea.uncharged")
                .formatted(charged(stack) ? Formatting.GOLD : Formatting.GRAY));
        tooltip.add(Text.translatable("item.orbital_railgun.astraea.desc2").formatted(Formatting.DARK_GRAY));
    }
}

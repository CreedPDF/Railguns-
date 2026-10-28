package com.creedpdf.orbitalrailgun.reliquary;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageType;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.world.ServerWorld;
import org.jetbrains.annotations.Nullable;

/**
 * The Reliquary's damage types (data/cosmic_reliquary/damage_type). Their tags (data/minecraft/tags/damage_types) make
 * them skip what the spec asks them to skip, so a hit is a plain subtraction from the health float that still goes
 * through death, drops and kill credit:
 * <ul>
 *   <li>{@code disintegrate} (singularity): bypasses armour, enchantments, effects, resistance, shields and the
 *   invulnerability frames - nothing survives reaching the centre;</li>
 *   <li>{@code void_arc} (rift arcs): bypasses invulnerability frames and armour, so overlapping arcs all land;</li>
 *   <li>{@code core_round} (revolver): bypasses invulnerability frames, so one round hurts every mob it pierces;</li>
 *   <li>{@code molten_scar} (burning ground): bypasses invulnerability frames and armour;</li>
 *   <li>{@code void_cut} (the swing itself): an ordinary hit.</li>
 * </ul>
 */
public final class ModDamageTypes {
    private ModDamageTypes() {}

    public static final RegistryKey<DamageType> DISINTEGRATE = key("disintegrate"), VOID_ARC = key("void_arc"), VOID_CUT = key("void_cut"),
            CORE_ROUND = key("core_round"), MOLTEN_SCAR = key("molten_scar");

    private static RegistryKey<DamageType> key(String n) {
        return RegistryKey.of(RegistryKeys.DAMAGE_TYPE, CosmicReliquary.id(n));
    }

    public static boolean ours(DamageSource s) {
        return s.isOf(DISINTEGRATE) || s.isOf(VOID_ARC) || s.isOf(VOID_CUT) || s.isOf(CORE_ROUND) || s.isOf(MOLTEN_SCAR);
    }

    /** Deal {@code amount} of {@code type}, credited to {@code attacker} (may be null), from {@code source}. */
    public static boolean hurt(ServerWorld w, LivingEntity target, RegistryKey<DamageType> type, @Nullable Entity source, @Nullable Entity attacker,
                               float amount) {
        DamageSource src = new DamageSource(w.getRegistryManager().get(RegistryKeys.DAMAGE_TYPE).entryOf(type), source, attacker);
        return target.damage(src, amount);
    }
}

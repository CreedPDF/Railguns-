package com.creedpdf.orbitalrailgun.reliquary.logic;

import com.creedpdf.orbitalrailgun.reliquary.ModDamageTypes;
import com.creedpdf.orbitalrailgun.reliquary.ModSounds;
import com.creedpdf.orbitalrailgun.reliquary.block.MoltenScarBlock;
import com.creedpdf.orbitalrailgun.reliquary.block.MoltenScarBlockEntity;
import com.creedpdf.orbitalrailgun.reliquary.network.ReliquaryNet;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The world tick handler for molten scars. Once a second, for every floor scar in every world, it finds the living
 * things standing in it and burns them: {@link #BURN} health off (a {@code molten_scar} hit, credited to whoever fired
 * the round), one point of durability off each armour piece they wear, and the clients are told to trail heat voxels
 * behind them for the next second. Only scars that exist are scanned - never the whole world.
 */
public final class MoltenScars {
    private MoltenScars() {}

    public static final float BURN = 2f;
    private static final Map<RegistryKey<World>, Set<BlockPos>> SCARS = new HashMap<>();

    public static void register() {
        ServerTickEvents.END_WORLD_TICK.register(MoltenScars::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(s -> SCARS.clear());
    }

    public static void add(World w, BlockPos p) {
        SCARS.computeIfAbsent(w.getRegistryKey(), k -> new HashSet<>()).add(p.toImmutable());
    }

    public static void remove(World w, BlockPos p) {
        Set<BlockPos> s = SCARS.get(w.getRegistryKey());
        if (s != null) s.remove(p);
    }

    private static void tick(ServerWorld w) {
        if (w.getTime() % 20 != 0) return;
        Set<BlockPos> set = SCARS.get(w.getRegistryKey());
        if (set == null || set.isEmpty()) return;
        List<LivingEntity> burned = new ArrayList<>();
        for (BlockPos p : List.copyOf(set))
            if (w.isChunkLoaded(p) && w.getBlockState(p).getBlock() instanceof MoltenScarBlock
                    && w.getBlockState(p).get(MoltenScarBlock.FACING) == Direction.UP
                    && w.getBlockEntity(p) instanceof MoltenScarBlockEntity be) {
                PlayerEntity owner = be.owner() == null ? null : w.getPlayerByUuid(be.owner());
                Box on = new Box(p.getX(), p.getY(), p.getZ(), p.getX() + 1, p.getY() + 0.35, p.getZ() + 1);
                for (LivingEntity e : w.getEntitiesByClass(LivingEntity.class, on, LivingEntity::isAlive)) {
                    if (e.isSpectator() || (e instanceof PlayerEntity pl && pl.getAbilities().creativeMode) || burned.contains(e)) continue;
                    ModDamageTypes.hurt(w, e, ModDamageTypes.MOLTEN_SCAR, null, owner, BURN);
                    for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
                        ItemStack a = e.getEquippedStack(slot);
                        if (!a.isEmpty() && a.isDamageable()) a.damage(1, e, x -> x.sendEquipmentBreakStatus(slot));
                    }
                    burned.add(e);
                }
            }
        if (burned.isEmpty()) return;
        for (LivingEntity e : burned) w.playSound(null, e.getX(), e.getY(), e.getZ(), ModSounds.MOLTEN_SIZZLE, SoundCategory.BLOCKS, 0.6f, 1.2f);
        ReliquaryNet.broadcastBurn(w, burned);
    }
}

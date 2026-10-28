package com.creedpdf.orbitalrailgun.reliquary.logic;

import com.creedpdf.orbitalrailgun.reliquary.ModDamageTypes;
import com.creedpdf.orbitalrailgun.reliquary.ModSounds;
import com.creedpdf.orbitalrailgun.reliquary.entity.CustomSingularityEntity;
import com.creedpdf.orbitalrailgun.reliquary.network.ReliquaryNet;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import org.jetbrains.annotations.Nullable;

/**
 * Fired when something reaches the absolute centre of a singularity. Listeners may veto it (return false); otherwise
 * the victim's health is driven to zero by a {@code disintegrate} hit (which skips armour, enchantments, effects,
 * resistance, shields and invulnerability frames), the core implodes with a crushing vacuum fracture, and every client
 * nearby draws the victim vaporising into expanding neon-blue rings ({@code cosmic_shatter}).
 */
public final class CustomDisintegrationEvent {
    private CustomDisintegrationEvent() {}

    @FunctionalInterface
    public interface Listener {
        /** Return false to spare the victim. */
        boolean allow(CustomSingularityEntity singularity, LivingEntity victim);
    }

    public static final Event<Listener> EVENT = EventFactory.createArrayBacked(Listener.class, ls -> (s, v) -> {
        for (Listener l : ls) if (!l.allow(s, v)) return false;
        return true;
    });

    /** Returns true if the victim was erased. */
    public static boolean fire(ServerWorld w, CustomSingularityEntity s, LivingEntity victim, @Nullable PlayerEntity owner) {
        if (!EVENT.invoker().allow(s, victim)) return false;
        float size = Math.max(victim.getWidth(), victim.getHeight());
        double x = victim.getX(), y = victim.getBodyY(0.5), z = victim.getZ();
        ModDamageTypes.hurt(w, victim, ModDamageTypes.DISINTEGRATE, s, owner, victim.getHealth() + victim.getAbsorptionAmount() + 1000f);
        if (victim.isAlive()) return false;   // a totem, or something stronger than physics
        w.playSound(null, x, y, z, ModSounds.CORE_IMPLODE, SoundCategory.HOSTILE, 3f, 0.9f + w.random.nextFloat() * 0.2f);
        ReliquaryNet.broadcastShatter(w, x, y, z, size);
        victim.discard();                      // vaporised: no corpse lingers
        return true;
    }
}

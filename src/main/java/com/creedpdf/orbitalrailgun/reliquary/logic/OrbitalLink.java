package com.creedpdf.orbitalrailgun.reliquary.logic;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.entity.Ares0Entity;
import com.creedpdf.orbitalrailgun.entity.RailgunEntity;
import com.creedpdf.orbitalrailgun.reliquary.ModDamageTypes;
import com.creedpdf.orbitalrailgun.reliquary.ModDataComponentTypes;
import com.creedpdf.orbitalrailgun.reliquary.ModItems;
import com.creedpdf.orbitalrailgun.reliquary.ModSounds;
import com.creedpdf.orbitalrailgun.reliquary.item.ReliquaryItem;
import com.creedpdf.orbitalrailgun.reliquary.network.ReliquaryNet;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;

/**
 * Aetheric Charge and the Orbital Link.
 * <ul>
 *   <li>Every kill made with a Reliquary weapon (or by its singularity, rift arcs or molten scars) adds 1 to the killer's
 *   Aetheric Charge, up to {@link ModDataComponentTypes#MAX_CHARGE}; the new value is sent to their client only (it is
 *   drawn as the ring round the crosshair, nowhere else).</li>
 *   <li>The Orbital Link: with a full charge, using a Reliquary weapon while looking straight up sends the link packet.
 *   The server re-checks the charge and the look, then resets everything the player's space weapons were waiting on -
 *   ARES-9 (the Sky Rail Gun) out of its cooldown, its scan and rift timers, ARES-0 (the Planet Destroyer) out of its
 *   cooldown, and the uplink tablet's drop timer - and spends the charge.</li>
 * </ul>
 */
public final class OrbitalLink {
    private OrbitalLink() {}

    public static void register() {
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!(source.getAttacker() instanceof ServerPlayerEntity p) || entity == p) return;
            if (!ModDamageTypes.ours(source) && !ModItems.isReliquary(p.getMainHandStack())) return;
            ModDataComponentTypes.Profile prof = ModDataComponentTypes.of(p);
            if (prof.aethericCharge() >= ModDataComponentTypes.MAX_CHARGE) return;
            prof.setAethericCharge(prof.aethericCharge() + 1);
            ReliquaryNet.sendCharge(p);
        });
        ServerPlayConnectionEvents.JOIN.register((h, s, server) -> ReliquaryNet.sendCharge(h.player));
        ServerPlayerEvents.COPY_FROM.register((oldP, newP, alive) ->
                ModDataComponentTypes.of(newP).setAethericCharge(ModDataComponentTypes.of(oldP).aethericCharge()));
        ServerPlayerEvents.AFTER_RESPAWN.register((oldP, newP, alive) -> ReliquaryNet.sendCharge(newP));
    }

    /** The link packet arrived. */
    public static void link(ServerPlayerEntity p) {
        ModDataComponentTypes.Profile prof = ModDataComponentTypes.of(p);
        if (prof.aethericCharge() < ModDataComponentTypes.MAX_CHARGE || p.getPitch() > ReliquaryItem.LINK_PITCH + 5
                || !(p.getMainHandStack().getItem() instanceof ReliquaryItem)) return;
        boolean any = false;
        RailgunEntity rail = RailgunEntity.findOwned(p.getServer(), p.getUuid());
        if (rail != null) any |= rail.orbitalLink();
        for (Ares0Entity a : Ares0Entity.owned(p.getServer(), p.getUuid())) any |= a.orbitalLink();
        if (p.getItemCooldownManager().isCoolingDown(RailgunMod.RAILGUN_REMOTE)) {
            p.getItemCooldownManager().remove(RailgunMod.RAILGUN_REMOTE);
            any = true;
        }
        if (!any) {
            p.playSound(ModSounds.VOID_ARC, SoundCategory.PLAYERS, 0.6f, 0.5f);   // nothing up there is waiting
            return;
        }
        prof.setAethericCharge(0);
        ReliquaryNet.sendCharge(p);
        p.getWorld().playSound(null, p.getX(), p.getY(), p.getZ(), ModSounds.ORBITAL_LINK, SoundCategory.PLAYERS, 2.5f, 1f);
        ReliquaryNet.broadcastLink(p);
    }
}

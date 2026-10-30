package com.creedpdf.orbitalrailgun.astraea;

import com.creedpdf.orbitalrailgun.RailgunMod;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Rarity;

/**
 * ASTRAEA // The Worldsplitter - a divine lance. Ascension (the ability key) opens a rift in the sky, a star is born in
 * it and its light comes down into the bearer: the lance is charged with one throw. The throw sends it round the
 * Earth faster and faster until it drills into the planet along its own orbit and splits the Earth in half -
 * permanently: a chasm tears across the land, and the other half hangs in the sky.
 */
public final class Astraea {
    private Astraea() {}

    public static final Item LANCE = Registry.register(Registries.ITEM, RailgunMod.id("astraea"),
            new AstraeaItem(new Item.Settings().maxCount(1).fireproof().rarity(Rarity.EPIC)));

    public static final SoundEvent S_AURA = sound("astraea.aura"), S_STANCE = sound("astraea.stance"), S_RIFT = sound("astraea.rift"),
            S_PULSE = sound("astraea.pulse"), S_DESCEND = sound("astraea.descend"), S_ABSORB = sound("astraea.absorb"),
            S_ASCENSION = sound("astraea.ascension"), S_THROW = sound("astraea.throw"), S_PASS = sound("astraea.pass"),
            S_DRILL = sound("astraea.drill"), S_SPLIT = sound("astraea.split"), S_QUAKE = sound("astraea.quake"),
            S_TEAR = sound("astraea.tear"), S_RETURN = sound("astraea.return");

    private static SoundEvent sound(String path) {
        var id = RailgunMod.id(path);
        return Registry.register(Registries.SOUND_EVENT, id, SoundEvent.of(id));
    }

    public static void init() {
        AstraeaNet.registerServer();
        ServerTickEvents.END_SERVER_TICK.register(AstraeaServer::tick);
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
                !(entity instanceof ServerPlayerEntity p) || AstraeaServer.allowDamage(p, source));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> SplitState.get(server).sync(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> AstraeaServer.forget(handler.player));
        ServerLifecycleEvents.SERVER_STOPPING.register(AstraeaServer::stopping);
    }
}

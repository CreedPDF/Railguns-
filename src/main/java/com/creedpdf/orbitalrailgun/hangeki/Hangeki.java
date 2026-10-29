package com.creedpdf.orbitalrailgun.hangeki;

import com.creedpdf.orbitalrailgun.RailgunMod;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.item.Item;
import net.minecraft.item.ToolMaterial;
import net.minecraft.recipe.Ingredient;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Rarity;

/**
 * HANGEKI // Reversal Edge: a sword whose one trick is to parry anything.
 * <ul>
 *   <li><b>Projectiles</b> (arrows, tridents, fireballs, wither skulls, firework rockets, KRL missiles, the railgun's
 *   beam, a warden's sonic boom): the meter in the bottom-left corner tracks what is coming; about three blocks out
 *   time slows hard (never stops). Parry (R or right-click) and a slow-motion stance cutscene plays, time snaps back,
 *   the thing hits the blade with REFLECTED impact frames and goes back where it came from at twice the force.</li>
 *   <li><b>Melee</b> (a mob or player hitting you, an explosion): parry raises a short guard. A blow landing in it is
 *   countered: no damage to you, double back to the attacker, who is thrown away and staggered.</li>
 * </ul>
 * Server logic in {@link ParryServer}; the world really slows down ({@link TimeScale}).
 */
public final class Hangeki {
    private Hangeki() {}

    /** Netherite-class: long-lived, sharp, enchantable like the best. */
    public static final ToolMaterial MATERIAL = new ToolMaterial() {
        @Override
        public int getDurability() {
            return 4062;
        }

        @Override
        public float getMiningSpeedMultiplier() {
            return 9f;
        }

        @Override
        public float getAttackDamage() {
            return 5f;
        }

        @Override
        public int getMiningLevel() {
            return 4;
        }

        @Override
        public int getEnchantability() {
            return 18;
        }

        @Override
        public Ingredient getRepairIngredient() {
            return Ingredient.ofItems(net.minecraft.item.Items.NETHERITE_INGOT);
        }
    };

    public static final Item SWORD = Registry.register(Registries.ITEM, RailgunMod.id("hangeki"),
            new HangekiItem(MATERIAL, 4, -2.3f, new Item.Settings().fireproof().rarity(Rarity.EPIC)));

    public static final SoundEvent S_WARN = sound("hangeki.warn"), S_SLOW = sound("hangeki.slow"), S_STANCE = sound("hangeki.stance"),
            S_IMPACT = sound("hangeki.impact"), S_REFLECT = sound("hangeki.reflect"), S_RESUME = sound("hangeki.resume"),
            S_COUNTER = sound("hangeki.counter"), S_GUARD = sound("hangeki.guard");

    private static SoundEvent sound(String path) {
        var id = RailgunMod.id(path);
        return Registry.register(Registries.SOUND_EVENT, id, SoundEvent.of(id));
    }

    public static void init() {
        HangekiNet.registerServer();
        ServerTickEvents.START_WORLD_TICK.register(ParryServer::tickWorld);
        ServerTickEvents.END_SERVER_TICK.register(ParryServer::endServerTick);
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
                !(entity instanceof ServerPlayerEntity p) || ParryServer.allowDamage(p, source, amount));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> ParryServer.forget(handler.player));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> ParryServer.clear());
    }
}

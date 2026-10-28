package com.creedpdf.orbitalrailgun;

import com.creedpdf.orbitalrailgun.block.RailgunColliderBlock;
import com.creedpdf.orbitalrailgun.entity.RailgunEntity;
import com.creedpdf.orbitalrailgun.item.RailgunRemoteItem;
import com.creedpdf.orbitalrailgun.network.ModPackets;
import com.creedpdf.orbitalrailgun.registry.ModParticles;
import com.creedpdf.orbitalrailgun.registry.ModSounds;
import com.creedpdf.orbitalrailgun.util.DeploymentScheduler;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.piston.PistonBehavior;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.damage.DamageType;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common entrypoint. Registers everything that must exist on both the client and the server:
 * sounds, the railgun entity type, the remote item, network receivers and the deployment
 * scheduler.
 *
 * <p>No blocks are registered: the railgun is an entity (see {@link RailgunEntity} for why),
 * so it needs no placeholder block or block entity.</p>
 */
public final class RailgunMod implements ModInitializer {
    public static final String MOD_ID = "orbital_railgun";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** Replaced by the client entrypoint; stays a no-op on dedicated servers. */
    private static RailgunClientBridge clientBridge = RailgunClientBridge.NO_OP;

    public static final RegistryKey<DamageType> BEAM_DAMAGE =
            RegistryKey.of(RegistryKeys.DAMAGE_TYPE, id("beam"));

    public static final EntityType<RailgunEntity> RAILGUN = Registry.register(
            Registries.ENTITY_TYPE,
            id("railgun"),
            FabricEntityTypeBuilder.<RailgunEntity>create(SpawnGroup.MISC, RailgunEntity::new)
                    .dimensions(EntityDimensions.fixed(12.0f, 8.0f))
                    // Large tracking range so the beam stays visible from afar; update every
                    // tick so the high-speed orbital drop is smooth on clients.
                    .trackRangeChunks(16)
                    .trackedUpdateRate(1)
                    .forceTrackedVelocityUpdates(true)
                    .fireImmune()
                    .build());


    /** ARES-0, the planet killer. */
    public static final EntityType<com.creedpdf.orbitalrailgun.entity.Ares0Entity> ARES0 = Registry.register(
            Registries.ENTITY_TYPE,
            id("ares0"),
            FabricEntityTypeBuilder.<com.creedpdf.orbitalrailgun.entity.Ares0Entity>create(SpawnGroup.MISC, com.creedpdf.orbitalrailgun.entity.Ares0Entity::new)
                    .dimensions(EntityDimensions.fixed(9.0f, 9.0f))
                    .trackRangeChunks(16)
                    .trackedUpdateRate(1)
                    .forceTrackedVelocityUpdates(true)
                    .fireImmune()
                    .build());

    /** A burning piece of a destroyed planet falling to Earth. */
    public static final EntityType<com.creedpdf.orbitalrailgun.entity.MeteorEntity> METEOR = Registry.register(
            Registries.ENTITY_TYPE,
            id("meteor"),
            FabricEntityTypeBuilder.<com.creedpdf.orbitalrailgun.entity.MeteorEntity>create(SpawnGroup.MISC, com.creedpdf.orbitalrailgun.entity.MeteorEntity::new)
                    .dimensions(EntityDimensions.fixed(1.5f, 1.5f))
                    .trackRangeChunks(20)
                    .trackedUpdateRate(1)
                    .forceTrackedVelocityUpdates(true)
                    .fireImmune()
                    .build());

    /** The ARES starship. */
    public static final EntityType<com.creedpdf.orbitalrailgun.entity.ShipEntity> SHIP = Registry.register(
            Registries.ENTITY_TYPE,
            id("starship"),
            FabricEntityTypeBuilder.<com.creedpdf.orbitalrailgun.entity.ShipEntity>create(SpawnGroup.MISC, com.creedpdf.orbitalrailgun.entity.ShipEntity::new)
                    .dimensions(EntityDimensions.fixed(1.0f, 1.0f))
                    .trackRangeChunks(24)
                    .trackedUpdateRate(1)
                    .forceTrackedVelocityUpdates(true)
                    .fireImmune()
                    .build());

    /** The see-through rift opening (Immersive Portals portal). */
    public static final EntityType<com.creedpdf.orbitalrailgun.space.RiftPortal> RIFT_PORTAL = Registry.register(
            Registries.ENTITY_TYPE, id("rift_portal"), com.creedpdf.orbitalrailgun.space.RiftPortal.TYPE);

    public static final Item EVA_HELMET = eva("eva_helmet", net.minecraft.item.ArmorItem.Type.HELMET);
    public static final Item EVA_CHESTPLATE = eva("eva_chestplate", net.minecraft.item.ArmorItem.Type.CHESTPLATE);
    public static final Item EVA_LEGGINGS = eva("eva_leggings", net.minecraft.item.ArmorItem.Type.LEGGINGS);
    public static final Item EVA_BOOTS = eva("eva_boots", net.minecraft.item.ArmorItem.Type.BOOTS);

    private static Item eva(String name, net.minecraft.item.ArmorItem.Type type) {
        return Registry.register(Registries.ITEM, id(name),
                new com.creedpdf.orbitalrailgun.item.EvaSuitItem(type, new Item.Settings().maxCount(1).fireproof().rarity(Rarity.EPIC)));
    }

    /** Invisible collision for the deployed railgun (see RailgunColliderBlock). No item. */
    public static final Block RAILGUN_COLLIDER = Registry.register(
            Registries.BLOCK,
            id("railgun_collider"),
            new RailgunColliderBlock(AbstractBlock.Settings.create()
                    .strength(-1.0f, 3_600_000.0f)
                    .dropsNothing()
                    .nonOpaque()
                    .noBlockBreakParticles()
                    .pistonBehavior(PistonBehavior.BLOCK)
                    .sounds(BlockSoundGroup.METAL)
                    .allowsSpawning((state, world, pos, type) -> false)
                    .solidBlock((state, world, pos) -> false)
                    .suffocates((state, world, pos) -> false)
                    .blockVision((state, world, pos) -> false)));

    /** The starship's flight console (placed in the cabin of a parked ship). */
    public static final Block FLIGHT_CONSOLE = Registry.register(
            Registries.BLOCK,
            id("flight_console"),
            new com.creedpdf.orbitalrailgun.block.FlightConsoleBlock(AbstractBlock.Settings.create()
                    .strength(-1.0f, 3_600_000.0f)
                    .dropsNothing()
                    .nonOpaque()
                    .luminance(state -> 9)
                    .pistonBehavior(PistonBehavior.BLOCK)
                    .sounds(BlockSoundGroup.METAL)));

    public static final Item FLIGHT_CONSOLE_ITEM = Registry.register(Registries.ITEM, id("flight_console"),
            new net.minecraft.item.BlockItem(FLIGHT_CONSOLE, new Item.Settings()));

    public static final Item STARSHIP = Registry.register(Registries.ITEM, id("starship"),
            new com.creedpdf.orbitalrailgun.item.StarshipItem(new Item.Settings().maxCount(1).fireproof().rarity(Rarity.EPIC)));

    public static final Item RAILGUN_REMOTE = Registry.register(
            Registries.ITEM,
            id("railgun_remote"),
            new RailgunRemoteItem(new Item.Settings().maxCount(1).rarity(Rarity.EPIC)));

    @Override
    public void onInitialize() {
        ModSounds.init();
        ModParticles.init();
        ModPackets.registerServerReceivers();
        com.creedpdf.orbitalrailgun.krl.Krl.init();
        com.creedpdf.orbitalrailgun.legion.ArsenalLegion.init();

        // Deluxs Armory has three creative tabs: Railguns (the orbital weapons, the ship, the EVA suit), Military (the KRL
        // launcher system) and Arsenal Legion (the autonomous weapon swarm).
        Registry.register(Registries.ITEM_GROUP, id("main"), net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup.builder()
                .icon(() -> new net.minecraft.item.ItemStack(RAILGUN_REMOTE))
                .displayName(net.minecraft.text.Text.translatable("itemGroup.orbital_railgun.main"))
                .entries((context, entries) -> {
                    entries.add(RAILGUN_REMOTE);
                    entries.add(STARSHIP);
                    entries.add(FLIGHT_CONSOLE_ITEM);
                    entries.add(EVA_HELMET);
                    entries.add(EVA_CHESTPLATE);
                    entries.add(EVA_LEGGINGS);
                    entries.add(EVA_BOOTS);
                })
                .build());
        Registry.register(Registries.ITEM_GROUP, id("military"), net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup.builder()
                .icon(() -> new net.minecraft.item.ItemStack(com.creedpdf.orbitalrailgun.krl.Krl.SPEAR))
                .displayName(net.minecraft.text.Text.translatable("itemGroup.orbital_railgun.military"))
                .entries((context, entries) -> {
                    entries.add(com.creedpdf.orbitalrailgun.krl.Krl.LAUNCHER_ITEM);
                    entries.add(com.creedpdf.orbitalrailgun.krl.Krl.CONSOLE_ITEM);
                    entries.add(com.creedpdf.orbitalrailgun.krl.Krl.STATION_ITEM);
                    entries.add(com.creedpdf.orbitalrailgun.krl.Krl.MAGAZINE_ITEM);
                    for (int lv = 0; lv < com.creedpdf.orbitalrailgun.krl.Krl.LIVERIES.length; lv++)
                        entries.add(com.creedpdf.orbitalrailgun.krl.KineticSpearItem.withLivery(new net.minecraft.item.ItemStack(com.creedpdf.orbitalrailgun.krl.Krl.SPEAR), lv));
                })
                .build());

        Registry.register(Registries.ITEM_GROUP, id("arsenal_legion"), net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup.builder()
                .icon(() -> new net.minecraft.item.ItemStack(com.creedpdf.orbitalrailgun.legion.ArsenalLegion.ALTAR_ITEM))
                .displayName(net.minecraft.text.Text.translatable("itemGroup.orbital_railgun.arsenal_legion"))
                .entries((context, entries) -> entries.add(com.creedpdf.orbitalrailgun.legion.ArsenalLegion.ALTAR_ITEM))
                .build());

        ServerTickEvents.END_WORLD_TICK.register(DeploymentScheduler::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> DeploymentScheduler.clear());

        // v3.5 rift dimension: anyone still inside is sent home (the rift now opens into real space)
        ServerTickEvents.END_WORLD_TICK.register(com.creedpdf.orbitalrailgun.rift.RiftDimension::tick);

        // v4: space, planets, EVA suit, ARES-0
        ServerTickEvents.END_WORLD_TICK.register(com.creedpdf.orbitalrailgun.space.SpaceServer::tick);
        ServerTickEvents.END_SERVER_TICK.register(com.creedpdf.orbitalrailgun.space.SpaceServer::tickBlasts);
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                com.creedpdf.orbitalrailgun.space.PlanetState.get(server).sync(handler.player));
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                com.creedpdf.orbitalrailgun.space.SpaceServer.forget(handler.player));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> com.creedpdf.orbitalrailgun.space.RiftPortals.clear());
        // EVA boots: shock absorbers (no fall damage)
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
                !(source.isOf(net.minecraft.entity.damage.DamageTypes.FALL) && com.creedpdf.orbitalrailgun.space.EvaSuit.boots(entity)));

        LOGGER.info("Deluxs Armory online. Uplink established.");
    }

    public static Identifier id(String path) {
        return new Identifier(MOD_ID, path);
    }

    public static RailgunClientBridge client() {
        return clientBridge;
    }

    public static void setClientBridge(RailgunClientBridge bridge) {
        clientBridge = bridge;
    }
}

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
                    .dimensions(EntityDimensions.fixed(10.0f, 8.5f))
                    // Large tracking range so the beam stays visible from afar; update every
                    // tick so the high-speed orbital drop is smooth on clients.
                    .trackRangeChunks(16)
                    .trackedUpdateRate(1)
                    .forceTrackedVelocityUpdates(true)
                    .fireImmune()
                    .build());

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

    public static final Item RAILGUN_REMOTE = Registry.register(
            Registries.ITEM,
            id("railgun_remote"),
            new RailgunRemoteItem(new Item.Settings().maxCount(1).rarity(Rarity.EPIC)));

    @Override
    public void onInitialize() {
        ModSounds.init();
        ModParticles.init();
        ModPackets.registerServerReceivers();

        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> entries.add(RAILGUN_REMOTE));
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.COMBAT).register(entries -> entries.add(RAILGUN_REMOTE));

        ServerTickEvents.END_WORLD_TICK.register(DeploymentScheduler::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> DeploymentScheduler.clear());

        LOGGER.info("Orbital Railgun online. Uplink established.");
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

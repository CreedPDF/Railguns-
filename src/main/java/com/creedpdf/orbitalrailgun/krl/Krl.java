package com.creedpdf.orbitalrailgun.krl;

import com.creedpdf.orbitalrailgun.RailgunMod;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.MapColor;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.block.piston.PistonBehavior;
import net.minecraft.entity.damage.DamageType;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;

/**
 * KRL - the Kinetic Rail Launcher. A launch pad with a rail tower that takes a kinetic spear; a fire-control console
 * (3 blocks wide) that links up to five launchers and picks a target on its map up to 2000 blocks away. On launch the
 * spear's motor burns on the rail for a minute, then it leaves with a sonic boom, climbs to the stratosphere, stalls,
 * noses over and falls back at Mach 13 with its tip wrapped in plasma, and hits in a superheated plasma explosion.
 */
public final class Krl {
    private Krl() {}

    // ---- rules
    public static final double RANGE = 2000;
    public static final int MAX_LINKS = 5;
    /** The motor burns on the rail this long before the spear leaves (a minute). */
    public static final int IGNITION = 1200;
    /** How close a player must be to use a console, and how far a launcher may be linked from it. */
    public static final double USE_RANGE = 8, LINK_RANGE = 256;

    // ---- registry
    public static final KrlLauncherBlock LAUNCHER = Registry.register(Registries.BLOCK, RailgunMod.id("krl_launcher"),
            new KrlLauncherBlock(AbstractBlock.Settings.create().mapColor(MapColor.BLACK).strength(6f, 1200f).requiresTool().nonOpaque()
                    .pistonBehavior(PistonBehavior.BLOCK).sounds(BlockSoundGroup.NETHERITE).luminance(s -> 4)));
    public static final KrlConsoleBlock CONSOLE = Registry.register(Registries.BLOCK, RailgunMod.id("krl_console"),
            new KrlConsoleBlock(AbstractBlock.Settings.create().mapColor(MapColor.BLACK).strength(4f, 600f).requiresTool().nonOpaque()
                    .pistonBehavior(PistonBehavior.BLOCK).sounds(BlockSoundGroup.NETHERITE).luminance(s -> 7)));
    public static final KrlConsolePartBlock CONSOLE_PART = Registry.register(Registries.BLOCK, RailgunMod.id("krl_console_part"),
            new KrlConsolePartBlock(AbstractBlock.Settings.create().mapColor(MapColor.BLACK).strength(4f, 600f).requiresTool().nonOpaque()
                    .pistonBehavior(PistonBehavior.BLOCK).sounds(BlockSoundGroup.NETHERITE).dropsNothing().luminance(s -> 7)));

    public static final Item LAUNCHER_ITEM = Registry.register(Registries.ITEM, RailgunMod.id("krl_launcher"),
            new BlockItem(LAUNCHER, new Item.Settings().rarity(Rarity.EPIC)));
    public static final Item CONSOLE_ITEM = Registry.register(Registries.ITEM, RailgunMod.id("krl_console"),
            new BlockItem(CONSOLE, new Item.Settings().rarity(Rarity.EPIC)));
    public static final Item SPEAR = Registry.register(Registries.ITEM, RailgunMod.id("kinetic_spear"),
            new KineticSpearItem(new Item.Settings().maxCount(4).rarity(Rarity.EPIC).fireproof()));

    public static final BlockEntityType<KrlLauncherBlockEntity> LAUNCHER_BE = Registry.register(Registries.BLOCK_ENTITY_TYPE,
            RailgunMod.id("krl_launcher"), FabricBlockEntityTypeBuilder.create(KrlLauncherBlockEntity::new, LAUNCHER).build());
    public static final BlockEntityType<KrlConsoleBlockEntity> CONSOLE_BE = Registry.register(Registries.BLOCK_ENTITY_TYPE,
            RailgunMod.id("krl_console"), FabricBlockEntityTypeBuilder.create(KrlConsoleBlockEntity::new, CONSOLE).build());

    public static final RegistryKey<DamageType> PLASMA = RegistryKey.of(RegistryKeys.DAMAGE_TYPE, RailgunMod.id("krl_plasma"));

    // ---- sounds
    public static final SoundEvent S_LOAD = sound("krl.load"), S_LINK = sound("krl.link"), S_CLICK = sound("krl.click"), S_ARM = sound("krl.arm"),
            S_SIREN = sound("krl.siren"), S_IGNITE = sound("krl.ignite"), S_BURN = sound("krl.burn"), S_BOOM = sound("krl.boom"),
            S_CLIMB = sound("krl.climb"), S_STALL = sound("krl.stall"), S_WIND = sound("krl.wind"), S_PLASMA = sound("krl.plasma"),
            S_INCOMING = sound("krl.incoming"), S_IMPACT = sound("krl.impact");

    private static SoundEvent sound(String name) {
        Identifier id = RailgunMod.id(name);
        return Registry.register(Registries.SOUND_EVENT, id, SoundEvent.of(id));
    }

    public static void init() {
        KrlNet.register();
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_WORLD_TICK.register(KrlServer::tick);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> KrlServer.join(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            KrlServer.release(handler.player);
            KrlLinking.clear(handler.player);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(KrlServer::stopping);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            KrlServer.clear();
            KrlLinking.clear();
            KrlMap.clear();
        });
    }

    @SuppressWarnings("unused")
    private static final Block[] ALL = {LAUNCHER, CONSOLE, CONSOLE_PART};
}

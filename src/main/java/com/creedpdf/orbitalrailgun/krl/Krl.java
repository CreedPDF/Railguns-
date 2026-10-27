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
 * turret slews to the target, the motor lights and burns on the inclined rail for two seconds, then the missile leaves
 * with a sonic boom, climbs to the stratosphere, stalls, noses over and falls back at Mach 13 with its tip glowing in
 * a plasma sheath, and explodes on impact.
 */
public final class Krl {
    private Krl() {}

    // ---- rules
    /** Standard motor range; boosted motors reach further (KrlLoadout.RANGE). */
    public static final double RANGE = 2000;
    public static final int MAX_LINKS = 5;
    /** From LAUNCH to liftoff: the turret slews to the target, then the motor lights and burns on the rail for FLAME ticks. */
    public static final int SLEW = 30, FLAME = 40, COUNTDOWN = SLEW + FLAME;
    /** Paint schemes for the kinetic missile (textures/krl/spear_<name>.png). */
    public static final String[] LIVERIES = {"standard", "olive", "arctic", "desert", "darkgrey", "test"};
    public static final String[] LIVERY_NAMES = {"Standard Grey", "Olive Drab", "Arctic White", "Desert Tan", "Dark Gunship Grey", "Test Round"};

    public static int livery(int i) {
        return Math.floorMod(i, LIVERIES.length);
    }
    /** How close a player must be to use a console, and how far a launcher may be linked from it. */
    public static final double USE_RANGE = 8, LINK_RANGE = 256;
    /** Interceptors a magazine holds. */
    public static final int MAG_CAP = 30;

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

    public static final KrlStationBlock STATION = Registry.register(Registries.BLOCK, RailgunMod.id("krl_station"),
            new KrlStationBlock(AbstractBlock.Settings.create().mapColor(MapColor.GRAY).strength(4f, 600f).requiresTool().nonOpaque()
                    .pistonBehavior(PistonBehavior.BLOCK).sounds(BlockSoundGroup.NETHERITE).luminance(s -> 3)));

    public static final KrlMagazineBlock MAGAZINE = Registry.register(Registries.BLOCK, RailgunMod.id("krl_magazine"),
            new KrlMagazineBlock(AbstractBlock.Settings.create().mapColor(MapColor.DARK_GREEN).strength(5f, 1200f).requiresTool().nonOpaque()
                    .pistonBehavior(PistonBehavior.BLOCK).sounds(BlockSoundGroup.NETHERITE).luminance(s -> 3)));

    public static final KrlMagazinePartBlock MAGAZINE_PART = Registry.register(Registries.BLOCK, RailgunMod.id("krl_magazine_part"),
            new KrlMagazinePartBlock(AbstractBlock.Settings.create().mapColor(MapColor.DARK_GREEN).strength(5f, 1200f).requiresTool().nonOpaque()
                    .pistonBehavior(PistonBehavior.BLOCK).sounds(BlockSoundGroup.NETHERITE).dropsNothing()));

    public static final Item LAUNCHER_ITEM = Registry.register(Registries.ITEM, RailgunMod.id("krl_launcher"),
            new KrlBlockItem(LAUNCHER, new Item.Settings().rarity(Rarity.EPIC), "Load with a kinetic missile. Sneak-use empty-handed to pick it for linking."));
    public static final Item CONSOLE_ITEM = Registry.register(Registries.ITEM, RailgunMod.id("krl_console"),
            new KrlBlockItem(CONSOLE, new Item.Settings().rarity(Rarity.EPIC), "3 blocks wide. Links up to 5 launchers and aims them on a terrain map."));
    public static final Item STATION_ITEM = Registry.register(Registries.ITEM, RailgunMod.id("krl_station"),
            new KrlBlockItem(STATION, new Item.Settings().rarity(Rarity.EPIC), "Use with a kinetic missile in hand to fit warheads, charges and motors."));
    public static final Item MAGAZINE_ITEM = Registry.register(Registries.ITEM, RailgunMod.id("krl_magazine"),
            new KrlBlockItem(MAGAZINE, new Item.Settings().rarity(Rarity.EPIC), "3x3x2 rack, 30 interceptors. Place beside a launcher; the console radar fires them."));
    public static final Item SPEAR = Registry.register(Registries.ITEM, RailgunMod.id("kinetic_spear"),
            new KineticSpearItem(new Item.Settings().maxCount(4).rarity(Rarity.EPIC).fireproof()));

    public static final BlockEntityType<KrlLauncherBlockEntity> LAUNCHER_BE = Registry.register(Registries.BLOCK_ENTITY_TYPE,
            RailgunMod.id("krl_launcher"), FabricBlockEntityTypeBuilder.create(KrlLauncherBlockEntity::new, LAUNCHER).build());
    public static final BlockEntityType<KrlConsoleBlockEntity> CONSOLE_BE = Registry.register(Registries.BLOCK_ENTITY_TYPE,
            RailgunMod.id("krl_console"), FabricBlockEntityTypeBuilder.create(KrlConsoleBlockEntity::new, CONSOLE).build());
    public static final BlockEntityType<KrlStationBlockEntity> STATION_BE = Registry.register(Registries.BLOCK_ENTITY_TYPE,
            RailgunMod.id("krl_station"), FabricBlockEntityTypeBuilder.create(KrlStationBlockEntity::new, STATION).build());

    public static final BlockEntityType<KrlMagazineBlockEntity> MAGAZINE_BE = Registry.register(Registries.BLOCK_ENTITY_TYPE,
            RailgunMod.id("krl_magazine"), FabricBlockEntityTypeBuilder.create(KrlMagazineBlockEntity::new, MAGAZINE).build());

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
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_WORLD_TICK.register(KrlDefense::tick);
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
            KrlDefense.clear();
        });
    }

    @SuppressWarnings("unused")
    private static final Block[] ALL = {LAUNCHER, CONSOLE, CONSOLE_PART, STATION, MAGAZINE, MAGAZINE_PART};
}

package com.creedpdf.orbitalrailgun.station;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.krl.KrlBlockItem;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.MapColor;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.block.piston.PistonBehavior;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;

/**
 * Space Command: an orbital station built module by module.
 * <ul>
 *   <li>{@link ScHubBlock Command Hub} (3 x 3 x 3): the station's core. Every module within {@link #LINK} blocks joins
 *       its grid; its console shows power, modules and threats, and runs debris drills.</li>
 *   <li>Solar Array: rolls out its blankets once placed and powers the grid ({@link #SOLAR_KW} kW in orbit, less in
 *       daylight on a planet, nothing at night).</li>
 *   <li>Station Railgun: point defence. Powered, it tracks and shoots down meteorites - the debris of a destroyed planet
 *       streaming past the station, or a drill swarm - before they hit.</li>
 *   <li>Strike Satellite: charges from the grid (and keeps charging in orbit while you are away); fire it from the
 *       uplink tablet's SAT page at a point on the ground below you.</li>
 * </ul>
 */
public final class Sc {
    private Sc() {}

    // ---- rules
    public static final int LINK = 32;
    public static final float SOLAR_KW = 40f, HUB_KW = 20f, RAILGUN_KW = 30f, SAT_KW = 60f;
    /** Satellite: ticks to charge fully at full power; strike reach from the firing player. */
    public static final int SAT_CHARGE_TICKS = 2400;
    public static final double STRIKE_RANGE = 384;
    /** Station railgun reach, slew rate (radians per tick), capacitor charge and recovery. */
    public static final double RAIL_RANGE = 150;
    public static final float RAIL_SLEW = 0.14f;
    public static final int RAIL_CHARGE = 16, RAIL_COOLDOWN = 22;
    /** Solar blankets take this long to roll out after placement. */
    public static final int SOLAR_DEPLOY = 100;
    /** Debris drill length. */
    public static final int DRILL_TICKS = 900;

    // ---- registry
    private static AbstractBlock.Settings metal(MapColor c, int light) {
        return AbstractBlock.Settings.create().mapColor(c).strength(5f, 1200f).requiresTool().nonOpaque()
                .pistonBehavior(PistonBehavior.BLOCK).sounds(BlockSoundGroup.NETHERITE).luminance(s -> light);
    }

    public static final ScHubBlock HUB = Registry.register(Registries.BLOCK, RailgunMod.id("sc_hub"), new ScHubBlock(metal(MapColor.WHITE_GRAY, 6)));
    public static final ScHubPartBlock HUB_PART = Registry.register(Registries.BLOCK, RailgunMod.id("sc_hub_part"),
            new ScHubPartBlock(metal(MapColor.WHITE_GRAY, 0).dropsNothing()));
    public static final ScModuleBlock SOLAR = Registry.register(Registries.BLOCK, RailgunMod.id("sc_solar"),
            new ScModuleBlock(metal(MapColor.BLACK, 2), ScModuleBlock.Kind.SOLAR));
    public static final ScModuleBlock RAILGUN = Registry.register(Registries.BLOCK, RailgunMod.id("sc_railgun"),
            new ScModuleBlock(metal(MapColor.BLACK, 3), ScModuleBlock.Kind.RAILGUN));
    public static final ScModuleBlock SATELLITE = Registry.register(Registries.BLOCK, RailgunMod.id("sc_satellite"),
            new ScModuleBlock(metal(MapColor.GOLD, 4), ScModuleBlock.Kind.SATELLITE));

    public static final Item HUB_ITEM = Registry.register(Registries.ITEM, RailgunMod.id("sc_hub"),
            new KrlBlockItem(HUB, new Item.Settings().rarity(Rarity.EPIC), "3x3x3 station core. Modules within 32 blocks join its power grid."));
    public static final Item SOLAR_ITEM = Registry.register(Registries.ITEM, RailgunMod.id("sc_solar"),
            new KrlBlockItem(SOLAR, new Item.Settings().rarity(Rarity.EPIC), "Rolls out two solar blankets. 40 kW in orbit, less on a planet by day."));
    public static final Item RAILGUN_ITEM = Registry.register(Registries.ITEM, RailgunMod.id("sc_railgun"),
            new KrlBlockItem(RAILGUN, new Item.Settings().rarity(Rarity.EPIC), "Point defence. Needs 30 kW. Shoots down meteorites within 150 blocks."));
    public static final Item SATELLITE_ITEM = Registry.register(Registries.ITEM, RailgunMod.id("sc_satellite"),
            new KrlBlockItem(SATELLITE, new Item.Settings().rarity(Rarity.EPIC), "Orbital strike. Charges on 60 kW; fire it from the uplink tablet's SAT page."));

    public static final BlockEntityType<ScHubBlockEntity> HUB_BE = Registry.register(Registries.BLOCK_ENTITY_TYPE, RailgunMod.id("sc_hub"),
            FabricBlockEntityTypeBuilder.create(ScHubBlockEntity::new, HUB).build());
    public static final BlockEntityType<ScSolarBlockEntity> SOLAR_BE = Registry.register(Registries.BLOCK_ENTITY_TYPE, RailgunMod.id("sc_solar"),
            FabricBlockEntityTypeBuilder.create(ScSolarBlockEntity::new, SOLAR).build());
    public static final BlockEntityType<ScRailgunBlockEntity> RAILGUN_BE = Registry.register(Registries.BLOCK_ENTITY_TYPE, RailgunMod.id("sc_railgun"),
            FabricBlockEntityTypeBuilder.create(ScRailgunBlockEntity::new, RAILGUN).build());
    public static final BlockEntityType<ScSatelliteBlockEntity> SATELLITE_BE = Registry.register(Registries.BLOCK_ENTITY_TYPE, RailgunMod.id("sc_satellite"),
            FabricBlockEntityTypeBuilder.create(ScSatelliteBlockEntity::new, SATELLITE).build());

    // ---- sounds (synthesised by tools/sfx/sc_sounds.py)
    public static final SoundEvent S_LINK = sound("sc.link"), S_DEPLOY = sound("sc.deploy"), S_HUM = sound("sc.hum"),
            S_ALARM = sound("sc.alarm"), S_RAIL_CHARGE = sound("sc.rail_charge"), S_RAIL_FIRE = sound("sc.rail_fire"),
            S_RAIL_HIT = sound("sc.rail_hit"), S_SAT_UPLINK = sound("sc.sat_uplink"), S_SAT_CHARGE = sound("sc.sat_charge"),
            S_SAT_FIRE = sound("sc.sat_fire"), S_STRIKE_BEAM = sound("sc.strike_beam"), S_STRIKE_IMPACT = sound("sc.strike_impact"),
            S_STRIKE_RUMBLE = sound("sc.strike_rumble");

    private static SoundEvent sound(String name) {
        Identifier id = RailgunMod.id(name);
        return Registry.register(Registries.SOUND_EVENT, id, SoundEvent.of(id));
    }

    public static void init() {
        ScNet.register();
        ServerTickEvents.END_WORLD_TICK.register(ScDebris::tick);
        ServerTickEvents.END_SERVER_TICK.register(ScStrike::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            ScGrid.clear();
            ScDebris.clear();
            ScStrike.clear();
        });
    }
}

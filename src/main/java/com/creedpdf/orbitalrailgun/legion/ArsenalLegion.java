package com.creedpdf.orbitalrailgun.legion;

import com.creedpdf.orbitalrailgun.krl.KrlBlockItem;
import com.creedpdf.orbitalrailgun.legion.block.LegionAltarBlock;
import com.creedpdf.orbitalrailgun.legion.block.LegionAltarBlockEntity;
import com.creedpdf.orbitalrailgun.legion.entity.AutonomousWeaponEntity;
import com.creedpdf.orbitalrailgun.legion.network.LegionPackets;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.fabricmc.fabric.api.particle.v1.FabricParticleTypes;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.MapColor;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.block.piston.PistonBehavior;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.item.Item;
import net.minecraft.particle.DefaultParticleType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;

/**
 * Arsenal Legion: Autonomous Weapon Swarms. Bind your weapons at a Legion Altar and they leave your hands to fly
 * around you as a telekinetic swarm (see {@link SwarmManager}): they orbit you, dash through whatever you attack or
 * whatever attacks you, and - when the swarm holds a shield or a heavy weapon - intercept projectiles fired at you.
 *
 * <p>Everything here lives in its own namespace, {@code arsenal_legion}, inside the Deluxs Armory jar:</p>
 * <ul>
 *   <li>entity {@code arsenal_legion:autonomous_weapon} ({@link AutonomousWeaponEntity}), drawn with the Blender-built
 *       harness model {@code arsenal_legion:models/entity/floating_blade};</li>
 *   <li>block {@code arsenal_legion:legion_altar} ({@link LegionAltarBlock});</li>
 *   <li>attribute {@code arsenal_legion:swarm_capacity} (default 6 weapons);</li>
 *   <li>particle {@code arsenal_legion:kinetic_gaze}; sounds {@code arsenal_legion:swarm_hum},
 *       {@code arsenal_legion:weapon_launch}, {@code arsenal_legion:weapon_pierce}, {@code arsenal_legion:weapon_parry},
 *       {@code arsenal_legion:altar_bind};</li>
 *   <li>item tag {@code arsenal_legion:swarm_weapons}: extra items (beyond swords, axes, tridents and shields) that may
 *       join a swarm.</li>
 * </ul>
 */
public final class ArsenalLegion {
    private ArsenalLegion() {}

    public static final String NS = "arsenal_legion";

    public static Identifier id(String path) {
        return new Identifier(NS, path);
    }

    // ---- attribute (registered early, see SwarmAttributes)
    public static final EntityAttribute SWARM_CAPACITY = SwarmAttributes.SWARM_CAPACITY;

    // ---- entity
    public static final EntityType<AutonomousWeaponEntity> WEAPON = Registry.register(Registries.ENTITY_TYPE, id("autonomous_weapon"),
            FabricEntityTypeBuilder.<AutonomousWeaponEntity>create(SpawnGroup.MISC, AutonomousWeaponEntity::new)
                    .dimensions(EntityDimensions.fixed(0.6f, 0.6f))
                    .trackRangeBlocks(80)
                    .trackedUpdateRate(1)
                    .forceTrackedVelocityUpdates(true)
                    .disableSaving()       // weapons are stored in the player's swarm, never in the world
                    .disableSummon()
                    .fireImmune()
                    .build());

    // ---- altar
    public static final LegionAltarBlock ALTAR = Registry.register(Registries.BLOCK, id("legion_altar"),
            new LegionAltarBlock(AbstractBlock.Settings.create().mapColor(MapColor.BLACK).strength(8f, 1200f).requiresTool().nonOpaque()
                    .pistonBehavior(PistonBehavior.BLOCK).sounds(BlockSoundGroup.NETHERITE).luminance(s -> 9)));
    public static final Item ALTAR_ITEM = Registry.register(Registries.ITEM, id("legion_altar"),
            new KrlBlockItem(ALTAR, new Item.Settings().rarity(Rarity.EPIC),
                    "Use a sword, axe, trident or shield on it to bind it to your swarm. Sneak-use empty-handed to release them all."));
    public static final BlockEntityType<LegionAltarBlockEntity> ALTAR_BE = Registry.register(Registries.BLOCK_ENTITY_TYPE, id("legion_altar"),
            FabricBlockEntityTypeBuilder.create(LegionAltarBlockEntity::new, ALTAR).build());

    // ---- tag, particle, sounds
    public static final TagKey<Item> SWARM_WEAPONS = TagKey.of(RegistryKeys.ITEM, id("swarm_weapons"));
    public static final DefaultParticleType KINETIC_GAZE = Registry.register(Registries.PARTICLE_TYPE, id("kinetic_gaze"), FabricParticleTypes.simple(true));
    public static final SoundEvent SWARM_HUM = sound("swarm_hum"), WEAPON_LAUNCH = sound("weapon_launch"), WEAPON_PIERCE = sound("weapon_pierce"),
            WEAPON_PARRY = sound("weapon_parry"), ALTAR_BIND = sound("altar_bind");

    private static SoundEvent sound(String name) {
        Identifier i = id(name);
        return Registry.register(Registries.SOUND_EVENT, i, SoundEvent.of(i));
    }

    /** Called from the mod initialiser: forces the registrations above and hooks the swarm into the game loop. */
    public static void init() {
        LegionPackets.registerServer();
        SwarmManager.register();
    }
}

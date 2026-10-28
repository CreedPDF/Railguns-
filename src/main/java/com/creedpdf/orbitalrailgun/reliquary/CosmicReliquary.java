package com.creedpdf.orbitalrailgun.reliquary;

import com.creedpdf.orbitalrailgun.reliquary.logic.MoltenScars;
import com.creedpdf.orbitalrailgun.reliquary.logic.OrbitalLink;
import com.creedpdf.orbitalrailgun.reliquary.logic.StarEaterBeams;
import com.creedpdf.orbitalrailgun.reliquary.network.ReliquaryNet;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * The Cosmic Reliquary Handheld Arsenal: three handheld weapons, in their own creative tab and their own namespace
 * ({@code cosmic_reliquary}) inside the Deluxs Armory jar.
 * <ul>
 *   <li>{@link ModItems#STAR_EATER_GAZE The Star-Eater's Gaze} - hold attack: a twisting stellar ray that pins whatever
 *   it touches; hold it 3 seconds and it collapses into a singularity that drags everything in and erases it.</li>
 *   <li>{@link ModItems#VOID_CARVER_BLADE Void-Carver's Edge} - each swing cuts a plane of space, marks everything it
 *   crosses, and leaves a rift that arcs into the marked for five seconds.</li>
 *   <li>{@link ModItems#CORE_REVOLVER The Core-Infused Revolver} - a round that pierces every mob in 64 blocks and
 *   scars the surface it hits with molten heat that burns whoever walks on it.</li>
 * </ul>
 * Kills with them build the player's Aetheric Charge (shown only as a ring round the crosshair). At 100, looking
 * straight up and using any of them fires the Orbital Link: it resets the cooldowns of the player's ARES-9 railgun,
 * ARES-0 and the uplink's drop timer.
 *
 * <p>Code layout: {@link ModItems}, {@link ModEntities}, {@link ModBlocks}, {@link ModDataComponentTypes},
 * {@link ModSounds}, {@link ModDamageTypes}; game logic in {@code logic/}; packets in {@code network/}; everything
 * client-side in {@code client/reliquary/} (registered by the mod's ClientModInitializer).</p>
 */
public final class CosmicReliquary {
    private CosmicReliquary() {}

    public static final String NS = "cosmic_reliquary";

    public static Identifier id(String path) {
        return new Identifier(NS, path);
    }

    /** Called from the mod initialiser, after the existing systems. */
    public static void init() {
        ModSounds.init();
        ModDataComponentTypes.init();
        ModBlocks.init();
        ModItems.init();
        ModEntities.init();
        ReliquaryNet.registerServer();
        StarEaterBeams.register();
        MoltenScars.register();
        OrbitalLink.register();
        Registry.register(Registries.ITEM_GROUP, id("cosmic_reliquary"), FabricItemGroup.builder()
                .icon(() -> new ItemStack(ModItems.STAR_EATER_GAZE))
                .displayName(Text.translatable("itemGroup.cosmic_reliquary.cosmic_reliquary"))
                .entries((ctx, entries) -> {
                    entries.add(ModItems.STAR_EATER_GAZE);
                    entries.add(ModItems.VOID_CARVER_BLADE);
                    entries.add(ModItems.CORE_REVOLVER);
                })
                .build());
    }
}

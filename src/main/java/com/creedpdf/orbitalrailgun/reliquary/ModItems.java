package com.creedpdf.orbitalrailgun.reliquary;

import com.creedpdf.orbitalrailgun.reliquary.item.CoreRevolverItem;
import com.creedpdf.orbitalrailgun.reliquary.item.ReliquaryItem;
import com.creedpdf.orbitalrailgun.reliquary.item.StarEaterGazeItem;
import com.creedpdf.orbitalrailgun.reliquary.item.VoidCarverBladeItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Rarity;

/** The three handheld weapons. Their models are Blender meshes drawn by the client's builtin item renderers. */
public final class ModItems {
    private ModItems() {}

    public static final StarEaterGazeItem STAR_EATER_GAZE = Registry.register(Registries.ITEM, CosmicReliquary.id("star_eater_gaze"),
            new StarEaterGazeItem(settings()));
    public static final VoidCarverBladeItem VOID_CARVER_BLADE = Registry.register(Registries.ITEM, CosmicReliquary.id("void_carver_blade"),
            new VoidCarverBladeItem(settings()));
    public static final CoreRevolverItem CORE_REVOLVER = Registry.register(Registries.ITEM, CosmicReliquary.id("core_revolver"),
            new CoreRevolverItem(settings()));

    private static Item.Settings settings() {
        return new Item.Settings().maxCount(1).rarity(Rarity.EPIC).fireproof();
    }

    public static boolean isReliquary(ItemStack s) {
        return s.getItem() instanceof ReliquaryItem;
    }

    static void init() {}
}

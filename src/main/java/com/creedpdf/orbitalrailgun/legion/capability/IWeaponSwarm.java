package com.creedpdf.orbitalrailgun.legion.capability;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;

import java.util.List;

/**
 * The player's telekinetic swarm: the weapons bound to them (one ItemStack per slot) and whether the swarm is deployed.
 * The slots are the weapons' only home while bound - the flying entities are views of these stacks, rebuilt from them
 * whenever needed, so nothing is ever duplicated or lost when the entities come and go.
 */
public interface IWeaponSwarm {
    /** Storage slots. How many may fly at once is the player's swarm capacity (an attribute, 6 by default). */
    int MAX_SLOTS = 24;

    ItemStack get(int slot);

    void set(int slot, ItemStack stack);

    /** Bound weapons. */
    int count();

    /** Deployed (flying) or stowed (bound but hidden). */
    boolean isActive();

    void setActive(boolean active);

    /** Bind a weapon into the first free slot below {@code limit}; returns the slot, or -1 if full. */
    int bind(ItemStack stack, int limit);

    /** Empty every slot and return what was in them. */
    List<ItemStack> takeAll();

    /** Bumped on every change (drives the client sync). */
    int revision();

    NbtCompound writeNbt();

    void readNbt(NbtCompound nbt);

    void copyFrom(IWeaponSwarm other);
}

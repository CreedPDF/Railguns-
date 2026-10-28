package com.creedpdf.orbitalrailgun.legion.capability;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.collection.DefaultedList;

import java.util.ArrayList;
import java.util.List;

/** The default {@link IWeaponSwarm}: a fixed slot array, saved with the player under "ArsenalLegion". */
public final class WeaponSwarm implements IWeaponSwarm {
    private final DefaultedList<ItemStack> slots = DefaultedList.ofSize(MAX_SLOTS, ItemStack.EMPTY);
    private boolean active = true;
    private int revision;

    @Override
    public ItemStack get(int slot) {
        return slot >= 0 && slot < MAX_SLOTS ? slots.get(slot) : ItemStack.EMPTY;
    }

    @Override
    public void set(int slot, ItemStack stack) {
        if (slot < 0 || slot >= MAX_SLOTS) return;
        slots.set(slot, stack == null ? ItemStack.EMPTY : stack);
        revision++;
    }

    @Override
    public int count() {
        int n = 0;
        for (ItemStack s : slots) if (!s.isEmpty()) n++;
        return n;
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public void setActive(boolean active) {
        if (this.active != active) {
            this.active = active;
            revision++;
        }
    }

    @Override
    public int bind(ItemStack stack, int limit) {
        if (stack.isEmpty()) return -1;
        int bound = count();
        if (bound >= limit) return -1;
        for (int i = 0; i < MAX_SLOTS; i++) {
            if (slots.get(i).isEmpty()) {
                set(i, stack);
                return i;
            }
        }
        return -1;
    }

    @Override
    public List<ItemStack> takeAll() {
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < MAX_SLOTS; i++) {
            if (!slots.get(i).isEmpty()) out.add(slots.get(i));
            slots.set(i, ItemStack.EMPTY);
        }
        revision++;
        return out;
    }

    @Override
    public int revision() {
        return revision;
    }

    @Override
    public NbtCompound writeNbt() {
        NbtCompound nbt = new NbtCompound();
        nbt.putBoolean("Active", active);
        NbtList list = new NbtList();
        for (int i = 0; i < MAX_SLOTS; i++) {
            ItemStack s = slots.get(i);
            if (s.isEmpty()) continue;
            NbtCompound c = s.writeNbt(new NbtCompound());
            c.putByte("Slot", (byte) i);
            list.add(c);
        }
        nbt.put("Items", list);
        return nbt;
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        for (int i = 0; i < MAX_SLOTS; i++) slots.set(i, ItemStack.EMPTY);
        active = !nbt.contains("Active") || nbt.getBoolean("Active");
        NbtList list = nbt.getList("Items", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < list.size(); i++) {
            NbtCompound c = list.getCompound(i);
            int slot = c.getByte("Slot") & 255;
            if (slot < MAX_SLOTS) slots.set(slot, ItemStack.fromNbt(c));
        }
        revision++;
    }

    @Override
    public void copyFrom(IWeaponSwarm other) {
        readNbt(other.writeNbt());
    }
}

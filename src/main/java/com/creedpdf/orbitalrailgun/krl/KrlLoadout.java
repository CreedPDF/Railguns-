package com.creedpdf.orbitalrailgun.krl;

import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * How a kinetic missile is fitted out at the Missile Integration Station: its warhead, the explosive charge packed into
 * it, the motor class and the paint scheme. Stored on the item (NBT "krl"), carried by the launcher and the flight.
 */
public record KrlLoadout(int warhead, int charge, int motor, int livery) {
    public static final int KINETIC = 0, HE = 1, THERMOBARIC = 2, INCENDIARY = 3, BUNKER = 4, CLUSTER = 5;
    public static final String[] WARHEADS = {"KINETIC PENETRATOR", "HIGH EXPLOSIVE", "THERMOBARIC", "INCENDIARY", "BUNKER BUSTER", "CLUSTER"};
    public static final String[] WARHEAD_SHORT = {"KIN", "HE", "TBX", "INC", "BNK", "CLU"};
    public static final String[] WARHEAD_INFO = {
            "Inert tungsten rod. All the damage is the impact itself.",
            "Blast warhead. A heavier charge throws a bigger explosion.",
            "Fuel-air blast: a wider, burning explosion.",
            "Sets everything round the impact on fire.",
            "Hardened nose: buries itself deep before it detonates.",
            "Bursts over the target into a ring of bomblets."};
    public static final String[] MOTORS = {"STANDARD", "BOOSTED", "HIGH-THRUST"};
    public static final int MAX_CHARGE = 5;
    /** Range (blocks) and the Mach number at impact for each motor. */
    public static final double[] RANGE = {2000, 2600, 3200};
    public static final double[] MACH = {13, 16, 20};
    public static final double MAX_RANGE = 3200;

    public static final KrlLoadout DEFAULT = new KrlLoadout(KINETIC, 0, 0, 0);

    public KrlLoadout {
        warhead = MathHelper.clamp(warhead, 0, WARHEADS.length - 1);
        charge = MathHelper.clamp(charge, 0, MAX_CHARGE);
        motor = MathHelper.clamp(motor, 0, MOTORS.length - 1);
        livery = Krl.livery(livery);
        if (warhead == KINETIC) charge = 0;
    }

    public double range() {
        return RANGE[motor];
    }

    public double mach() {
        return MACH[motor];
    }

    /** Apogee scale for the motor (a stronger motor climbs higher). */
    public double heightMul() {
        return 1 + 0.14 * motor;
    }

    /** Plume scale for the motor. */
    public float flameMul() {
        return 1 + 0.28f * motor;
    }

    /** Explosion power (TNT is 4). */
    public float power() {
        return switch (warhead) {
            case HE -> 9f + 2.6f * charge;
            case THERMOBARIC -> 10f + 3f * charge;
            case INCENDIARY -> 5.5f + 1.6f * charge;
            case BUNKER -> 8f + 2.4f * charge;
            case CLUSTER -> 4f + 0.8f * charge;
            default -> 6.5f + 1.5f * motor; // kinetic: the impact alone, harder the faster it hits
        };
    }

    public boolean fire() {
        return warhead == THERMOBARIC || warhead == INCENDIARY;
    }

    public String summary() {
        String c = warhead == KINETIC ? "" : " +" + charge;
        return WARHEAD_SHORT[warhead] + c + "  " + MOTORS[motor];
    }

    // ------------------------------------------------------------------------------------------- storage

    public NbtCompound toNbt() {
        NbtCompound n = new NbtCompound();
        n.putInt("warhead", warhead);
        n.putInt("charge", charge);
        n.putInt("motor", motor);
        n.putInt("livery", livery);
        return n;
    }

    public static KrlLoadout fromNbt(NbtCompound n) {
        if (n == null) return DEFAULT;
        return new KrlLoadout(n.getInt("warhead"), n.getInt("charge"), n.getInt("motor"), n.getInt("livery"));
    }

    public static KrlLoadout of(ItemStack stack) {
        NbtCompound n = stack.getNbt();
        if (n == null) return DEFAULT;
        if (n.contains("krl")) return fromNbt(n.getCompound("krl"));
        // older missiles carried only their livery
        return new KrlLoadout(KINETIC, 0, 0, n.getInt("livery"));
    }

    public ItemStack applyTo(ItemStack stack) {
        NbtCompound n = stack.getOrCreateNbt();
        n.put("krl", toNbt());
        n.putInt("livery", livery);
        return stack;
    }

    public void write(PacketByteBuf b) {
        b.writeByte(warhead);
        b.writeByte(charge);
        b.writeByte(motor);
        b.writeByte(livery);
    }

    public static KrlLoadout read(PacketByteBuf b) {
        return new KrlLoadout(b.readByte(), b.readByte(), b.readByte(), b.readByte());
    }

    // ------------------------------------------------------------------------------------------- refit costs

    /** What it costs to refit this missile to `to` (downgrades are free; the livery is free). */
    public List<ItemStack> costTo(KrlLoadout to) {
        List<ItemStack> c = new ArrayList<>();
        if (to.warhead != warhead) {
            switch (to.warhead) {
                case HE -> c.add(new ItemStack(Items.GUNPOWDER, 4));
                case THERMOBARIC -> {
                    c.add(new ItemStack(Items.GUNPOWDER, 4));
                    c.add(new ItemStack(Items.BLAZE_POWDER, 2));
                }
                case INCENDIARY -> c.add(new ItemStack(Items.FIRE_CHARGE, 4));
                case BUNKER -> c.add(new ItemStack(Items.IRON_BLOCK, 2));
                case CLUSTER -> {
                    c.add(new ItemStack(Items.GUNPOWDER, 6));
                    c.add(new ItemStack(Items.IRON_INGOT, 2));
                }
                default -> {
                }
            }
        }
        int from = to.warhead == warhead ? charge : 0;
        if (to.charge > from) c.add(new ItemStack(Items.TNT, to.charge - from));
        if (to.motor > motor) {
            c.add(new ItemStack(Items.BLAZE_ROD, 2 * (to.motor - motor)));
            c.add(new ItemStack(Items.FIRE_CHARGE, to.motor - motor));
        }
        return merge(c);
    }

    private static List<ItemStack> merge(List<ItemStack> in) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack s : in) {
            boolean done = false;
            for (ItemStack o : out) {
                if (o.isOf(s.getItem())) {
                    o.increment(s.getCount());
                    done = true;
                    break;
                }
            }
            if (!done) out.add(s.copy());
        }
        return out;
    }
}

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
public record KrlLoadout(int warhead, int charge, int motor, int livery, int fuze, int casing) {
    public static final int KINETIC = 0, HE = 1, THERMOBARIC = 2, INCENDIARY = 3, BUNKER = 4, CLUSTER = 5, SHAPED = 6, MIRV = 7, SEISMIC = 8;
    public static final String[] WARHEADS = {"KINETIC PENETRATOR", "HIGH EXPLOSIVE", "THERMOBARIC", "INCENDIARY", "BUNKER BUSTER", "CLUSTER",
            "SHAPED CHARGE", "MIRV", "SEISMIC"};
    public static final String[] WARHEAD_SHORT = {"KIN", "HE", "TBX", "INC", "BNK", "CLU", "SHP", "MRV", "SEI"};
    public static final String[] WARHEAD_INFO = {
            "Inert tungsten rod. All the damage is the impact itself, and it leaves a crater.",
            "Blast warhead. A heavier charge throws a bigger explosion and digs a bigger crater.",
            "Fuel-air blast: the widest explosion, burning and scorching the crater.",
            "Sets everything round the impact on fire and melts the crater to magma.",
            "Hardened nose: buries itself deep before it detonates.",
            "Bursts over the target into a ring of bomblets.",
            "Copper-lined cone: a narrow jet bores a deep shaft straight down.",
            "Splits high up into four re-entry vehicles that strike round the target.",
            "Earth penetrator: a deep blast that collapses a wide, shallow bowl."};
    public static final String[] FUZES = {"IMPACT", "AIRBURST", "DELAYED"};
    public static final String[] FUZE_INFO = {"Detonates on contact.", "Detonates 9 m up: a wider blast, no crater.",
            "Punches 6 m into the ground first: a deeper crater."};
    public static final String[] CASINGS = {"STEEL", "FRAGMENTATION", "TUNGSTEN"};
    public static final String[] CASING_INFO = {"Standard casing.", "Notched casing: a lethal ring of fragments round the blast.",
            "Dense casing: +12% yield and a deeper crater."};
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
        fuze = MathHelper.clamp(fuze, 0, FUZES.length - 1);
        casing = MathHelper.clamp(casing, 0, CASINGS.length - 1);
        if (warhead == KINETIC) charge = 0;
    }

    public KrlLoadout(int warhead, int charge, int motor, int livery) {
        this(warhead, charge, motor, livery, 0, 0);
    }

    public KrlLoadout withLivery(int l) {
        return new KrlLoadout(warhead, charge, motor, l, fuze, casing);
    }

    /** Crater radius (blocks) dug by the main detonation; 0 for none. */
    public float crater() {
        if (fuze == 1) return 0;
        float r = switch (warhead) {
            case CLUSTER, MIRV, SHAPED -> 0;
            case SEISMIC -> power() * 1.25f;
            case INCENDIARY -> power() * 0.8f;
            default -> power() * 0.85f;
        };
        return r;
    }

    /** Vertical stretch of the crater (1 = hemisphere). */
    public float craterDepth() {
        float d = warhead == SEISMIC ? 0.45f : warhead == BUNKER ? 1.3f : 0.8f;
        if (casing == 2) d *= 1.35f;
        if (fuze == 2) d *= 1.25f;
        return d;
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
        float p = switch (warhead) {
            case HE -> 12f + 3.2f * charge;
            case THERMOBARIC -> 13f + 3.6f * charge;
            case INCENDIARY -> 7f + 2f * charge;
            case BUNKER -> 10f + 3f * charge;
            case CLUSTER -> 5f + 1f * charge;
            case SHAPED -> 8f + 2f * charge;
            case MIRV -> 8f + 2.2f * charge;
            case SEISMIC -> 11f + 3f * charge;
            default -> 9f + 2f * motor; // kinetic: the impact alone, harder the faster it hits
        };
        if (casing == 2) p *= 1.12f;
        if (fuze == 1) p *= 1.1f;
        return p;
    }

    public boolean fire() {
        return warhead == THERMOBARIC || warhead == INCENDIARY;
    }

    public String summary() {
        String c = warhead == KINETIC ? "" : " +" + charge;
        String f = fuze == 0 ? "" : fuze == 1 ? " AB" : " DL";
        return WARHEAD_SHORT[warhead] + c + f + "  " + MOTORS[motor];
    }

    // ------------------------------------------------------------------------------------------- storage

    public NbtCompound toNbt() {
        NbtCompound n = new NbtCompound();
        n.putInt("warhead", warhead);
        n.putInt("charge", charge);
        n.putInt("motor", motor);
        n.putInt("livery", livery);
        n.putInt("fuze", fuze);
        n.putInt("casing", casing);
        return n;
    }

    public static KrlLoadout fromNbt(NbtCompound n) {
        if (n == null) return DEFAULT;
        return new KrlLoadout(n.getInt("warhead"), n.getInt("charge"), n.getInt("motor"), n.getInt("livery"), n.getInt("fuze"), n.getInt("casing"));
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
        b.writeByte(fuze);
        b.writeByte(casing);
    }

    public static KrlLoadout read(PacketByteBuf b) {
        return new KrlLoadout(b.readByte(), b.readByte(), b.readByte(), b.readByte(), b.readByte(), b.readByte());
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
                case SHAPED -> {
                    c.add(new ItemStack(Items.COPPER_BLOCK, 2));
                    c.add(new ItemStack(Items.GUNPOWDER, 4));
                }
                case MIRV -> {
                    c.add(new ItemStack(Items.GUNPOWDER, 8));
                    c.add(new ItemStack(Items.DIAMOND, 2));
                }
                case SEISMIC -> {
                    c.add(new ItemStack(Items.OBSIDIAN, 2));
                    c.add(new ItemStack(Items.GUNPOWDER, 6));
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
        if (to.fuze != fuze && to.fuze != 0) {
            c.add(new ItemStack(Items.REDSTONE, 2));
            c.add(new ItemStack(to.fuze == 1 ? Items.COMPARATOR : Items.REPEATER, 1));
        }
        if (to.casing != casing && to.casing != 0) {
            if (to.casing == 1) c.add(new ItemStack(Items.IRON_INGOT, 4));
            else c.add(new ItemStack(Items.NETHERITE_SCRAP, 1));
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

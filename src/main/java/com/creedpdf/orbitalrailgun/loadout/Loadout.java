package com.creedpdf.orbitalrailgun.loadout;

import java.util.ArrayList;
import java.util.List;

/**
 * The railgun's field configuration: one muzzle device, one ammunition type and up to
 * {@link #MAX_MODULES} support modules, chosen on the pod's panel before deployment and delivered
 * from orbital storage when the railgun deploys (see PartDrop).
 *
 * <p>Packed into one int so it syncs as a single TrackedData value:
 * bits 0-3 muzzle (0 = standard bore), bits 4-7 ammunition (0 = ferro slug), bits 8+ one bit per
 * module.</p>
 */
public final class Loadout {

    public enum Category { MUZZLE, AMMO, MODULE }

    /** Every option. {@code bone} is the model bone prefix ("opt_" + id) that shows it. */
    public enum Option {
        // ---- Muzzle devices ----------------------------------------------------------------------
        LANCER(Category.MUZZLE, "lancer", "LANCER LENS ARRAY", 0xFF6FE8FF,
                "Crystal lens stack focuses the shot into a needle that drills far deeper.",
                "BORE DEPTH +60%", "DAMAGE +25%", "BLAST -15%", "BEAM: NEEDLE"),
        TRIPRONG(Category.MUZZLE, "triprong", "TRI-PRONG COMPENSATOR", 0xFFFFD24A,
                "Three field prongs braid lightning around the beam and steady the turret.",
                "TURRET SLEW +120%", "CHARGE -15%", "BEAM: ARC BRAIDS"),
        SHROUD(Category.MUZZLE, "shroud", "PLASMA SHROUD", 0xFFFF6A2A,
                "Flared plasma bell that widens the beam into a searing column.",
                "BEAM WIDTH +60%", "BLAST +30%", "HIT RADIUS +50%", "BORE -30%"),
        SCATTER(Category.MUZZLE, "scatter", "SCATTER CHOKE", 0xFFFFA040,
                "Five-port choke splits the shot: four satellite beams rake the area around the target.",
                "+4 SATELLITE BEAMS", "MAIN BLAST -20%", "AREA SATURATION"),
        GRAVITY(Category.MUZZLE, "gravity", "GRAVITON COIL", 0xFFB070FF,
                "Floating graviton rings drag everything toward the target while the gun charges.",
                "PULL FIELD 20 m", "IMPLOSION ON IMPACT", "BLAST +10%"),
        GHOST(Category.MUZZLE, "ghost", "GHOST BAFFLE", 0xFF9AA4B0,
                "Baffle stack suppressor. Near-silent shot, no muzzle flash, faint beam.",
                "FIRE NOISE -88%", "NO MUZZLE FLASH", "DAMAGE +15%"),

        // ---- Ammunition ----------------------------------------------------------------------------
        TUNGSTEN(Category.AMMO, "tungsten", "TUNGSTEN PENETRATOR", 0xFFB8D8FF,
                "Dense tungsten darts. Narrow, brutal tunnel straight through anything.",
                "BORE DEPTH +120%", "BORE WIDTH -40%", "DAMAGE +35%", "BLAST -30%"),
        THERMITE(Category.AMMO, "thermite", "THERMITE INCENDIARY", 0xFFFF7A1A,
                "Molten thermite slug. Sets the strike zone ablaze and melts stone to magma.",
                "FIRESTORM 14 m", "MAGMA CRATER", "BURN 12 s"),
        CRYO(Category.AMMO, "cryo", "CRYO-STASIS SLUG", 0xFF8FF4FF,
                "Super-cooled payload. Flash-freezes the strike zone and everything in it.",
                "FREEZE FIELD 16 m", "SLOW + FREEZE", "BLAST -40%"),
        ION(Category.AMMO, "ion", "ION DISRUPTOR", 0xFFC060FF,
                "Pure ion discharge. No terrain damage; lightning chains between every target.",
                "NO BLOCK DAMAGE", "DAMAGE +80%", "CHAIN ARCS x8"),
        CLUSTER(Category.AMMO, "cluster", "CLUSTER PAYLOAD", 0xFFFFD040,
                "Splits on impact into sixteen bomblets that rain across the area.",
                "16 BOMBLETS", "SPREAD 20 m", "MAIN BLAST -50%"),
        SINGULARITY(Category.AMMO, "singularity", "SINGULARITY CHARGE", 0xFF7A30FF,
                "Contained micro-singularity. Pulls the world inward, then collapses it.",
                "GRAVITY WELL", "COLLAPSE SPHERE 9 m", "PULLS ENTITIES"),
        SUNBURST(Category.AMMO, "sunburst", "SUNBURST WARHEAD", 0xFFC8FF60,
                "Thermonuclear lance. The largest blast the platform can deliver.",
                "BLAST +60%", "RING BLASTS +40%", "CHARGE +50%", "COOLDOWN x2.5"),

        // ---- Support modules -----------------------------------------------------------------------
        CAPACITOR(Category.MODULE, "capacitor", "CAPACITOR BANK", 0xFFFFB020,
                "Extra capacitor cells on the turret flanks.", "CHARGE TIME -50%"),
        COOLANT(Category.MODULE, "coolant", "CRYO COOLANT LOOP", 0xFF60D8FF,
                "Liquid-nitrogen jacket along the barrel.", "COOLDOWN -65%"),
        OVERCLOCK(Category.MODULE, "overclock", "OVERCLOCK COILS", 0xFFFF4040,
                "Additional accelerator coils driven past their rating.", "DAMAGE +40%", "BLAST +20%", "BEAM +15%"),
        TARGETING(Category.MODULE, "targeting", "TARGETING ARRAY", 0xFFFF3030,
                "Radar dome and laser designator.", "RANGE +60%", "SLEW +50%", "LASER DESIGNATOR"),
        SHIELD(Category.MODULE, "shield", "AEGIS SHIELD EMITTERS", 0xFF50C8FF,
                "Projects a hard-light dome over the emplacement.", "DOME 10 m", "BLOCKS PROJECTILES", "REPELS HOSTILES"),
        SENTRY(Category.MODULE, "sentry", "SENTRY TURRETS", 0xFFFF8040,
                "Two auto-turrets on the bulwarks.", "AUTO-DEFENSE 32 m", "LASER BOLTS"),
        THRUSTER(Category.MODULE, "thruster", "VECTOR THRUSTERS", 0xFF60A0FF,
                "Booster nozzles for the return to orbit.", "LAUNCH SPEED x2.5", "BLAST-OFF SHOCKWAVE"),
        SEISMIC(Category.MODULE, "seismic", "SEISMIC ANCHORS", 0xFFC09060,
                "Pile-driver spikes on the clamps couple every shot into the ground.", "QUAKE RING 28 m", "KNOCK-UP"),
        AUTOLOADER(Category.MODULE, "autoloader", "AUTOLOADER DRUM", 0xFFE0E0E0,
                "Rotary drum re-chambers instantly.", "3-ROUND BURST", "COOLDOWN +30%"),
        AFTERBURN(Category.MODULE, "afterburn", "PLASMA AFTERBURN", 0xFFFF5020,
                "Injects excess plasma into the crater.", "PLASMA POOL 12 s", "DAMAGE OVER TIME");

        public final Category category;
        public final String id;
        public final String title;
        public final int color;
        public final String description;
        public final String[] stats;

        Option(Category category, String id, String title, int color, String description, String... stats) {
            this.category = category;
            this.id = id;
            this.title = title;
            this.color = color;
            this.description = description;
            this.stats = stats;
        }

        public String bone() {
            return "opt_" + id;
        }

        public static List<Option> of(Category c) {
            List<Option> out = new ArrayList<>();
            for (Option o : values()) if (o.category == c) out.add(o);
            return out;
        }
    }

    public static final int MAX_MODULES = 4;
    public static final List<Option> MUZZLES = Option.of(Category.MUZZLE);
    public static final List<Option> AMMO = Option.of(Category.AMMO);
    public static final List<Option> MODULES = Option.of(Category.MODULE);
    public static final Loadout STANDARD = new Loadout(0);

    private final int raw;

    private Loadout(int raw) {
        this.raw = raw;
    }

    public static Loadout of(int raw) {
        Loadout l = new Loadout(raw);
        return l.valid() ? l : STANDARD;
    }

    public int raw() {
        return raw;
    }

    /** Muzzle device, or null for the standard bore. */
    public Option muzzle() {
        int i = raw & 0xF;
        return i == 0 || i > MUZZLES.size() ? null : MUZZLES.get(i - 1);
    }

    /** Ammunition, or null for the standard ferro slug. */
    public Option ammo() {
        int i = (raw >> 4) & 0xF;
        return i == 0 || i > AMMO.size() ? null : AMMO.get(i - 1);
    }

    public List<Option> modules() {
        List<Option> out = new ArrayList<>();
        for (int i = 0; i < MODULES.size(); i++) if ((raw >> (8 + i) & 1) != 0) out.add(MODULES.get(i));
        return out;
    }

    /** No attachments at all: nothing to drop from orbital storage. */
    public boolean isStandard() {
        return raw == 0;
    }

    public boolean has(Option o) {
        return switch (o.category) {
            case MUZZLE -> muzzle() == o;
            case AMMO -> ammo() == o;
            case MODULE -> (raw >> (8 + MODULES.indexOf(o)) & 1) != 0;
        };
    }

    public boolean valid() {
        int m = raw & 0xF, a = (raw >> 4) & 0xF;
        if (m > MUZZLES.size() || a > AMMO.size()) return false;
        if ((raw >>> (8 + MODULES.size())) != 0) return false;
        return Integer.bitCount(raw >>> 8) <= MAX_MODULES;
    }

    /** Selecting a muzzle/ammo replaces the current one (or clears it if already chosen); modules toggle. */
    public Loadout toggle(Option o) {
        switch (o.category) {
            case MUZZLE -> {
                int idx = has(o) ? 0 : MUZZLES.indexOf(o) + 1;
                return new Loadout((raw & ~0xF) | idx);
            }
            case AMMO -> {
                int idx = has(o) ? 0 : AMMO.indexOf(o) + 1;
                return new Loadout((raw & ~0xF0) | (idx << 4));
            }
            default -> {
                int bit = 1 << (8 + MODULES.indexOf(o));
                if ((raw & bit) != 0) return new Loadout(raw & ~bit);
                if (modules().size() >= MAX_MODULES) return this;
                return new Loadout(raw | bit);
            }
        }
    }

    // =========================================================================================
    // Derived stats
    // =========================================================================================

    public int chargeTicks(int base) {
        float m = 1f;
        if (has(Option.TRIPRONG)) m *= 0.85f;
        if (has(Option.CAPACITOR)) m *= 0.5f;
        if (has(Option.SUNBURST)) m *= 1.5f;
        return Math.max(20, Math.round(base * m));
    }

    public int cooldownTicks(int base) {
        float m = 1f;
        if (has(Option.COOLANT)) m *= 0.35f;
        if (has(Option.AUTOLOADER)) m *= 1.3f;
        if (has(Option.SUNBURST)) m *= 2.5f;
        return Math.max(10, Math.round(base * m));
    }

    public float damageMul() {
        float m = 1f;
        if (has(Option.LANCER)) m *= 1.25f;
        if (has(Option.GHOST)) m *= 1.15f;
        if (has(Option.TUNGSTEN)) m *= 1.35f;
        if (has(Option.ION)) m *= 1.8f;
        if (has(Option.OVERCLOCK)) m *= 1.4f;
        return m;
    }

    public float blastMul() {
        float m = 1f;
        if (has(Option.LANCER)) m *= 0.85f;
        if (has(Option.SHROUD)) m *= 1.3f;
        if (has(Option.SCATTER)) m *= 0.8f;
        if (has(Option.GRAVITY)) m *= 1.1f;
        if (has(Option.TUNGSTEN)) m *= 0.7f;
        if (has(Option.THERMITE)) m *= 0.85f;
        if (has(Option.CRYO)) m *= 0.6f;
        if (has(Option.CLUSTER)) m *= 0.5f;
        if (has(Option.SUNBURST)) m *= 1.6f;
        if (has(Option.OVERCLOCK)) m *= 1.2f;
        return m;
    }

    public float ringBlastMul() {
        float m = blastMul();
        if (has(Option.SUNBURST)) m *= 1.4f;
        return m;
    }

    public double boreDepthMul() {
        double m = 1.0;
        if (has(Option.LANCER)) m *= 1.6;
        if (has(Option.SHROUD)) m *= 0.7;
        if (has(Option.TUNGSTEN)) m *= 2.2;
        return m;
    }

    public double boreRadiusMul() {
        double m = 1.0;
        if (has(Option.LANCER)) m *= 0.7;
        if (has(Option.SHROUD)) m *= 1.3;
        if (has(Option.TUNGSTEN)) m *= 0.6;
        return m;
    }

    public double hitRadiusMul() {
        return has(Option.SHROUD) ? 1.5 : has(Option.LANCER) ? 0.7 : 1.0;
    }

    public float beamWidth() {
        float m = 1f;
        if (has(Option.LANCER)) m *= 0.55f;
        if (has(Option.SHROUD)) m *= 1.6f;
        if (has(Option.TUNGSTEN)) m *= 0.75f;
        if (has(Option.SUNBURST)) m *= 1.25f;
        if (has(Option.OVERCLOCK)) m *= 1.15f;
        return m;
    }

    public float slewMul() {
        float m = 1f;
        if (has(Option.TRIPRONG)) m *= 2.2f;
        if (has(Option.TARGETING)) m *= 1.5f;
        return m;
    }

    public double rangeMul() {
        return has(Option.TARGETING) ? 1.6 : 1.0;
    }

    public int burst() {
        return has(Option.AUTOLOADER) ? 3 : 1;
    }

    public float fireVolume() {
        return has(Option.GHOST) ? 0.12f : 1f;
    }

    public boolean breaksBlocks() {
        return !has(Option.ION);
    }

    /**
     * Beam palette: {core, plasma, halo} as 0xRRGGBB. Ammunition sets the colour; the standard
     * ferro slug is the classic red.
     */
    public int[] beamColors() {
        Option a = ammo();
        if (a == null) return new int[]{0xFFF2EE, 0xFF4A38, 0xFF3020};
        return switch (a) {
            case TUNGSTEN -> new int[]{0xF4FAFF, 0x8CC8FF, 0x3C7CFF};
            case THERMITE -> new int[]{0xFFF4D0, 0xFF8A1E, 0xFF4A00};
            case CRYO -> new int[]{0xF0FFFF, 0x7AF0FF, 0x20B8FF};
            case ION -> new int[]{0xFAF0FF, 0xC060FF, 0x7A20FF};
            case CLUSTER -> new int[]{0xFFFBE0, 0xFFD040, 0xFF9000};
            case SINGULARITY -> new int[]{0xE8D8FF, 0x7A30FF, 0x2A0060};
            case SUNBURST -> new int[]{0xFAFFF0, 0xC8FF60, 0x60FF20};
            default -> new int[]{0xFFF2EE, 0xFF4A38, 0xFF3020};
        };
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Loadout l && l.raw == raw;
    }

    @Override
    public int hashCode() {
        return raw;
    }
}

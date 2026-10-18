package com.creedpdf.orbitalrailgun.client.render;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.entity.RailgunEntity;
import com.creedpdf.orbitalrailgun.entity.RailgunState;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.Resource;
import net.minecraft.util.Identifier;
import software.bernie.geckolib.core.animatable.model.CoreGeoBone;
import software.bernie.geckolib.core.animation.AnimationProcessor;

import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Plays the railgun's keyframe animations itself, straight from {@code railgun.animation.json}
 * (exported from Blender), instead of through GeckoLib's animation controllers.
 *
 * <p>Why: the railgun's animation is a pure function of its server-synced state and how long it
 * has been in that state. Evaluating it directly from {@code (state, stateTicks + partialTick)}
 * is deterministic, never depends on controller transitions / snapshots, stays in sync with the
 * server-side particles and sounds, and behaves the same on every GeckoLib version.</p>
 *
 * <p>Semantics match GeckoLib's: keyframe positions are model pixels (rendered with X negated by
 * GeckoLib, exactly as for keyframes), rotations are degrees with X/Y negated, and the
 * {@code easing} on a keyframe shapes the segment that ends at that keyframe.</p>
 */
public final class RailgunAnimator {
    private RailgunAnimator() {}

    private static final Identifier FILE = RailgunMod.id("animations/railgun.animation.json");

    /** name -> animation */
    private static Map<String, Anim> anims;
    /** every bone touched by any animation (reset to rest when the current one doesn't key it) */
    private static Set<String> animatedBones = Set.of();
    private static boolean failed;

    public static void invalidate() {
        anims = null;
        failed = false;
    }

    // ---------------------------------------------------------------------------------------------

    public static void apply(RailgunEntity e, AnimationProcessor<RailgunEntity> processor, float partialTick) {
        if (!ensureLoaded()) return;
        RailgunState state = e.getState();
        float t = (e.getStateTicks() + partialTick) / 20f;
        float age = (e.age + partialTick) / 20f;

        String name;
        float time;
        switch (state) {
            case PANEL_OPEN -> { name = "animation.railgun.panel_open"; time = t; }
            case TRANSFORMING -> { name = "animation.railgun.deploy"; time = t; }
            case PACKING -> { name = "animation.railgun.pack"; time = t; }
            case IDLE, COOLDOWN -> { name = "animation.railgun.idle"; time = age; }
            case CHARGING -> { name = "animation.railgun.charge"; time = age; }
            case FIRING -> {
                Anim fire = anims.get("animation.railgun.fire");
                if (fire != null && t < fire.length) { name = "animation.railgun.fire"; time = t; }
                else { name = "animation.railgun.idle"; time = age; }
            }
            default -> { name = "animation.railgun.pod"; time = 0f; }   // FALLING, POD, POD_READY, LAUNCHING
        }
        Anim anim = anims.get(name);
        if (anim == null) return;
        if (anim.loop) time = time % anim.length;
        else time = Math.min(time, anim.length);

        applyLoadout(e, processor, partialTick);

        for (String boneName : animatedBones) {
            CoreGeoBone bone = processor.getBone(boneName);
            if (bone == null) continue;
            Map<String, Track> chans = anim.bones.get(boneName);
            Track pos = chans == null ? null : chans.get("position");
            Track rot = chans == null ? null : chans.get("rotation");
            Track scl = chans == null ? null : chans.get("scale");

            float[] p = pos != null ? pos.sample(time) : ZERO;
            bone.setPosX(p[0]);
            bone.setPosY(p[1]);
            bone.setPosZ(p[2]);

            float[] s = scl != null ? scl.sample(time) : ONE;
            bone.setScaleX(s[0]);
            bone.setScaleY(s[1]);
            bone.setScaleZ(s[2]);

            if (!"turret".equals(boneName) && !"barrel".equals(boneName)) {
                float[] r = rot != null ? rot.sample(time) : ZERO;
                bone.setRotX((float) Math.toRadians(-r[0]));
                bone.setRotY((float) Math.toRadians(-r[1]));
                bone.setRotZ((float) Math.toRadians(r[2]));
            }
        }
    }

    // ---- Loadout attachments ---------------------------------------------------------------

    /** Per railgun: the loadout last seen and when each option appeared (entity age, ticks). */
    private static final java.util.Map<Integer, int[]> INSTALL = new java.util.HashMap<>();
    private static final int OPTIONS = com.creedpdf.orbitalrailgun.loadout.Loadout.Option.values().length;

    /**
     * Shows the bones of installed options (bone names start with "opt_<id>"), hides the rest,
     * scales newly delivered attachments in, and aims the sentry turret heads.
     */
    private static void applyLoadout(RailgunEntity e, AnimationProcessor<RailgunEntity> processor, float partialTick) {
        com.creedpdf.orbitalrailgun.loadout.Loadout l = e.getLoadout();
        int[] rec = INSTALL.computeIfAbsent(e.getId(), k -> {
            int[] r = new int[OPTIONS + 1];
            r[OPTIONS] = l.raw();
            java.util.Arrays.fill(r, 0, OPTIONS, -10000);
            return r;
        });
        if (rec[OPTIONS] != l.raw()) {
            com.creedpdf.orbitalrailgun.loadout.Loadout old = com.creedpdf.orbitalrailgun.loadout.Loadout.of(rec[OPTIONS]);
            for (var o : com.creedpdf.orbitalrailgun.loadout.Loadout.Option.values()) {
                if (l.has(o) && !old.has(o)) rec[o.ordinal()] = e.age;
            }
            rec[OPTIONS] = l.raw();
        }
        if (INSTALL.size() > 64) INSTALL.keySet().removeIf(id -> e.getWorld().getEntityById(id) == null);

        float mount = mountFactor(e, partialTick, false), mountMuzzle = mountFactor(e, partialTick, true);
        for (CoreGeoBone bone : processor.getRegisteredBones()) {
            String name = bone.getName();
            if (!name.startsWith("opt_")) continue;
            int cut = name.indexOf("__");
            String id = cut < 0 ? name.substring(4) : name.substring(4, cut);
            var opt = OPTION_BY_ID.get(id);
            float f = opt == null || !l.has(opt) ? 0f : opt.category == com.creedpdf.orbitalrailgun.loadout.Loadout.Category.MUZZLE ? mountMuzzle : mount;
            if (f > 0f) {
                // freshly delivered while deployed: grow in as well
                f = Math.min(f, Math.min(1f, (e.age - rec[opt.ordinal()] + partialTick) / 16f));
            }
            boolean on = f > 0.001f;
            bone.setHidden(!on);
            bone.setChildrenHidden(!on);
            if (!on || name.endsWith("_head")) continue;
            float sc = (float) Easing.apply("easeoutback", f);
            bone.setScaleX(sc);
            bone.setScaleY(sc);
            bone.setScaleZ(sc);
        }
        if (l.has(com.creedpdf.orbitalrailgun.loadout.Loadout.Option.SENTRY)) aimSentries(e, processor, partialTick);
    }

    /**
     * Attachments are stored inside the pod and mounted during deployment: they unfold once the
     * turret is up (muzzle devices once the barrel has telescoped out) and fold away first when
     * packing up.
     */
    private static float mountFactor(RailgunEntity e, float pt, boolean muzzle) {
        float t = e.getStateTicks() + pt;
        return switch (e.getState()) {
            case IDLE, CHARGING, FIRING, COOLDOWN -> 1f;
            case TRANSFORMING -> Math.max(0f, Math.min(1f, (t - (muzzle ? 226f : 166f)) / 16f));
            case PACKING -> muzzle ? Math.max(0f, 1f - t / 14f) : Math.max(0f, Math.min(1f, (74f - t) / 16f));
            default -> 0f;
        };
    }

    private static final java.util.Map<String, com.creedpdf.orbitalrailgun.loadout.Loadout.Option> OPTION_BY_ID = new HashMap<>();
    static {
        for (var o : com.creedpdf.orbitalrailgun.loadout.Loadout.Option.values()) OPTION_BY_ID.put(o.id, o);
    }

    private static void aimSentries(RailgunEntity e, AnimationProcessor<RailgunEntity> processor, float partialTick) {
        var mounts = com.creedpdf.orbitalrailgun.loadout.LoadoutEffects.sentryMounts();
        String[] heads = {"opt_sentry__a_head", "opt_sentry__b_head"};
        for (int i = 0; i < 2; i++) {
            CoreGeoBone head = processor.getBone(heads[i]);
            if (head == null) continue;
            net.minecraft.util.math.Vec3d gun = e.getLerpedPos(partialTick).add(mounts[i]);
            net.minecraft.entity.mob.HostileEntity target = e.getWorld().getEntitiesByClass(net.minecraft.entity.mob.HostileEntity.class,
                    new net.minecraft.util.math.Box(gun, gun).expand(32), net.minecraft.entity.Entity::isAlive).stream()
                    .min(java.util.Comparator.comparingDouble(m -> m.squaredDistanceTo(gun))).orElse(null);
            float yaw = target == null ? (e.age + partialTick) * 0.02f * (i == 0 ? 1 : -1)
                    : (float) Math.atan2(target.getX() - gun.x, target.getZ() - gun.z);
            head.setRotY(yaw);
        }
    }

    private static final float[] ZERO = {0f, 0f, 0f};
    private static final float[] ONE = {1f, 1f, 1f};

    // ---------------------------------------------------------------------------------------------
    // Loading
    // ---------------------------------------------------------------------------------------------

    private static boolean ensureLoaded() {
        if (anims != null) return true;
        if (failed) return false;
        try {
            Optional<Resource> res = MinecraftClient.getInstance().getResourceManager().getResource(FILE);
            if (res.isEmpty()) throw new IllegalStateException("missing " + FILE);
            JsonObject root;
            try (Reader reader = res.get().getReader()) {
                root = JsonParser.parseReader(reader).getAsJsonObject();
            }
            Map<String, Anim> out = new HashMap<>();
            Set<String> bones = new HashSet<>();
            JsonObject all = root.getAsJsonObject("animations");
            for (Map.Entry<String, JsonElement> a : all.entrySet()) {
                JsonObject ao = a.getValue().getAsJsonObject();
                Anim anim = new Anim();
                anim.length = ao.get("animation_length").getAsFloat();
                JsonElement loop = ao.get("loop");
                anim.loop = loop != null && loop.isJsonPrimitive() && loop.getAsJsonPrimitive().isBoolean() && loop.getAsBoolean();
                for (Map.Entry<String, JsonElement> b : ao.getAsJsonObject("bones").entrySet()) {
                    Map<String, Track> chans = new HashMap<>();
                    for (Map.Entry<String, JsonElement> c : b.getValue().getAsJsonObject().entrySet()) {
                        chans.put(c.getKey(), Track.parse(c.getValue().getAsJsonObject()));
                    }
                    anim.bones.put(b.getKey(), chans);
                    bones.add(b.getKey());
                }
                out.put(a.getKey(), anim);
            }
            anims = out;
            animatedBones = bones;
            return true;
        } catch (Exception ex) {
            failed = true;
            RailgunMod.LOGGER.error("Could not load railgun animations", ex);
            return false;
        }
    }

    private static final class Anim {
        float length;
        boolean loop;
        final Map<String, Map<String, Track>> bones = new HashMap<>();
    }

    /** One channel: sorted keyframes with per-segment easing. */
    private static final class Track {
        float[] times;
        float[][] values;
        String[] easing;

        static Track parse(JsonObject keys) {
            List<Map.Entry<String, JsonElement>> entries = new ArrayList<>(keys.entrySet());
            entries.sort((x, y) -> Float.compare(Float.parseFloat(x.getKey()), Float.parseFloat(y.getKey())));
            Track tr = new Track();
            int n = entries.size();
            tr.times = new float[n];
            tr.values = new float[n][3];
            tr.easing = new String[n];
            for (int i = 0; i < n; i++) {
                Map.Entry<String, JsonElement> en = entries.get(i);
                tr.times[i] = Float.parseFloat(en.getKey());
                JsonArray vec;
                if (en.getValue().isJsonArray()) {
                    vec = en.getValue().getAsJsonArray();
                } else {
                    JsonObject o = en.getValue().getAsJsonObject();
                    vec = o.getAsJsonArray("vector");
                    if (o.has("easing")) tr.easing[i] = o.get("easing").getAsString().toLowerCase(java.util.Locale.ROOT);
                }
                for (int k = 0; k < 3; k++) tr.values[i][k] = vec.get(k).getAsFloat();
            }
            return tr;
        }

        float[] sample(float t) {
            int n = times.length;
            if (n == 0) return ZERO;
            if (t <= times[0]) return values[0];
            if (t >= times[n - 1]) return values[n - 1];
            int i = 1;
            while (i < n && times[i] < t) i++;
            float t0 = times[i - 1], t1 = times[i];
            float f = t1 > t0 ? (t - t0) / (t1 - t0) : 1f;
            f = (float) Easing.apply(easing[i], f);
            float[] a = values[i - 1], b = values[i];
            return new float[]{a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f, a[2] + (b[2] - a[2]) * f};
        }
    }

    /** The same easing curves GeckoLib uses (in, out, in-out of sine/quad/cubic/quart/quint/expo/back). */
    static final class Easing {
        static double apply(String name, double t) {
            if (name == null || name.equals("linear")) return t;
            String kind;
            int mode; // 0 in, 1 out, 2 in-out
            if (name.startsWith("easeinout")) { mode = 2; kind = name.substring(9); }
            else if (name.startsWith("easein")) { mode = 0; kind = name.substring(6); }
            else if (name.startsWith("easeout")) { mode = 1; kind = name.substring(7); }
            else return t;
            return switch (mode) {
                case 0 -> curve(kind, t);
                case 1 -> 1 - curve(kind, 1 - t);
                default -> t < 0.5 ? curve(kind, t * 2) / 2 : 1 - curve(kind, (1 - t) * 2) / 2;
            };
        }

        private static double curve(String kind, double t) {
            return switch (kind) {
                case "sine" -> 1 - Math.cos(t * Math.PI / 2);
                case "quad" -> t * t;
                case "cubic" -> t * t * t;
                case "quart" -> t * t * t * t;
                case "quint" -> t * t * t * t * t;
                case "expo" -> t <= 0 ? 0 : Math.pow(2, 10 * (t - 1));
                case "circ" -> 1 - Math.sqrt(1 - t * t);
                case "back" -> { double n = 1.70158; yield t * t * ((n + 1) * t - n); }
                default -> t;
            };
        }
    }
}

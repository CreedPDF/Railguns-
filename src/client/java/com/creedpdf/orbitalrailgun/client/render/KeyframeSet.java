package com.creedpdf.orbitalrailgun.client.render;

import com.creedpdf.orbitalrailgun.RailgunMod;
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
 * A Blender-exported .animation.json played procedurally (same semantics as {@link RailgunAnimator}:
 * positions in model px, rotations in degrees with X/Y negated, easing on the segment that ends at
 * a keyframe). Used by the orbital railgun and the orbital depot.
 */
public final class KeyframeSet {
    private final Identifier file;
    private Map<String, Anim> anims;
    private Set<String> bones = Set.of();
    private boolean failed;

    public KeyframeSet(Identifier file) {
        this.file = file;
    }

    public float length(String anim) {
        if (!ensureLoaded()) return 1f;
        Anim a = anims.get(anim);
        return a == null ? 1f : a.length;
    }

    /**
     * Poses every bone the file animates from {@code anim} at {@code time} seconds (looping or
     * clamped as exported). Bones the animation does not key go back to rest.
     */
    public void apply(AnimationProcessor<?> processor, String anim, float time) {
        if (!ensureLoaded()) return;
        Anim a = anims.get(anim);
        if (a == null) return;
        time = a.loop ? (a.length > 0 ? time % a.length : 0) : Math.max(0, Math.min(time, a.length));
        for (String name : bones) {
            CoreGeoBone bone = processor.getBone(name);
            if (bone == null) continue;
            Map<String, Track> ch = a.bones.get(name);
            Track pos = ch == null ? null : ch.get("position");
            Track rot = ch == null ? null : ch.get("rotation");
            Track scl = ch == null ? null : ch.get("scale");
            float[] p = pos != null ? pos.sample(time) : ZERO;
            bone.setPosX(p[0]);
            bone.setPosY(p[1]);
            bone.setPosZ(p[2]);
            float[] s = scl != null ? scl.sample(time) : ONE;
            bone.setScaleX(s[0]);
            bone.setScaleY(s[1]);
            bone.setScaleZ(s[2]);
            float[] r = rot != null ? rot.sample(time) : ZERO;
            bone.setRotX((float) Math.toRadians(-r[0]));
            bone.setRotY((float) Math.toRadians(-r[1]));
            bone.setRotZ((float) Math.toRadians(r[2]));
        }
    }

    // ---------------------------------------------------------------------------------------------

    private static final float[] ZERO = {0f, 0f, 0f};
    private static final float[] ONE = {1f, 1f, 1f};

    private boolean ensureLoaded() {
        if (anims != null) return true;
        if (failed) return false;
        try {
            Optional<Resource> res = MinecraftClient.getInstance().getResourceManager().getResource(file);
            if (res.isEmpty()) throw new IllegalStateException("missing " + file);
            JsonObject root;
            try (Reader reader = res.get().getReader()) {
                root = JsonParser.parseReader(reader).getAsJsonObject();
            }
            Map<String, Anim> out = new HashMap<>();
            Set<String> all = new HashSet<>();
            for (Map.Entry<String, JsonElement> a : root.getAsJsonObject("animations").entrySet()) {
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
                    all.add(b.getKey());
                }
                out.put(a.getKey(), anim);
            }
            anims = out;
            bones = all;
            return true;
        } catch (Exception ex) {
            failed = true;
            RailgunMod.LOGGER.error("Could not load animations " + file, ex);
            return false;
        }
    }

    private static final class Anim {
        float length;
        boolean loop;
        final Map<String, Map<String, Track>> bones = new HashMap<>();
    }

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
            f = (float) RailgunAnimator.Easing.apply(easing[i], f);
            float[] a = values[i - 1], b = values[i];
            return new float[]{a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f, a[2] + (b[2] - a[2]) * f};
        }
    }
}

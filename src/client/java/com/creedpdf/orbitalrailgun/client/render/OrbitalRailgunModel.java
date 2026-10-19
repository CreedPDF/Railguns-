package com.creedpdf.orbitalrailgun.client.render;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.entity.OrbitalRailgunEntity;
import com.creedpdf.orbitalrailgun.loadout.Loadout;
import com.creedpdf.orbitalrailgun.loadout.PartDrop;
import net.minecraft.util.Identifier;
import software.bernie.geckolib.core.animatable.model.CoreGeoBone;
import software.bernie.geckolib.core.animation.AnimationProcessor;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.model.GeoModel;

import java.util.HashMap;
import java.util.Map;

/** The orbital ARES-9: Blender keyframes + attachment visibility / docking poses from the scene. */
public class OrbitalRailgunModel extends GeoModel<OrbitalRailgunEntity> {
    private static final Identifier MODEL = RailgunMod.id("geo/orbital_railgun.geo.json");
    private static final Identifier TEXTURE = RailgunMod.id("textures/entity/orbital_railgun.png");
    private static final Identifier ANIMATION = RailgunMod.id("animations/orbital_railgun.animation.json");
    static final KeyframeSet KEYS = new KeyframeSet(ANIMATION);

    private static final Map<String, Loadout.Option> BY_ID = new HashMap<>();
    static {
        for (Loadout.Option o : Loadout.Option.values()) BY_ID.put(o.id, o);
    }

    @Override public Identifier getModelResource(OrbitalRailgunEntity a) { return MODEL; }
    @Override public Identifier getTextureResource(OrbitalRailgunEntity a) { return TEXTURE; }
    @Override public Identifier getAnimationResource(OrbitalRailgunEntity a) { return ANIMATION; }

    @Override
    public void setCustomAnimations(OrbitalRailgunEntity e, long instanceId, AnimationState<OrbitalRailgunEntity> state) {
        super.setCustomAnimations(e, instanceId, state);
        AnimationProcessor<OrbitalRailgunEntity> proc = getAnimationProcessor();
        String anim = "animation.orbital." + e.anim;
        KEYS.apply(proc, anim, e.animTime);
        // looping layers on top of the deployed pose
        if (e.anim.equals("idle") || e.anim.equals("charge")) {
            KEYS.apply(proc, "animation.orbital.deploy", 99f);
            KEYS.apply(proc, anim, e.clock);
        }
        if (e.anim.equals("fire")) {
            KEYS.apply(proc, "animation.orbital.deploy", 99f);
            KEYS.apply(proc, anim, e.animTime);
        }
        boolean deployed = !e.anim.equals("stowed") && !e.anim.equals("stow")
                && !(e.anim.equals("deploy") && e.animTime < 8.6f);
        Loadout l = Loadout.of(e.loadout);
        for (CoreGeoBone bone : proc.getRegisteredBones()) {
            String name = bone.getName();
            if (!name.startsWith("opt_")) continue;
            int cut = name.indexOf("__");
            String id = cut < 0 ? name.substring(4) : name.substring(4, cut);
            Loadout.Option opt = BY_ID.get(id);
            boolean installed = opt != null && l.has(opt);
            if (name.endsWith("__arms")) {
                PartDrop.Pose parent = e.partPoses.get(name.substring(0, name.length() - 6));
                float a = installed && parent != null && parent.visible() ? parent.arms() : 0f;
                boolean on = a > 0.01f;
                bone.setHidden(!on);
                bone.setChildrenHidden(!on);
                float sc = on ? (float) RailgunAnimator.Easing.apply("easeoutback", a) : 0.01f;
                bone.setScaleX(sc);
                bone.setScaleY(sc);
                bone.setScaleZ(sc);
                continue;
            }
            if (name.endsWith("_head")) {
                bone.setHidden(!installed);
                continue;
            }
            PartDrop.Pose pose = e.partPoses.get(name);
            boolean on;
            if (pose != null) {
                on = installed && pose.visible();
                bone.setPosX(pose.x());
                bone.setPosY(pose.y());
                bone.setPosZ(pose.z());
                bone.setRotX(pose.rotX());
                bone.setRotY(pose.rotY());
                bone.setRotZ(pose.rotZ());
            } else {
                on = installed && deployed && !e.hideUnposedParts;
                bone.setPosX(0);
                bone.setPosY(0);
                bone.setPosZ(0);
                bone.setRotX(0);
                bone.setRotY(0);
                bone.setRotZ(0);
            }
            bone.setScaleX(1);
            bone.setScaleY(1);
            bone.setScaleZ(1);
            bone.setHidden(!on);
            bone.setChildrenHidden(!on);
        }
    }
}

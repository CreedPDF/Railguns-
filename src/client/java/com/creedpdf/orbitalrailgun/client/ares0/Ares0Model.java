package com.creedpdf.orbitalrailgun.client.ares0;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.entity.Ares0Entity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import software.bernie.geckolib.core.animatable.model.CoreGeoBone;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.model.GeoModel;

/**
 * ARES-0 (Blender-built GeckoLib model), posed procedurally: six stabiliser legs fold up for the
 * drop and slam down on landing, the turret yaws and the 31-block tri-rail accelerator pitches
 * toward its target while nine capacitor rings spin up.
 */
public class Ares0Model extends GeoModel<Ares0Entity> {
    private static final Identifier MODEL = RailgunMod.id("geo/ares0.geo.json");
    private static final Identifier TEXTURE = RailgunMod.id("textures/entity/ares0.png");
    private static final Identifier ANIMATION = RailgunMod.id("animations/ares0.animation.json");
    public static final int RINGS = 9;

    @Override
    public Identifier getModelResource(Ares0Entity animatable) {
        return MODEL;
    }

    @Override
    public Identifier getTextureResource(Ares0Entity animatable) {
        return TEXTURE;
    }

    @Override
    public Identifier getAnimationResource(Ares0Entity animatable) {
        return ANIMATION;
    }

    @Override
    public void setCustomAnimations(Ares0Entity e, long instanceId, AnimationState<Ares0Entity> state) {
        super.setCustomAnimations(e, instanceId, state);
        float pt = state.getPartialTick();
        float t = e.getStateTicks() + pt;
        Ares0Entity.State s = e.getState();

        float fold = switch (s) {
            case FALLING -> 1f;
            case LANDING -> 1f;
            case DEPLOYING -> 1f - smooth((t - 20f) / 50f);
            case LEAVING -> smooth((t - 10f) / 40f);
            default -> 0f;
        };
        // slam overshoot right as the feet hit
        float slam = s == Ares0Entity.State.DEPLOYING && t > 66 && t < 82 ? -0.06f * MathHelper.sin((t - 66) / 16f * MathHelper.PI) : 0f;
        for (int i = 0; i < 6; i++) {
            CoreGeoBone mount = bone("mount_" + i);
            if (mount != null) mount.setRotY(i * MathHelper.PI / 3f);
            CoreGeoBone leg = bone("leg_" + i);
            if (leg != null) leg.setRotX(1.75f * fold + slam);
        }
        CoreGeoBone yaw = bone("yaw");
        if (yaw != null) yaw.setRotY((float) Math.toRadians(e.getAimYaw(pt)));
        CoreGeoBone pitch = bone("pitch");
        if (pitch != null) pitch.setRotX((float) Math.toRadians(e.getAimPitch(pt)));

        float time = e.age + pt;
        float spinRate = switch (s) {
            case CHARGING -> 0.05f + 0.5f * Math.min(1f, t / Ares0Entity.CHARGE_TICKS);
            case FIRING -> 0.6f;
            case COOLDOWN -> 0.6f * Math.max(0f, 1f - t / 120f) + 0.02f;
            case IDLE -> 0.02f;
            default -> 0f;
        };
        for (int i = 0; i < RINGS; i++) {
            CoreGeoBone ring = bone("ring_" + i);
            if (ring == null) continue;
            float dir = i % 2 == 0 ? 1f : -1f;
            ring.setRotZ(dir * (time * spinRate + i * 0.4f));
        }
        CoreGeoBone halo = bone("halo");
        if (halo != null) halo.setRotZ(-time * (0.04f + spinRate));
        // sensor mast rises after deploy
        CoreGeoBone mast = bone("mast");
        if (mast != null) {
            float up = switch (s) {
                case DEPLOYING -> smooth((t - 120f) / 50f);
                case FALLING, LANDING -> 0f;
                case LEAVING -> 1f - smooth(t / 30f);
                default -> 1f;
            };
            mast.setPosY(-30f * (1f - up));
        }
        CoreGeoBone dish = bone("dish");
        if (dish != null) dish.setRotY(time * 0.03f);
    }

    private CoreGeoBone bone(String name) {
        return getAnimationProcessor().getBone(name);
    }

    private static float smooth(float f) {
        f = MathHelper.clamp(f, 0f, 1f);
        return f * f * (3f - 2f * f);
    }
}

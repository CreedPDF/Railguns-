package com.creedpdf.orbitalrailgun.client.render;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.entity.RailgunEntity;
import net.minecraft.util.Identifier;
import software.bernie.geckolib.core.animatable.model.CoreGeoBone;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.model.GeoModel;

/**
 * GeckoLib model binding + procedural turret aiming.
 *
 * <p>Your Blender/Blockbench export must contain bones named {@code turret} (yaw pivot, at
 * {@code RailgunConfig.PIVOT_Y} blocks above the origin) and {@code barrel} (child of turret,
 * pitch pivot, barrel modelled pointing toward -Z / "north" in Blockbench). Everything else is
 * free-form and driven by the .animation.json.</p>
 */
public class RailgunModel extends GeoModel<RailgunEntity> {
    private static final Identifier MODEL = RailgunMod.id("geo/railgun.geo.json");
    private static final Identifier TEXTURE = RailgunMod.id("textures/entity/railgun.png");
    private static final Identifier ANIMATION = RailgunMod.id("animations/railgun.animation.json");

    /**
     * Flip these to -1 if a custom export ends up aiming backwards or upside down. The beam itself
     * is always computed from the entity's aim math, so only the visible barrel is affected.
     */
    private static final float YAW_SIGN = 1f;
    private static final float PITCH_SIGN = 1f;

    @Override
    public Identifier getModelResource(RailgunEntity animatable) {
        return MODEL;
    }

    @Override
    public Identifier getTextureResource(RailgunEntity animatable) {
        return TEXTURE;
    }

    @Override
    public Identifier getAnimationResource(RailgunEntity animatable) {
        return ANIMATION;
    }

    /**
     * Runs after keyframe animations each frame, so procedural aim overrides any keyframed
     * rotation on these two bones while every other bone (legs, coils, panels) stays animated.
     */
    @Override
    public void setCustomAnimations(RailgunEntity animatable, long instanceId, AnimationState<RailgunEntity> animationState) {
        super.setCustomAnimations(animatable, instanceId, animationState);
        // Always applied: the entity decides the aim per state (rail straight up while packed).

        float partialTick = animationState.getPartialTick();
        RailgunAnimator.apply(animatable, getAnimationProcessor(), partialTick);
        CoreGeoBone turret = getAnimationProcessor().getBone("turret");
        CoreGeoBone barrel = getAnimationProcessor().getBone("barrel");
        if (turret != null) turret.setRotY(YAW_SIGN * animatable.getAimYawRad(partialTick));
        if (barrel != null) barrel.setRotX(PITCH_SIGN * animatable.getAimPitchRad(partialTick));
        // the old control panel on the pod is gone: everything is done from the uplink tablet
        CoreGeoBone panel = getAnimationProcessor().getBone("pod_panel");
        if (panel != null) panel.setHidden(true);
    }
}

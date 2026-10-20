package com.creedpdf.orbitalrailgun.client.ship;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.entity.ShipEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import software.bernie.geckolib.core.animatable.model.CoreGeoBone;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.model.GeoModel;

/** The starship (Blender-built GeckoLib model), posed in code: heading, pitch, bank, gear and hatch. */
public class ShipModel extends GeoModel<ShipEntity> {
    private static final Identifier MODEL = RailgunMod.id("geo/ship.geo.json");
    private static final Identifier TEXTURE = RailgunMod.id("textures/entity/ship.png");
    private static final Identifier ANIMATION = RailgunMod.id("animations/ship.animation.json");
    /** Hatch swing from closed to resting on the ground (matches the Blender preview). */
    private static final float RAMP_OPEN = (float) Math.toRadians(123.5);

    @Override
    public Identifier getModelResource(ShipEntity e) {
        return MODEL;
    }

    @Override
    public Identifier getTextureResource(ShipEntity e) {
        return TEXTURE;
    }

    @Override
    public Identifier getAnimationResource(ShipEntity e) {
        return ANIMATION;
    }

    @Override
    public void setCustomAnimations(ShipEntity e, long instanceId, AnimationState<ShipEntity> state) {
        super.setCustomAnimations(e, instanceId, state);
        float pt = state.getPartialTick();
        float t = e.getPhaseTicks() + pt;
        CoreGeoBone root = bone("ship_root");
        if (root != null) {
            Vec3d h = ShipCinematic.noseDirection(e, pt);
            double horiz = Math.sqrt(h.x * h.x + h.z * h.z);
            float yaw = (float) Math.atan2(-h.x, -h.z);
            float pitch = (float) Math.atan2(h.y, horiz);
            root.setRotY(yaw);
            root.setRotX(-pitch);
            // engine rumble on the pad
            float th = e.thrust(t);
            if (e.getPhase() == ShipEntity.Phase.IGNITION || (e.getPhase() == ShipEntity.Phase.ASCENT && t < 60)) {
                float time = e.age + pt;
                root.setPosX(0.15f * th * MathHelper.sin(time * 2.7f));
                root.setPosZ(0.15f * th * MathHelper.sin(time * 3.3f + 1f));
            } else {
                root.setPosX(0f);
                root.setPosZ(0f);
            }
        }
        float fold = e.legFold(t);
        CoreGeoBone front = bone("ship_leg_front");
        if (front != null) front.setRotX(MathHelper.HALF_PI * fold);
        for (String n : new String[]{"ship_leg_left", "ship_leg_right"}) {
            CoreGeoBone leg = bone(n);
            if (leg != null) leg.setRotX(-MathHelper.HALF_PI * fold);
        }
        CoreGeoBone ramp = bone("ship_ramp");
        if (ramp != null) ramp.setRotX(-RAMP_OPEN * e.rampOpen(t));
    }

    private CoreGeoBone bone(String name) {
        return getAnimationProcessor().getBone(name);
    }
}

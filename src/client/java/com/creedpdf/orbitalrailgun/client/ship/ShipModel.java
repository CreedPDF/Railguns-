package com.creedpdf.orbitalrailgun.client.ship;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.entity.ShipEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import software.bernie.geckolib.core.animatable.model.CoreGeoBone;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.model.GeoModel;

/**
 * The starship (Blender-built GeckoLib model), posed in code from the entity's flight model:
 * yaw and pitch on the root, roll on the bank bone, VTOL nacelles tilting nozzle-down to hover,
 * tripod gear folding, rear hatch dropping into a ramp, engine rumble on the pad.
 */
public class ShipModel extends GeoModel<ShipEntity> {
    private static final Identifier MODEL = RailgunMod.id("geo/ship.geo.json");
    private static final Identifier TEXTURE = RailgunMod.id("textures/entity/ship.png");
    private static final Identifier ANIMATION = RailgunMod.id("animations/ship.animation.json");
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
        float[] pose = e.pose(t);
        CoreGeoBone root = bone("ship_root");
        if (root != null) {
            root.setRotY(pose[0]);
            root.setRotX(-pose[1]);
            float th = e.thrust(t);
            boolean pad = e.getPhase() == ShipEntity.Phase.IGNITION || (e.getPhase() == ShipEntity.Phase.ASCENT && t < 50)
                    || e.getPhase() == ShipEntity.Phase.TOUCHDOWN || (e.getPhase() == ShipEntity.Phase.DEPARTING && t < 50);
            float time = e.age + pt;
            float rumble = pad ? 0.12f * th : 0f;
            root.setPosX(rumble * MathHelper.sin(time * 2.7f));
            root.setPosZ(rumble * MathHelper.sin(time * 3.3f + 1f));
        }
        CoreGeoBone bank = bone("ship_bank");
        if (bank != null) bank.setRotZ(pose[2]);
        float vt = e.vtol(t);
        for (String n : new String[]{"ship_eng_l", "ship_eng_r"}) {
            CoreGeoBone eng = bone(n);
            if (eng != null) eng.setRotX(-MathHelper.HALF_PI * vt);
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

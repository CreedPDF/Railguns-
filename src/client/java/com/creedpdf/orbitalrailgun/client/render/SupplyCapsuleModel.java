package com.creedpdf.orbitalrailgun.client.render;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.entity.SupplyCapsuleEntity;
import com.creedpdf.orbitalrailgun.entity.SupplyCapsuleEntity.Phase;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import software.bernie.geckolib.core.animatable.model.CoreGeoBone;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.model.GeoModel;

/**
 * Supply capsule model. Petals, landing legs and the cargo cradle are posed procedurally from
 * the capsule's phase (see {@link SupplyCapsuleEntity}).
 *
 * <p>Sign conventions (GeckoLib renders model X mirrored): a petal on model +X opens with a
 * positive Z rotation, -X with negative; +Z opens with positive X rotation, -Z with negative.</p>
 */
public class SupplyCapsuleModel extends GeoModel<SupplyCapsuleEntity> {
    private static final Identifier MODEL = RailgunMod.id("geo/supply_capsule.geo.json");
    private static final Identifier TEXTURE = RailgunMod.id("textures/entity/supply_capsule.png");
    private static final Identifier ANIMATION = RailgunMod.id("animations/supply_capsule.animation.json");
    private static final float OPEN = (float) Math.toRadians(108);

    @Override public Identifier getModelResource(SupplyCapsuleEntity e) { return MODEL; }
    @Override public Identifier getTextureResource(SupplyCapsuleEntity e) { return TEXTURE; }
    @Override public Identifier getAnimationResource(SupplyCapsuleEntity e) { return ANIMATION; }

    @Override
    public void setCustomAnimations(SupplyCapsuleEntity e, long instanceId, AnimationState<SupplyCapsuleEntity> state) {
        float pt = state.getPartialTick();
        Phase ph = e.getPhase();
        float t = e.getPhaseTicks() + pt;

        float open = switch (ph) {
            case OPEN -> ease(t / Phase.OPEN.ticks);
            case TRANSFER -> 1f;
            case CLOSE -> 1f - ease(t / (Phase.CLOSE.ticks * 0.8f));
            default -> 0f;
        };
        // Overshoot a touch as the petals slam open, like heavy doors on dampers.
        float a = OPEN * open + (ph == Phase.OPEN ? (float) Math.sin(MathHelper.clamp(t / Phase.OPEN.ticks, 0, 1) * Math.PI) * 0.08f : 0f);
        rot(e, "petal_px", 0, 0, a);
        rot(e, "petal_nx", 0, 0, -a);
        rot(e, "petal_pz", a, 0, 0);
        rot(e, "petal_nz", -a, 0, 0);

        float legs = switch (ph) {
            case DESCENT -> e.getY() - e.prevY > 2.0 ? 1f : 0f;   // folded while free-falling
            case ASCENT -> Math.min(1f, t / 15f);
            default -> 0f;
        };
        for (int i = 0; i < 4; i++) {
            CoreGeoBone leg = getAnimationProcessor().getBone("leg_" + i);
            if (leg != null) leg.setPosY(7f * legs);
        }

        float lift = switch (ph) {
            case TRANSFER -> ease(Math.min(1f, t / 12f));
            case CLOSE -> 1f - ease(Math.min(1f, t / 10f));
            default -> 0f;
        };
        CoreGeoBone cargo = getAnimationProcessor().getBone("cargo");
        if (cargo != null) {
            cargo.setPosY(12f * lift);
            cargo.setRotY(ph == Phase.TRANSFER ? t * 0.08f : 0f);
        }
    }

    private void rot(SupplyCapsuleEntity e, String bone, float x, float y, float z) {
        CoreGeoBone b = getAnimationProcessor().getBone(bone);
        if (b == null) return;
        b.setRotX(x);
        b.setRotY(y);
        b.setRotZ(z);
    }

    private static float ease(float f) {
        f = MathHelper.clamp(f, 0f, 1f);
        return f * f * (3f - 2f * f);
    }
}

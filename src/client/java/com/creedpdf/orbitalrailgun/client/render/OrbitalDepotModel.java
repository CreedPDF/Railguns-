package com.creedpdf.orbitalrailgun.client.render;

import com.creedpdf.orbitalrailgun.RailgunMod;
import com.creedpdf.orbitalrailgun.entity.OrbitalDepotEntity;
import com.creedpdf.orbitalrailgun.entity.OrbitalDepotEntity.DState;
import com.creedpdf.orbitalrailgun.loadout.Loadout;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import software.bernie.geckolib.core.animatable.model.CoreGeoBone;
import software.bernie.geckolib.core.animation.AnimationProcessor;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.model.GeoModel;

/** The orbital depot: hood lift, spinning cabinet, and the shelf parts (selected ones pop out). */
public class OrbitalDepotModel extends GeoModel<OrbitalDepotEntity> {
    private static final Identifier MODEL = RailgunMod.id("geo/orbital_depot.geo.json");
    private static final Identifier TEXTURE = RailgunMod.id("textures/entity/orbital_depot.png");
    private static final Identifier ANIMATION = RailgunMod.id("animations/orbital_depot.animation.json");
    private static final KeyframeSet KEYS = new KeyframeSet(ANIMATION);

    @Override public Identifier getModelResource(OrbitalDepotEntity a) { return MODEL; }
    @Override public Identifier getTextureResource(OrbitalDepotEntity a) { return TEXTURE; }
    @Override public Identifier getAnimationResource(OrbitalDepotEntity a) { return ANIMATION; }

    @Override
    public void setCustomAnimations(OrbitalDepotEntity e, long instanceId, AnimationState<OrbitalDepotEntity> state) {
        super.setCustomAnimations(e, instanceId, state);
        AnimationProcessor<OrbitalDepotEntity> proc = getAnimationProcessor();
        float pt = state.getPartialTick();
        float t = (e.getStateTicks() + pt) / 20f;
        float age = (e.age + pt) / 20f;
        DState s = e.getDState();
        switch (s) {
            case OPENING -> KEYS.apply(proc, "animation.depot.open", t);
            case OPEN, SENDING -> {
                KEYS.apply(proc, "animation.depot.open", 99f);
                KEYS.apply(proc, "animation.depot.idle", age);
                CoreGeoBone cab = proc.getBone("cabinet");
                if (cab != null) cab.setRotY(0f);
            }
            case CLOSING -> KEYS.apply(proc, "animation.depot.close", t);
            default -> KEYS.apply(proc, "animation.depot.closed", 0f);
        }

        Loadout draft = e.getDraft(), sent = e.getSent();
        float lt = e.getStateTicks() + pt - 6;
        for (Loadout.Option o : Loadout.Option.values()) {
            CoreGeoBone b = proc.getBone("mini_" + o.id);
            if (b == null) continue;
            int i = o.ordinal();
            float[] out = OrbitalDepotEntity.slotOut(i);
            boolean gone = (s == DState.CLOSING || s == DState.LAUNCHING) && sent.has(o)
                    || (s == DState.SENDING && sent.has(o) && lt > 50);
            b.setHidden(gone);
            b.setChildrenHidden(gone);
            float px = 0, py = 0, pz = 0, sc = 1f, ry = 0f;
            if (s == DState.OPEN && draft.has(o)) {
                float bob = MathHelper.sin(age * 4f + i) * 0.6f;
                px = out[0] * 5f;
                pz = out[2] * 5f;
                py = 1.5f + bob;
                sc = 1.12f;
                ry = age * 1.2f;
            } else if (s == DState.SENDING && sent.has(o)) {
                px = out[0] * 5f;
                pz = out[2] * 5f;
                py = 1.5f + OrbitalDepotEntity.lift(lt);
                ry = Math.max(0, lt) * 0.25f;
                sc = 1.12f;
            }
            b.setPosX(px);
            b.setPosY(py);
            b.setPosZ(pz);
            b.setRotY(ry);
            b.setScaleX(sc);
            b.setScaleY(sc);
            b.setScaleZ(sc);
        }
    }
}

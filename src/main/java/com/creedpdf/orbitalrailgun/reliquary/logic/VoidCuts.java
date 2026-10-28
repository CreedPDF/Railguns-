package com.creedpdf.orbitalrailgun.reliquary.logic;

import com.creedpdf.orbitalrailgun.reliquary.ModDamageTypes;
import com.creedpdf.orbitalrailgun.reliquary.ModEntities;
import com.creedpdf.orbitalrailgun.reliquary.ModItems;
import com.creedpdf.orbitalrailgun.reliquary.ModSounds;
import com.creedpdf.orbitalrailgun.reliquary.entity.VoidRiftEntity;
import com.creedpdf.orbitalrailgun.reliquary.item.VoidCarverBladeItem;
import com.creedpdf.orbitalrailgun.reliquary.network.ReliquaryNet;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * A Void-Carver swing. The cut is a plane through the eye spanned by the look direction and the player's right
 * (normal = the view's "up"). An entity is cut when its bounding box straddles that plane (its eight corners are not
 * all on one side) and its centre, projected into the plane, lies within {@link VoidCarverBladeItem#REACH} blocks and
 * {@link VoidCarverBladeItem#ARC} degrees of the look. Cut entities take {@link VoidCarverBladeItem#CUT_DAMAGE}, are
 * marked, and a {@link VoidRiftEntity} opens at the swing.
 */
public final class VoidCuts {
    private VoidCuts() {}

    public static void swing(ServerPlayerEntity p) {
        if (!p.getMainHandStack().isOf(ModItems.VOID_CARVER_BLADE) || p.getItemCooldownManager().isCoolingDown(ModItems.VOID_CARVER_BLADE)) return;
        p.getItemCooldownManager().set(ModItems.VOID_CARVER_BLADE, VoidCarverBladeItem.COOLDOWN);
        p.swingHand(Hand.MAIN_HAND, true);
        ServerWorld w = p.getServerWorld();
        Vec3d eye = p.getEyePos(), f = p.getRotationVec(1f);
        Vec3d r = f.crossProduct(new Vec3d(0, 1, 0));
        if (r.lengthSquared() < 1e-4) r = Vec3d.fromPolar(0, p.getYaw() + 90);
        r = r.normalize();
        Vec3d n = r.crossProduct(f).normalize();
        double reach = VoidCarverBladeItem.REACH, cosArc = Math.cos(Math.toRadians(VoidCarverBladeItem.ARC));
        List<Entity> cut = new ArrayList<>();
        for (Entity e : w.getOtherEntities(p, new Box(eye, eye).expand(reach + 1), x -> x instanceof LivingEntity && x.isAlive())) {
            Box b = e.getBoundingBox();
            if (!straddles(b, eye, n)) continue;
            Vec3d c = b.getCenter().subtract(eye);
            Vec3d inPlane = c.subtract(n.multiply(c.dotProduct(n)));
            double d = inPlane.length();
            if (d > reach + b.getXLength() * 0.5) continue;
            if (d > 0.3 && inPlane.multiply(1 / d).dotProduct(f) < cosArc) continue;
            ModDamageTypes.hurt(w, (LivingEntity) e, ModDamageTypes.VOID_CUT, p, p, VoidCarverBladeItem.CUT_DAMAGE);
            cut.add(e);
        }
        w.playSound(null, p.getX(), p.getEyeY(), p.getZ(), ModSounds.VOID_SLASH, SoundCategory.PLAYERS, 1.4f, 0.9f + w.random.nextFloat() * 0.2f);
        ReliquaryNet.broadcastSlash(p);
        VoidRiftEntity rift = new VoidRiftEntity(ModEntities.VOID_RIFT, w);
        Vec3d at = eye.add(f.multiply(2.2)).subtract(0, 0.9, 0);
        rift.setPosition(at);
        rift.setup(p, cut);
        w.spawnEntity(rift);
    }

    /** Are the box's corners on both sides of the plane through {@code o} with normal {@code n}? */
    static boolean straddles(Box b, Vec3d o, Vec3d n) {
        boolean pos = false, neg = false;
        for (int i = 0; i < 8; i++) {
            double x = (i & 1) == 0 ? b.minX : b.maxX, y = (i & 2) == 0 ? b.minY : b.maxY, z = (i & 4) == 0 ? b.minZ : b.maxZ;
            double s = (x - o.x) * n.x + (y - o.y) * n.y + (z - o.z) * n.z;
            if (s >= 0) pos = true;
            if (s <= 0) neg = true;
            if (pos && neg) return true;
        }
        return false;
    }
}

package com.creedpdf.orbitalrailgun.client.legion.render;

import com.creedpdf.orbitalrailgun.legion.SwarmPaths;
import com.creedpdf.orbitalrailgun.legion.entity.AutonomousWeaponEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ShieldItem;
import net.minecraft.item.ToolItem;
import net.minecraft.item.ToolMaterials;
import net.minecraft.item.TridentItem;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Where a flying weapon is drawn this frame, and the colour of its energy.
 * <ul>
 *   <li>In formation or a storm the pose is evaluated at the exact frame time from the owner's interpolated position
 *   and yaw - so the swarm moves with a walking or sprinting player perfectly smoothly, with no tick-rate stepping
 *   and no lag behind them.</li>
 *   <li>Striking or parrying, it is the entity's own interpolated position, aimed along its motion.</li>
 * </ul>
 */
public final class LegionVisual {
    private LegionVisual() {}

    public static SwarmPaths.Pose pose(AutonomousWeaponEntity e, float pt) {
        PlayerEntity o = e.owner();
        if (e.state().analytic() && o != null) {
            Vec3d feet = o.getLerpedPos(pt);
            float body = MathHelper.lerpAngleDegrees(pt, o.prevBodyYaw, o.bodyYaw);
            float head = MathHelper.lerpAngleDegrees(pt, o.prevHeadYaw, o.headYaw);
            return e.pathPose(o, feet, body, head, e.getWorld().getTime() + pt);
        }
        Vec3d p = e.getLerpedPos(pt);
        Vec3d m = e.getPos().subtract(e.prevX, e.prevY, e.prevZ);
        return new SwarmPaths.Pose(p, m.lengthSquared() > 1e-4 ? m.normalize() : e.renderDir);
    }

    /** The energy colour of a weapon, by what it is made of (warm tones; enchanted blades run hotter, toward rose). */
    public static float[] color(ItemStack s) {
        float[] c;
        if (s.getItem() instanceof ToolItem t) {
            if (t.getMaterial() == ToolMaterials.NETHERITE) c = new float[]{1f, 0.1f, 0.05f};
            else if (t.getMaterial() == ToolMaterials.DIAMOND) c = new float[]{1f, 0.92f, 0.78f};
            else if (t.getMaterial() == ToolMaterials.GOLD) c = new float[]{1f, 0.72f, 0.18f};
            else if (t.getMaterial() == ToolMaterials.IRON) c = new float[]{1f, 0.86f, 0.7f};
            else c = new float[]{1f, 0.48f, 0.16f};
        } else if (s.getItem() instanceof TridentItem) c = new float[]{0.55f, 1f, 0.72f};
        else if (s.getItem() instanceof ShieldItem) c = new float[]{1f, 0.62f, 0.12f};
        else c = new float[]{1f, 0.4f, 0.12f};
        if (s.hasEnchantments()) {
            c[1] = c[1] * 0.6f + 0.1f;
            c[2] = c[2] * 0.6f + 0.3f;
        }
        return c;
    }
}

package com.creedpdf.orbitalrailgun.client.render;

import com.creedpdf.orbitalrailgun.entity.RailgunEntity;
import com.creedpdf.orbitalrailgun.entity.RailgunState;
import com.creedpdf.orbitalrailgun.loadout.Loadout;
import com.creedpdf.orbitalrailgun.util.RailgunConfig;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Draws every firing railgun beam in a world-render pass, independent of entity tracking and
 * render distance: the server broadcasts each shot to the whole dimension, so a beam is visible
 * from anywhere (2000+ blocks). Fog is lifted for the pass and the projection far plane is pushed
 * out while a beam is live (see GameRendererMixin), so the beam is never clipped or fogged away.
 */
public final class RemoteBeams {
    private RemoteBeams() {}

    private record Shot(int entityId, Vec3d muzzle, BlockPos target, int loadout, long start) {}

    private static final List<Shot> SHOTS = new ArrayList<>();
    private static final VertexConsumerProvider.Immediate BUFFER = VertexConsumerProvider.immediate(new BufferBuilder(1 << 21));
    private static boolean active;

    public static void register() {
        WorldRenderEvents.AFTER_TRANSLUCENT.register(RemoteBeams::render);
    }

    /** True while any beam is on screen this frame (the far plane is extended then). */
    public static boolean active() {
        return active;
    }

    public static void add(ClientWorld world, int entityId, Vec3d muzzle, BlockPos target, int loadout) {
        SHOTS.removeIf(s -> s.entityId == entityId);
        SHOTS.add(new Shot(entityId, muzzle, target, loadout, world.getTime()));
    }

    private static void render(WorldRenderContext ctx) {
        ClientWorld world = ctx.world();
        if (world == null) {
            active = false;
            return;
        }
        float td = ctx.tickDelta();
        long now = world.getTime();
        Vec3d cam = ctx.camera().getPos();
        MatrixStack matrices = ctx.matrixStack();
        float time = now + td;
        Set<Integer> drawn = new HashSet<>();
        boolean any = false;

        for (Entity e : world.getEntities()) {
            if (!(e instanceof RailgunEntity rg) || rg.getState() != RailgunState.FIRING || !rg.hasTarget()) continue;
            drawn.add(rg.getId());
            any |= draw(world, matrices, cam, rg.getMuzzlePos(td), rg.getTarget(), rg.getLoadout(), rg.getStateTicks() + td, time);
        }
        SHOTS.removeIf(s -> now < s.start || now - s.start > RailgunConfig.FIRE_TICKS + 2);
        for (Shot s : SHOTS) {
            if (drawn.contains(s.entityId)) continue;
            any |= draw(world, matrices, cam, s.muzzle, s.target, Loadout.of(s.loadout), now - s.start + td, time);
        }
        active = any;
        if (!any) return;

        float fogStart = RenderSystem.getShaderFogStart();
        float fogEnd = RenderSystem.getShaderFogEnd();
        RenderSystem.setShaderFogStart(Float.MAX_VALUE * 0.5f);
        RenderSystem.setShaderFogEnd(Float.MAX_VALUE);
        BUFFER.draw();
        RenderSystem.setShaderFogStart(fogStart);
        RenderSystem.setShaderFogEnd(fogEnd);
    }

    private static boolean draw(ClientWorld world, MatrixStack matrices, Vec3d cam, Vec3d muzzle, BlockPos target,
                                Loadout l, float firingTicks, float time) {
        if (firingTicks > RailgunConfig.FIRE_TICKS) return false;
        matrices.push();
        matrices.translate(muzzle.x - cam.x, muzzle.y - cam.y, muzzle.z - cam.z);
        BeamRenderer.renderShot(BUFFER, matrices, world, muzzle, muzzle, target, l, firingTicks, time);
        matrices.pop();
        return true;
    }
}

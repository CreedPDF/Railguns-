package com.creedpdf.orbitalrailgun.client.legion;

import com.creedpdf.orbitalrailgun.client.legion.hud.SwarmHud;
import com.creedpdf.orbitalrailgun.client.legion.network.LegionClientPackets;
import com.creedpdf.orbitalrailgun.client.legion.particle.KineticGazeParticle;
import com.creedpdf.orbitalrailgun.client.legion.render.AutonomousWeaponRenderer;
import com.creedpdf.orbitalrailgun.client.legion.render.LegionAltarRenderer;
import com.creedpdf.orbitalrailgun.client.legion.sound.SwarmHumSoundInstance;
import com.creedpdf.orbitalrailgun.legion.ArsenalLegion;
import com.creedpdf.orbitalrailgun.legion.entity.AutonomousWeaponEntity;
import com.creedpdf.orbitalrailgun.legion.entity.SwarmState;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.particle.v1.ParticleFactoryRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactories;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

/**
 * Client side of Arsenal Legion: renderers, the kinetic_gaze particle, the swarm hum, the stow / deploy key (G) and
 * the HUD read-out.
 */
public final class LegionClient {
    private LegionClient() {}

    private static KeyBinding toggle;
    private static SwarmHumSoundInstance hum;
    private static int ticks;

    public static void register() {
        EntityRendererRegistry.register(ArsenalLegion.WEAPON, AutonomousWeaponRenderer::new);
        BlockEntityRendererFactories.register(ArsenalLegion.ALTAR_BE, LegionAltarRenderer::new);
        ParticleFactoryRegistry.getInstance().register(ArsenalLegion.KINETIC_GAZE, KineticGazeParticle.Factory::new);
        LegionClientPackets.register();
        toggle = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.arsenal_legion.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G,
                "key.categories.arsenal_legion"));
        ClientTickEvents.END_CLIENT_TICK.register(LegionClient::tick);
        HudRenderCallback.EVENT.register(SwarmHud::render);
        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> {
            SwarmHud.clear();
            hum = null;
        });
    }

    private static void tick(MinecraftClient mc) {
        while (toggle.wasPressed()) if (mc.player != null) LegionClientPackets.sendToggle();
        if (mc.player == null || mc.world == null || ++ticks % 10 != 0) return;
        // the hum follows the number of your own weapons flying round you
        int mine = mc.world.getEntitiesByClass(AutonomousWeaponEntity.class, mc.player.getBoundingBox().expand(16),
                e -> mc.player.getUuid().equals(e.ownerUuid())).size();
        if (mine > 0 && (hum == null || hum.isDone())) {
            hum = new SwarmHumSoundInstance(mc.player);
            mc.getSoundManager().play(hum);
        }
        if (hum != null) hum.setWeapons(mine);
    }

    /** A weapon's trail, every client tick: a faint distortion while orbiting, a bright streak while attacking. */
    public static void trail(AutonomousWeaponEntity e) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.options.getParticles().getValue().getId() == 2) return;   // particles: minimal
        Vec3d m = e.getPos().subtract(e.prevX, e.prevY, e.prevZ);
        double speed = m.length();
        Vec3d tail = e.getPos().add(0, 0.3, 0);
        if (e.state() == SwarmState.ORBIT && speed < 0.9) {
            if ((e.age + e.getId()) % 3 == 0) {
                Vec3d back = speed > 1e-3 ? tail.subtract(m.normalize().multiply(0.5)) : tail;
                mc.world.addParticle(ArsenalLegion.KINETIC_GAZE, back.x, back.y, back.z, 0, 0, 0);
            }
        } else if (speed > 1e-3) {
            Vec3d d = m.normalize().multiply(Math.max(1.0, speed * 2.2));
            mc.world.addParticle(ArsenalLegion.KINETIC_GAZE, true, tail.x, tail.y, tail.z, d.x, d.y, d.z);
        }
    }
}

package com.creedpdf.orbitalrailgun.client.config;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;

import java.util.Locale;

/** {@code /orbitalquality [low|medium|high|ultra] [adaptive|fixed]}: client-side graphics setting. */
public final class QualityCommand {
    private QualityCommand() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> dispatcher.register(
                ClientCommandManager.literal("orbitalquality")
                        .executes(c -> report(c.getSource()))
                        .then(ClientCommandManager.argument("level", StringArgumentType.word())
                                .suggests((c, sb) -> {
                                    for (Quality.Level l : Quality.Level.values()) sb.suggest(l.name().toLowerCase(Locale.ROOT));
                                    return sb.buildFuture();
                                })
                                .executes(c -> apply(c.getSource(), StringArgumentType.getString(c, "level"), Quality.adaptive()))
                                .then(ClientCommandManager.argument("mode", StringArgumentType.word())
                                        .suggests((c, sb) -> sb.suggest("adaptive").suggest("fixed").buildFuture())
                                        .executes(c -> apply(c.getSource(), StringArgumentType.getString(c, "level"),
                                                !"fixed".equalsIgnoreCase(StringArgumentType.getString(c, "mode"))))))));
    }

    private static int apply(FabricClientCommandSource src, String name, boolean adaptive) {
        try {
            Quality.set(Quality.Level.valueOf(name.toUpperCase(Locale.ROOT)), adaptive);
        } catch (IllegalArgumentException e) {
            src.sendError(Text.literal("low, medium, high or ultra"));
            return 0;
        }
        return report(src);
    }

    private static int report(FabricClientCommandSource src) {
        src.sendFeedback(Text.translatable("command.orbital_railgun.quality", Quality.chosen().name(), Quality.adaptive() ? "on" : "off", Quality.level().name()));
        return 1;
    }
}

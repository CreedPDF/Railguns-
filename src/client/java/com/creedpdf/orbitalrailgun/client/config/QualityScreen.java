package com.creedpdf.orbitalrailgun.client.config;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/** Cloth Config screen for the graphics settings. */
public final class QualityScreen {
    private QualityScreen() {}

    public static Screen create(Screen parent) {
        ConfigBuilder b = ConfigBuilder.create().setParentScreen(parent).setTitle(Text.translatable("config.orbital_railgun.title"));
        ConfigEntryBuilder eb = b.entryBuilder();
        ConfigCategory cat = b.getOrCreateCategory(Text.translatable("config.orbital_railgun.graphics"));
        Quality.Level[] level = {Quality.chosen()};
        boolean[] adaptive = {Quality.adaptive()};
        cat.addEntry(eb.startEnumSelector(Text.translatable("config.orbital_railgun.quality"), Quality.Level.class, Quality.chosen())
                .setDefaultValue(Quality.Level.HIGH)
                .setEnumNameProvider(e -> Text.translatable("config.orbital_railgun.quality." + e.name().toLowerCase(java.util.Locale.ROOT)))
                .setTooltip(Text.translatable("config.orbital_railgun.quality.tooltip"))
                .setSaveConsumer(v -> level[0] = (Quality.Level) v)
                .build());
        cat.addEntry(eb.startBooleanToggle(Text.translatable("config.orbital_railgun.adaptive"), Quality.adaptive())
                .setDefaultValue(true)
                .setTooltip(Text.translatable("config.orbital_railgun.adaptive.tooltip"))
                .setSaveConsumer(v -> adaptive[0] = v)
                .build());
        b.setSavingRunnable(() -> Quality.set(level[0], adaptive[0]));
        return b.build();
    }
}

package com.example;

import com.example.NowPlayingConfig.Side;
import me.shedaniel.autoconfig.gui.registry.api.GuiProvider;
import me.shedaniel.autoconfig.gui.registry.api.GuiRegistryAccess;
import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.text.Text;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;

public class CustomSideGuiProvider implements GuiProvider {

    @Override
    public List<AbstractConfigListEntry> get(String i18n, Field field, Object config, Object defaults,
                                            GuiRegistryAccess registry) {

        // Make sure we can read/write the field even if it's private
        if (!field.canAccess(config)) {
            field.setAccessible(true);
        }

        // Safety: only handle Side fields 
        if (!Side.class.equals(field.getType())) {
            return Collections.emptyList();
        }

        final ConfigEntryBuilder entryBuilder = ConfigEntryBuilder.create();

        try {
            final Side current = (Side) field.get(config);

            return Collections.singletonList(
                    entryBuilder.startEnumSelector(
                                    Text.translatable(i18n),
                                    Side.class,
                                    current != null ? current : Side.LEFT // fallback
                            )
                            .setDefaultValue(() -> {
                                try {
                                    Side def = (Side) field.get(defaults);
                                    return def != null ? def : Side.LEFT;
                                } catch (IllegalAccessException e) {
                                    return Side.LEFT;
                                }
                            })
                            .setSaveConsumer(newValue -> {
                                try {
                                    field.set(config, newValue);
                                } catch (IllegalAccessException e) {
                                    // Keep it quiet-ish; printing stack traces every click is annoying
                                    System.err.println("[NowPlayingMod] Failed to set Side config: " + e.getMessage());
                                }
                            })
                            .setTooltip(Text.translatable(i18n + ".tooltip"))
                            .build()
            );

        } catch (IllegalAccessException e) {
            System.err.println("[NowPlayingMod] Failed to read Side config: " + e.getMessage());
            return Collections.emptyList();
        }
    }
}

package com.naocraftlab.skins.compat.config;

import com.naocraftlab.skins.client.FilePicker;
import com.naocraftlab.skins.runtime.ConfigurationUseCases;
import com.naocraftlab.skins.runtime.ServerConfigurationAccess;
import net.minecraft.client.gui.screens.Screen;

@FunctionalInterface
public interface ConfigurationScreenFactory {
    Screen create(
            Screen parent,
            ConfigurationUseCases service,
            FilePicker filePicker,
            ServerConfigurationAccess serverAccess);
}

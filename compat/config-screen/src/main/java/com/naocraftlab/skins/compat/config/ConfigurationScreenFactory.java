package com.naocraftlab.skins.compat.config;

import com.naocraftlab.skins.client.FilePicker;
import com.naocraftlab.skins.runtime.ClientConfigurationService;
import com.naocraftlab.skins.runtime.ServerConfigurationAccess;
import net.minecraft.client.gui.screens.Screen;

@FunctionalInterface
public interface ConfigurationScreenFactory {
    Screen create(
            Screen parent,
            ClientConfigurationService service,
            FilePicker filePicker,
            ServerConfigurationAccess serverAccess);
}

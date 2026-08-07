package com.naocraftlab.skins.compat.config;

import com.naocraftlab.skins.runtime.ClientConfigurationDraft;
import dev.isxander.yacl3.api.ButtonOption;
import dev.isxander.yacl3.api.Controller;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.utils.Dimension;
import dev.isxander.yacl3.gui.AbstractWidget;
import dev.isxander.yacl3.gui.YACLScreen;
import dev.isxander.yacl3.gui.controllers.ActionController;
import java.nio.file.Path;
import java.util.Objects;
import net.minecraft.network.chat.Component;


final class FolderPickerController implements Controller<String> {
    private final Option<String> option;
    private final Path defaultDirectory;
    private final ClientConfigurationDraft draft;

    FolderPickerController(
            Option<String> option,
            Path defaultDirectory,
            ClientConfigurationDraft draft) {
        this.option = Objects.requireNonNull(option, "option");
        this.defaultDirectory = Objects.requireNonNull(
                defaultDirectory, "defaultDirectory").toAbsolutePath().normalize();
        this.draft = Objects.requireNonNull(draft, "draft");
    }

    @Override
    public Option<String> option() {
        return option;
    }

    @Override
    public Component formatValue() {
        String directory = option.pendingValue();
        return directory.isEmpty()
                ? Component.translatable(
                        "nclskins.config.client.storage.data_directory.default")
                : Component.literal(ClientConfigurationDraft.abbreviatedDataDirectory(directory));
    }

    @Override
    public AbstractWidget provideWidget(
            YACLScreen screen,
            Dimension<Integer> widgetDimension) {
        ButtonOption action = ButtonOption.createBuilder()
                .name(option.name())
                .description(option.description())
                .text(formatValue())
                .available(option.available())
                .action((ignoredScreen, ignoredOption) -> draft.selectDataDirectory(
                                defaultDirectory,
                                option.pendingValue())
                        .thenAccept(selected -> selected.ifPresent(option::requestSet)))
                .build();
        ActionController controller = new ActionController(action, formatValue()) {
            @Override
            public Component formatValue() {
                return FolderPickerController.this.formatValue();
            }
        };
        return new ActionController.ActionControllerElement(
                controller,
                screen,
                widgetDimension) {
            @Override
            public boolean canReset() {
                return true;
            }
        };
    }
}

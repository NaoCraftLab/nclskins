package com.naocraftlab.skins.compat.config;

import com.naocraftlab.skins.client.FilePicker;
import com.naocraftlab.skins.core.config.ClientConfiguration;
import com.naocraftlab.skins.core.config.ServerConfiguration;
import com.naocraftlab.skins.core.storage.NclSkinsStorage;
import com.naocraftlab.skins.runtime.ClientConfigurationDraft;
import com.naocraftlab.skins.runtime.ClientConfigurationService;
import com.naocraftlab.skins.runtime.ConfigurationActionRunner;
import com.naocraftlab.skins.runtime.ServerConfigurationAccess;
import dev.isxander.yacl3.api.ButtonOption;
import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.OptionFlag;
import dev.isxander.yacl3.api.OptionGroup;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.DoubleFieldControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerFieldControllerBuilder;
import dev.isxander.yacl3.api.controller.TickBoxControllerBuilder;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;


public final class YaclConfigurationScreenFactory {
    private YaclConfigurationScreenFactory() {}

    public static Screen create(
            Screen parent,
            ClientConfigurationService service,
            FilePicker nativeFileDialog,
            ServerConfigurationAccess serverAccess) {
        Objects.requireNonNull(parent, "parent");
        ClientConfigurationService checkedService = Objects.requireNonNull(service, "service");
        ServerConfigurationAccess checkedServerAccess = Objects.requireNonNull(
                serverAccess, "serverAccess");
        ClientConfigurationDraft clientDraft = new ClientConfigurationDraft(
                checkedService.client(),
                Objects.requireNonNull(nativeFileDialog, "nativeFileDialog"));
        AtomicReference<ServerConfiguration> serverDraft = checkedServerAccess.visible()
                ? new AtomicReference<>(checkedService.loadServer())
                : null;

        YetAnotherConfigLib.Builder builder = YetAnotherConfigLib.createBuilder()
                .title(Component.translatable("nclskins.config.title"))
                .category(clientCategory(clientDraft))
                .save(() -> {
                    checkedService.saveClient(clientDraft.value());
                    if (serverDraft != null) {
                        checkedService.saveServer(serverDraft.get());
                    }
        });
        if (serverDraft != null) {
            builder.category(serverCategory(
                    serverDraft,
                    checkedServerAccess.restartRequired()));
        }
        return builder.build().generateScreen(parent);
    }

    public static ButtonOption actionOption(
            String nameKey,
            String descriptionKey,
            ConfigurationActionRunner runner) {
        Objects.requireNonNull(runner, "runner");
        return ButtonOption.createBuilder()
                .name(Component.translatable(Objects.requireNonNull(nameKey, "nameKey")))
                .description(description(descriptionKey))
                .action((screen, option) -> runner.run())
                .build();
    }

    private static ConfigCategory clientCategory(
            ClientConfigurationDraft draft) {
        ClientConfiguration defaults = ClientConfiguration.defaults();
        Option<Boolean> titleScreen = Option.<Boolean>createBuilder()
                .name(Component.translatable(
                        "nclskins.config.client.menu_preview.title_screen.name"))
                .description(description(
                        "nclskins.config.client.menu_preview.title_screen.description"))
                .binding(
                        defaults.menuPreview().titleScreen(),
                        () -> draft.value().menuPreview().titleScreen(),
                        draft::setTitleScreenPreview)
                .controller(TickBoxControllerBuilder::create)
                .build();
        Option<Boolean> pauseMenu = Option.<Boolean>createBuilder()
                .name(Component.translatable(
                        "nclskins.config.client.menu_preview.pause_menu.name"))
                .description(description(
                        "nclskins.config.client.menu_preview.pause_menu.description"))
                .binding(
                        defaults.menuPreview().pauseMenu(),
                        () -> draft.value().menuPreview().pauseMenu(),
                        draft::setPauseMenuPreview)
                .controller(TickBoxControllerBuilder::create)
                .build();
        Option<String> dataDirectory = Option.<String>createBuilder()
                .name(Component.translatable(
                        "nclskins.config.client.storage.data_directory.name"))
                .description(description(
                        "nclskins.config.client.storage.data_directory.description"))
                .binding(
                        defaults.storage().dataDirectory(),
                        () -> draft.value().storage().dataDirectory(),
                        draft::setDataDirectory)
                .customController(option -> new FolderPickerController(
                        option,
                        NclSkinsStorage.defaultRoot(),
                        draft))
                .flag(OptionFlag.GAME_RESTART)
                .build();

        return ConfigCategory.createBuilder()
                .name(Component.translatable("nclskins.config.category.client"))
                .group(OptionGroup.createBuilder()
                        .name(Component.translatable("nclskins.config.group.menu_preview"))
                        .option(titleScreen)
                        .option(pauseMenu)
                        .build())
                .group(OptionGroup.createBuilder()
                        .name(Component.translatable("nclskins.config.group.storage"))
                        .option(dataDirectory)
                        .build())
                .build();
    }

    private static ConfigCategory serverCategory(
            AtomicReference<ServerConfiguration> draft,
            boolean restartRequired) {
        Option<Boolean> enabled = restartWhenServerRunning(Option.<Boolean>createBuilder()
                .name(Component.translatable(
                        "nclskins.config.server.realtime_refresh.enabled.name"))
                .description(description(
                        "nclskins.config.server.realtime_refresh.enabled.description"))
                .binding(
                        ServerConfiguration.defaults().realtimeRefresh().enabled(),
                        () -> draft.get().realtimeRefresh().enabled(),
                        value -> draft.updateAndGet(current ->
                                current.withRealtimeRefreshEnabled(value)))
                .controller(TickBoxControllerBuilder::create), restartRequired).build();
        Option<Boolean> trustedProxy = restartWhenServerRunning(Option.<Boolean>createBuilder()
                .name(Component.translatable(
                        "nclskins.config.server.realtime_refresh.trusted_proxy_forwarding.name"))
                .description(description(
                        "nclskins.config.server.realtime_refresh.trusted_proxy_forwarding.description"))
                .binding(
                        ServerConfiguration.defaults().realtimeRefresh().trustedProxyForwarding(),
                        () -> draft.get().realtimeRefresh().trustedProxyForwarding(),
                        value -> draft.updateAndGet(current ->
                                current.withTrustedProxyForwarding(value)))
                .controller(TickBoxControllerBuilder::create), restartRequired).build();
        Option<Integer> maxConcurrent = restartWhenServerRunning(Option.<Integer>createBuilder()
                .name(Component.translatable(
                        "nclskins.config.server.realtime_refresh.max_concurrent_lookups.name"))
                .description(description(
                        "nclskins.config.server.realtime_refresh.max_concurrent_lookups.description"))
                .binding(
                        ServerConfiguration.defaults().realtimeRefresh().maxConcurrentLookups(),
                        () -> draft.get().realtimeRefresh().maxConcurrentLookups(),
                        value -> draft.updateAndGet(current ->
                                current.withMaxConcurrentLookups(value)))
                .controller(option -> IntegerFieldControllerBuilder.create(option)
                        .range(1, Integer.MAX_VALUE)), restartRequired).build();
        Option<Double> lookupRate = restartWhenServerRunning(Option.<Double>createBuilder()
                .name(Component.translatable(
                        "nclskins.config.server.realtime_refresh.lookup_rate_per_second.name"))
                .description(description(
                        "nclskins.config.server.realtime_refresh.lookup_rate_per_second.description"))
                .binding(
                        ServerConfiguration.defaults().realtimeRefresh().lookupRatePerSecond(),
                        () -> draft.get().realtimeRefresh().lookupRatePerSecond(),
                        value -> draft.updateAndGet(current ->
                                current.withLookupRatePerSecond(value)))
                .controller(option -> DoubleFieldControllerBuilder.create(option)
                        .range(Double.MIN_VALUE, Double.MAX_VALUE)), restartRequired).build();
        Option<Integer> lookupBurst = restartWhenServerRunning(Option.<Integer>createBuilder()
                .name(Component.translatable(
                        "nclskins.config.server.realtime_refresh.lookup_burst.name"))
                .description(description(
                        "nclskins.config.server.realtime_refresh.lookup_burst.description"))
                .binding(
                        ServerConfiguration.defaults().realtimeRefresh().lookupBurst(),
                        () -> draft.get().realtimeRefresh().lookupBurst(),
                        value -> draft.updateAndGet(current ->
                                current.withLookupBurst(value)))
                .controller(option -> IntegerFieldControllerBuilder.create(option)
                        .range(1, Integer.MAX_VALUE)), restartRequired).build();
        OptionGroup.Builder group = OptionGroup.createBuilder()
                .name(Component.translatable("nclskins.config.group.realtime_refresh"));
        group.option(enabled)
                .option(trustedProxy)
                .option(maxConcurrent)
                .option(lookupRate)
                .option(lookupBurst);
        return ConfigCategory.createBuilder()
                .name(Component.translatable("nclskins.config.category.server"))
                .group(group.build())
                .build();
    }

    private static <T> Option.Builder<T> restartWhenServerRunning(
            Option.Builder<T> builder,
            boolean restartRequired) {
        if (restartRequired) {
            builder.flag(OptionFlag.GAME_RESTART);
        }
        return builder;
    }

    private static OptionDescription description(String key) {
        return OptionDescription.of(Component.translatable(
                Objects.requireNonNull(key, "descriptionKey")));
    }

}

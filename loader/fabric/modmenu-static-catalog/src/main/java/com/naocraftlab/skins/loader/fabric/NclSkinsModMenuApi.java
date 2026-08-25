package com.naocraftlab.skins.loader.fabric;

import com.naocraftlab.skins.compat.config.MinecraftConfigurationBridge;
import com.naocraftlab.skins.generated.TargetClientBindings;
import com.naocraftlab.skins.runtime.update.UpdateCandidate;
import com.naocraftlab.skins.runtime.update.UpdateCatalogClient;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import com.terraformersmc.modmenu.api.UpdateChecker;
import com.terraformersmc.modmenu.api.UpdateInfo;
import java.util.Objects;
import java.util.function.Supplier;
import net.fabricmc.loader.api.FabricLoader;


public final class NclSkinsModMenuApi implements ModMenuApi {
    private static final String MOD_ID = "nclskins";

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return MinecraftConfigurationBridge::createScreen;
    }

    @Override
    public UpdateChecker getUpdateChecker() {
        return checker(
                UpdateCatalogClient.create(),
                NclSkinsModMenuApi::currentVersion,
                TargetClientBindings.TARGET_ID,
                com.terraformersmc.modmenu.api.UpdateChannel::getUserPreference);
    }

    static UpdateChecker checker(
            UpdateCatalogClient client,
            Supplier<String> currentVersion,
            String targetId,
            Supplier<com.terraformersmc.modmenu.api.UpdateChannel> preference) {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(currentVersion, "currentVersion");
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(preference, "preference");
        return () -> client.check(
                        targetId,
                        currentVersion.get(),
                        com.naocraftlab.skins.runtime.update.UpdateChannel.valueOf(
                                preference.get().name()))
                .<UpdateInfo>map(ModMenuUpdateInfo::new)
                .orElse(null);
    }

    private static String currentVersion() {
        return FabricLoader.getInstance()
                .getModContainer(MOD_ID)
                .orElseThrow()
                .getMetadata()
                .getVersion()
                .getFriendlyString();
    }

    private record ModMenuUpdateInfo(UpdateCandidate candidate) implements UpdateInfo {
        private ModMenuUpdateInfo {
            Objects.requireNonNull(candidate, "candidate");
        }

        @Override
        public boolean isUpdateAvailable() {
            return true;
        }

        @Override
        public String getDownloadLink() {
            return candidate.url().toString();
        }

        @Override
        public com.terraformersmc.modmenu.api.UpdateChannel getUpdateChannel() {
            return com.terraformersmc.modmenu.api.UpdateChannel.valueOf(
                    candidate.channel().name());
        }
    }
}

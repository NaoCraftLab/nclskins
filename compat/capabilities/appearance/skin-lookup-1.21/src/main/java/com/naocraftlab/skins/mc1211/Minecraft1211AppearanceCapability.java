package com.naocraftlab.skins.mc1211;

import com.naocraftlab.skins.client.ExpectedAppearance;
import com.naocraftlab.skins.client.CurrentPlayerAppearanceSource;
import com.naocraftlab.skins.client.PlayerAppearanceSink;
import com.naocraftlab.skins.client.SignedProfileResolver.ResolvedProfile;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.client.TextureRegistry.TextureKind;
import com.naocraftlab.skins.client.TextureRegistry.TextureHandle;
import com.naocraftlab.skins.runtime.AcknowledgedAppearanceAssets;
import com.naocraftlab.skins.runtime.AcknowledgedAppearanceAssets.Asset;
import com.naocraftlab.skins.runtime.AppearanceOverrideController;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;


final class Minecraft1211AppearanceCapability
        implements PlayerAppearanceSink<AcknowledgedAppearanceAssets>, AutoCloseable {
    private final MinecraftTextureRegistry textures =
            new MinecraftTextureRegistry("live/appearance");
    private final AppearanceOverrideController<InstalledOverride, TextureHandle> overrides =
            new AppearanceOverrideController<>(new AppearanceOverrideController.Strategy<>() {
                @Override
                public ExpectedAppearance expected(InstalledOverride installed) {
                    return installed.expected();
                }

                @Override
                public List<TextureHandle> handles(InstalledOverride installed) {
                    List<TextureHandle> handles = new ArrayList<>(2);
                    if (installed.skinHandle() != null) {
                        handles.add(installed.skinHandle());
                    }
                    if (installed.capeHandle() != null) {
                        handles.add(installed.capeHandle());
                    }
                    return handles;
                }

                @Override
                public ApplyResult attach(InstalledOverride installed) {
                    return attachToCurrentPlayer(Minecraft.getInstance(), installed);
                }

                @Override
                public void restore() {
                    restoreVanilla(Minecraft.getInstance());
                }

                @Override
                public void release(TextureHandle handle) {
                    textures.release(handle);
                }
            });

    @Override
    public ApplyResult apply(ResolvedProfile<AcknowledgedAppearanceAssets> resolvedProfile) {
        Objects.requireNonNull(resolvedProfile, "resolvedProfile");
        Minecraft minecraft = Minecraft.getInstance();
        checkClientThread(minecraft);
        UUID profileId = resolvedProfile.profileId();
        if (!minecraft.getUser().getProfileId().equals(profileId)) {
            return ApplyResult.DEFERRED;
        }

        ExpectedAppearance expected = resolvedProfile.expectedAppearance();
        Optional<ApplyResult> reattached = overrides.reattachIfActive(expected);
        if (reattached.isPresent()) {
            return reattached.orElseThrow();
        }

        AcknowledgedAppearanceAssets payload = resolvedProfile.platformProfile();
        TextureHandle skinHandle = null;
        TextureHandle capeHandle = null;
        try {
            PlayerSkin fallback = DefaultPlayerSkin.get(profileId);
            ResourceLocation body = fallback.texture();
            PlayerSkin.Model model = fallback.model();
            if (payload.skin().isPresent()) {
                Asset skin = payload.skin().orElseThrow();
                skinHandle = textures.register(
                        TextureKind.PLAYER_SKIN,
                        skin.sha256(),
                        skin.path());
                body = location(skinHandle);
                model = expected.skinModel().orElseThrow() == SkinModel.SLIM
                        ? PlayerSkin.Model.SLIM
                        : PlayerSkin.Model.WIDE;
            }

            ResourceLocation cape = null;
            if (payload.cape().isPresent()) {
                Asset resolvedCape = payload.cape().orElseThrow();
                capeHandle = textures.register(
                        TextureKind.IMAGE, resolvedCape.sha256(), resolvedCape.path());
                cape = location(capeHandle);
            }

            PlayerSkin skin = new PlayerSkin(body, "", cape, cape, model, false);
            InstalledOverride replacement =
                    new InstalledOverride(expected, skin, () -> skin, skinHandle, capeHandle);
            return overrides.install(replacement);
        } catch (IOException | RuntimeException invalidTextureOrClientState) {
            release(skinHandle);
            release(capeHandle);
            return ApplyResult.DEFERRED;
        }
    }

    Optional<CurrentPlayerAppearanceSource.PlayerAppearance> installedAppearance(UUID profileId) {
        Objects.requireNonNull(profileId, "profileId");
        Minecraft minecraft = Minecraft.getInstance();
        checkClientThread(minecraft);
        InstalledOverride installed = overrides.active().orElse(null);
        if (installed == null || !installed.expected().profileId().equals(profileId)) {
            return Optional.empty();
        }
        TextureHandle skin = installed.skinHandle() != null
                ? installed.skinHandle()
                : new TextureHandle(installed.skin().texture().toString(), 64, 64);
        Optional<TextureHandle> cape = installed.capeHandle() == null
                ? Optional.empty()
                : Optional.of(installed.capeHandle());
        SkinModel model = installed.skin().model() == PlayerSkin.Model.SLIM
                ? SkinModel.SLIM
                : SkinModel.CLASSIC;
        return Optional.of(new CurrentPlayerAppearanceSource.PlayerAppearance(skin, model, cape));
    }

    @Override
    public ApplyResult reattach(ExpectedAppearance expected) {
        Objects.requireNonNull(expected, "expected");
        Minecraft minecraft = Minecraft.getInstance();
        checkClientThread(minecraft);
        return overrides.reattach(expected);
    }

    @Override
    public ApplyResult reset(ExpectedAppearance expected) {
        Objects.requireNonNull(expected, "expected");
        Minecraft minecraft = Minecraft.getInstance();
        checkClientThread(minecraft);
        if (!expected.profileId().equals(minecraft.getUser().getProfileId())) {
            return ApplyResult.DEFERRED;
        }
        overrides.clear();
        return ApplyResult.UPDATED;
    }


    void maintain() {
        Minecraft minecraft = Minecraft.getInstance();
        checkClientThread(minecraft);
        InstalledOverride installed = overrides.active().orElse(null);
        if (installed == null
                || !minecraft.getUser()
                        .getProfileId()
                        .equals(installed.expected().profileId())) {
            return;
        }
        PlayerInfo playerInfo = currentPlayerInfo(minecraft);
        if (playerInfo != null && playerInfo.skinLookup != installed.skinLookup()) {
            install(playerInfo, installed);
        }
    }

    @Override
    public void invalidate(ExpectedAppearance expected) {
        Objects.requireNonNull(expected, "expected");
        Minecraft minecraft = Minecraft.getInstance();
        checkClientThread(minecraft);
        overrides.invalidate(expected);
    }

    @Override
    public void close() {
        Minecraft minecraft = Minecraft.getInstance();
        checkClientThread(minecraft);
        overrides.close();
        textures.close();
    }

    private static void install(PlayerInfo playerInfo, InstalledOverride installed) {
        playerInfo.skinLookup = installed.skinLookup();
        playerInfo.getSkin();
    }

    private static ApplyResult attachToCurrentPlayer(
            Minecraft minecraft, InstalledOverride installed) {
        if (!minecraft.getUser()
                .getProfileId()
                .equals(installed.expected().profileId())) {
            return ApplyResult.DEFERRED;
        }
        PlayerInfo playerInfo = currentPlayerInfo(minecraft);
        if (playerInfo == null) {
            return ApplyResult.DEFERRED;
        }
        install(playerInfo, installed);
        return ApplyResult.UPDATED;
    }

    private static void restoreVanilla(Minecraft minecraft) {
        PlayerInfo playerInfo = currentPlayerInfo(minecraft);
        if (playerInfo == null) {
            return;
        }
        playerInfo.skinLookup = minecraft.getSkinManager().lookupInsecure(playerInfo.getProfile());
        playerInfo.getSkin();
    }

    private static PlayerInfo currentPlayerInfo(Minecraft minecraft) {
        ClientPacketListener connection = minecraft.getConnection();
        if (minecraft.player == null || connection == null) {
            return null;
        }
        UUID localPlayerId = minecraft.player.getUUID();
        PlayerInfo playerInfo = connection.getPlayerInfo(localPlayerId);
        return playerInfo != null && playerInfo.getProfile().getId().equals(localPlayerId)
                ? playerInfo
                : null;
    }

    private static ResourceLocation location(TextureHandle handle) {
        ResourceLocation location = ResourceLocation.tryParse(handle.location());
        if (location == null) {
            throw new IllegalArgumentException("Invalid installed texture location");
        }
        return location;
    }

    private void release(TextureHandle handle) {
        if (handle != null) {
            textures.release(handle);
        }
    }

    private static void checkClientThread(Minecraft minecraft) {
        if (!minecraft.isSameThread()) {
            throw new IllegalStateException("Appearance cache changes must run on the client thread");
        }
    }

    private record InstalledOverride(
            ExpectedAppearance expected,
            PlayerSkin skin,
            Supplier<PlayerSkin> skinLookup,
            TextureHandle skinHandle,
            TextureHandle capeHandle) {
        private InstalledOverride {
            Objects.requireNonNull(expected, "expected");
            Objects.requireNonNull(skin, "skin");
            Objects.requireNonNull(skinLookup, "skinLookup");
        }
    }
}

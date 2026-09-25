package com.naocraftlab.skins.compat.client.identifier;

import com.naocraftlab.skins.client.ExpectedAppearance;
import com.naocraftlab.skins.client.PlayerAppearanceSink;
import com.naocraftlab.skins.client.NativePlayerSkinLifecycle;
import com.naocraftlab.skins.client.SignedProfileResolver.ResolvedProfile;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.client.TextureRegistry.TextureKind;
import com.naocraftlab.skins.client.TextureRegistry.TextureHandle;
import com.naocraftlab.skins.runtime.AcknowledgedAppearanceAssets;
import com.naocraftlab.skins.runtime.AcknowledgedAppearanceAssets.Asset;
import com.naocraftlab.skins.runtime.AppearanceOverrideController;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;


final class IdentifierAppearanceSink
        implements PlayerAppearanceSink<AcknowledgedAppearanceAssets>, AutoCloseable {
    private Runnable maintenanceDirty = () -> { };
    private final IdentifierTextureRegistry textures =
            new IdentifierTextureRegistry("live/appearance", this::markMaintenanceDirty);
    private final Map<ProviderCape, TextureHandle> providerCapes = new HashMap<>();
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
    public void providerVisibility(com.naocraftlab.skins.client.ProviderVisibility visibility) {
        MinecraftProviderVisibility.set(visibility);
    }

    @Override
    public List<TrackedCapePlayer> trackedCapePlayers() {
        Minecraft minecraft = Minecraft.getInstance();
        checkClientThread(minecraft);
        ClientPacketListener connection = minecraft.getConnection();
        if (connection == null) return List.of();
        List<TrackedCapePlayer> players = new ArrayList<>();
        for (PlayerInfo info : connection.getOnlinePlayers()) {
            var profile = info.getProfile();
            if (profile.id() != null && profile.name() != null) {
                players.add(new TrackedCapePlayer(profile.id(), profile.name()));
            }
        }
        return List.copyOf(players);
    }

    @Override
    public Optional<String> registerCapeTexture(UUID profileId, CapeSource provider,
            String sha256, byte[] normalizedPng) {
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(sha256, "sha256");
        Objects.requireNonNull(normalizedPng, "normalizedPng");
        checkClientThread(Minecraft.getInstance());
        try {
            TextureHandle next = textures.register(TextureKind.IMAGE, sha256, normalizedPng);
            TextureHandle previous = providerCapes.put(new ProviderCape(profileId, provider), next);
            release(previous);
            return Optional.of(next.location());
        } catch (IOException | RuntimeException unavailableTexture) {
            return Optional.empty();
        }
    }

    @Override
    public void releaseCapeTexture(UUID profileId, CapeSource provider) {
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(provider, "provider");
        checkClientThread(Minecraft.getInstance());
        release(providerCapes.remove(new ProviderCape(profileId, provider)));
    }

    @Override
    public ApplyResult apply(ResolvedProfile<AcknowledgedAppearanceAssets> resolvedProfile) {
        Objects.requireNonNull(resolvedProfile, "resolvedProfile");
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            throw new IllegalStateException("Appearance installation is client-thread-only");
        }
        AcknowledgedAppearanceAssets payload = resolvedProfile.platformProfile();
        ExpectedAppearance expected = resolvedProfile.expectedAppearance();
        if (!resolvedProfile.profileId().equals(minecraft.getUser().getProfileId())) {
            return ApplyResult.DEFERRED;
        }
        Optional<ApplyResult> reattached = overrides.reattachIfActive(expected);
        if (reattached.isPresent()) {
            return dirty(reattached.orElseThrow());
        }

        TextureHandle skinHandle = null;
        TextureHandle capeHandle = null;
        final PlayerSkin playerSkin;
        try {
            PlayerSkin fallback = DefaultPlayerSkin.get(expected.profileId());
            ClientAsset.Texture body = fallback.body();
            PlayerModelType model = fallback.model();
            if (payload.skin().isPresent()) {
                Asset skin = payload.skin().orElseThrow();
                skinHandle = textures.register(
                        TextureKind.PLAYER_SKIN,
                        skin.sha256(),
                        skin.path());
                body = resourceTexture(skinHandle);
                model = expected.skinModel().orElseThrow() == SkinModel.SLIM
                        ? PlayerModelType.SLIM
                        : PlayerModelType.WIDE;
            }

            ClientAsset.Texture cape = null;
            if (payload.cape().isPresent()) {
                Asset resolvedCape = payload.cape().orElseThrow();
                capeHandle = textures.register(
                        TextureKind.IMAGE, resolvedCape.sha256(), resolvedCape.path());
                cape = resourceTexture(capeHandle);
            }
            playerSkin = PlayerSkin.insecure(body, cape, expected.capeHasElytra() || cape == null ? cape : new ClientAsset.ResourceTexture(
                    Identifier.parse("minecraft:textures/entity/equipment/wings/elytra.png"),
                    Identifier.parse("minecraft:textures/entity/equipment/wings/elytra.png")), model);
        } catch (IOException | RuntimeException invalidTextureOrClientState) {
            release(skinHandle);
            release(capeHandle);
            return ApplyResult.DEFERRED;
        }

        InstalledOverride replacement =
                new InstalledOverride(
                        expected, playerSkin, () -> playerSkin, skinHandle, capeHandle);
        try {
            return dirty(overrides.install(replacement));
        } catch (RuntimeException unavailablePlayerInfo) {
            release(skinHandle);
            release(capeHandle);
            return ApplyResult.DEFERRED;
        }
    }

    @Override
    public ApplyResult reattach(ExpectedAppearance expected) {
        Objects.requireNonNull(expected, "expected");
        Minecraft minecraft = Minecraft.getInstance();
        checkClientThread(minecraft);
        return dirty(overrides.reattach(expected));
    }

    @Override
    public ApplyResult reset(ExpectedAppearance expected) {
        Objects.requireNonNull(expected, "expected");
        Minecraft minecraft = Minecraft.getInstance();
        checkClientThread(minecraft);
        if (!expected.profileId().equals(minecraft.getUser().getProfileId())) {
            return ApplyResult.DEFERRED;
        }
        PlayerSkin accountDefault = DefaultPlayerSkin.get(expected.profileId());
        return dirty(overrides.install(new InstalledOverride(
                expected, accountDefault, () -> accountDefault, null, null)));
    }


    void maintain() {
        Minecraft minecraft = Minecraft.getInstance();
        checkClientThread(minecraft);
        InstalledOverride installed = overrides.active().orElse(null);
        if (installed == null
                || !minecraft.getUser()
                        .getProfileId()
                        .equals(installed.expected().profileId())
                || !skinReady(installed)) {
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
        markMaintenanceDirty();
    }

    void useMaintenanceDirty(Runnable listener) {
        maintenanceDirty = Objects.requireNonNull(listener, "listener");
    }

    boolean hasActiveOverride() {
        return overrides.active().isPresent();
    }

    Optional<PlayerSkin> installedSkin(UUID profileId) {
        Objects.requireNonNull(profileId, "profileId");
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            throw new IllegalStateException("Installed appearance must be read on the client thread");
        }
        InstalledOverride installed = overrides.active().orElse(null);
        return installed != null && installed.expected().profileId().equals(profileId)
                ? Optional.of(installed.skin())
                : Optional.empty();
    }

    @Override
    public void close() {
        MinecraftProviderVisibility.set(com.naocraftlab.skins.client.ProviderVisibility.ALL);
        Minecraft minecraft = Minecraft.getInstance();
        checkClientThread(minecraft);
        overrides.close();
        providerCapes.values().forEach(textures::release);
        providerCapes.clear();
        textures.close();
    }

    private static void install(PlayerInfo playerInfo, InstalledOverride installed) {
        playerInfo.skinLookup = installed.skinLookup();
        playerInfo.getSkin();
    }

    private ApplyResult dirty(ApplyResult result) {
        markMaintenanceDirty();
        return result;
    }

    private void markMaintenanceDirty() {
        maintenanceDirty.run();
    }

    private static ApplyResult attachToCurrentPlayer(
            Minecraft minecraft, InstalledOverride installed) {
        if (!minecraft.getUser()
                .getProfileId()
                .equals(installed.expected().profileId())
                || !skinReady(installed)) {
            return ApplyResult.DEFERRED;
        }
        PlayerInfo playerInfo = currentPlayerInfo(minecraft);
        if (playerInfo == null) {
            return ApplyResult.DEFERRED;
        }
        install(playerInfo, installed);
        return ApplyResult.UPDATED;
    }

    private static boolean skinReady(InstalledOverride installed) {
        return installed.skinHandle() == null
                || NativePlayerSkinLifecycle.isReady(installed.skinHandle().location());
    }

    private static void restoreVanilla(Minecraft minecraft) {
        PlayerInfo playerInfo = currentPlayerInfo(minecraft);
        if (playerInfo != null) {
            playerInfo.skinLookup =
                    minecraft.getSkinManager().createLookup(playerInfo.getProfile(), false);
            playerInfo.getSkin();
        }
    }

    private static PlayerInfo currentPlayerInfo(Minecraft minecraft) {
        ClientPacketListener connection = minecraft.getConnection();
        if (minecraft.player == null || connection == null) {
            return null;
        }
        UUID localPlayerId = minecraft.player.getUUID();
        PlayerInfo playerInfo = connection.getPlayerInfo(localPlayerId);
        return playerInfo != null && playerInfo.getProfile().id().equals(localPlayerId)
                ? playerInfo
                : null;
    }

    private static ClientAsset.Texture resourceTexture(TextureHandle handle) {
        Identifier location = Identifier.parse(handle.location());
        return new ClientAsset.ResourceTexture(location, location);
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

    private record ProviderCape(UUID profileId, CapeSource provider) {}

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

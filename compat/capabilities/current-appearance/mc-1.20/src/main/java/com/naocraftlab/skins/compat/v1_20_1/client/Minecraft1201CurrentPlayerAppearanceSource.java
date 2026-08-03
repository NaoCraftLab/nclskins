package com.naocraftlab.skins.compat.v1_20_1.client;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.naocraftlab.skins.client.CurrentPlayerAppearanceSource;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.client.TextureRegistry.TextureHandle;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;


public final class Minecraft1201CurrentPlayerAppearanceSource
        implements CurrentPlayerAppearanceSource {
    private final Function<UUID, Optional<PlayerAppearance>> installedAppearance;
    private volatile UUID resolvedProfileId;
    private volatile ResourceLocation resolvedSkin;
    private volatile ResourceLocation resolvedCape;
    private volatile SkinModel resolvedModel;
    private boolean skinLookupStarted;

    public Minecraft1201CurrentPlayerAppearanceSource() {
        this(ignored -> Optional.empty());
    }

    public Minecraft1201CurrentPlayerAppearanceSource(
            Function<UUID, Optional<PlayerAppearance>> installedAppearance) {
        this.installedAppearance = Objects.requireNonNull(installedAppearance, "installedAppearance");
    }

    @Override
    public PlayerAppearance currentPlayerAppearance() {
        Minecraft minecraft = Minecraft.getInstance();
        GameProfile profile = minecraft.getUser().getGameProfile();
        UUID profileId = profile.getId() == null ? new UUID(0L, 0L) : profile.getId();
        initializeProfile(profileId);

        Optional<PlayerAppearance> installed = installedAppearance.apply(profileId);
        if (installed.isPresent()) {
            return installed.orElseThrow();
        }

        AbstractClientPlayer player = minecraft.player;
        if (player != null) {
            resolvedSkin = player.getSkinTextureLocation();
            resolvedCape = player.getCloakTextureLocation();
            resolvedModel = "slim".equals(player.getModelName())
                    ? SkinModel.SLIM
                    : SkinModel.CLASSIC;
        } else {
            startSkinLookupWhenAvailable(minecraft, profile, profileId);
        }

        return new PlayerAppearance(
                new TextureHandle(resolvedSkin.toString(), 64, 64),
                resolvedModel,
                Optional.ofNullable(resolvedCape)
                        .map(value -> new TextureHandle(value.toString(), 64, 32)));
    }

    private synchronized void initializeProfile(UUID profileId) {
        if (profileId.equals(resolvedProfileId)) {
            return;
        }
        resolvedSkin = DefaultPlayerSkin.getDefaultSkin(profileId);
        resolvedCape = null;
        resolvedModel = defaultModel(profileId);
        skinLookupStarted = false;
        resolvedProfileId = profileId;
    }

    private synchronized void startSkinLookupWhenAvailable(
            Minecraft minecraft, GameProfile profile, UUID profileId) {
        if (!profileId.equals(resolvedProfileId) || skinLookupStarted) {
            return;
        }


        var skinManager = minecraft.getSkinManager();
        if (skinManager == null) {
            return;
        }
        skinLookupStarted = true;


        try {
            skinManager.registerSkins(
                    profile,
                    (type, location, texture) ->
                            textureAvailable(profileId, type, location, texture),
                    false);
        } catch (RuntimeException unavailable) {


            skinLookupStarted = false;
        }
    }

    private synchronized void textureAvailable(
            UUID profileId,
            MinecraftProfileTexture.Type type,
            ResourceLocation location,
            MinecraftProfileTexture texture) {
        if (!profileId.equals(resolvedProfileId)) {
            return;
        }
        if (type == MinecraftProfileTexture.Type.SKIN) {
            resolvedSkin = location;
            resolvedModel = "slim".equals(texture.getMetadata("model"))
                    ? SkinModel.SLIM
                    : SkinModel.CLASSIC;
        } else if (type == MinecraftProfileTexture.Type.CAPE) {
            resolvedCape = location;
        }
    }

    private static SkinModel defaultModel(UUID profileId) {
        return "slim".equals(DefaultPlayerSkin.getSkinModelName(profileId))
                ? SkinModel.SLIM
                : SkinModel.CLASSIC;
    }
}

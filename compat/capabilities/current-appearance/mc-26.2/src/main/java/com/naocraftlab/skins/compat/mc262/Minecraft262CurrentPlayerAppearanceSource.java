package com.naocraftlab.skins.compat.mc262;

import com.naocraftlab.skins.client.CurrentPlayerAppearanceSource;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.client.TextureRegistry.TextureHandle;
import java.util.Optional;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.component.ResolvableProfile;


public final class Minecraft262CurrentPlayerAppearanceSource
        implements CurrentPlayerAppearanceSource {
    private final Function<UUID, Optional<PlayerSkin>> installedSkin;

    public Minecraft262CurrentPlayerAppearanceSource(
            Function<UUID, Optional<PlayerSkin>> installedSkin) {
        this.installedSkin = Objects.requireNonNull(installedSkin, "installedSkin");
    }

    @Override
    public PlayerAppearance currentPlayerAppearance() {
        Minecraft minecraft = Minecraft.getInstance();
        PlayerSkin skin = installedSkin.apply(minecraft.getUser().getProfileId())
                .orElseGet(() -> currentOrDefault(minecraft));
        return new PlayerAppearance(
                new TextureHandle(skin.body().texturePath().toString(), 64, 64),
                skin.model() == PlayerModelType.SLIM ? SkinModel.SLIM : SkinModel.CLASSIC,
                skin.cape() == null
                        ? Optional.empty()
                        : Optional.of(new TextureHandle(skin.cape().texturePath().toString(), 64, 32)));
    }

    private static PlayerSkin currentOrDefault(Minecraft minecraft) {
        if (minecraft.player != null) {
            return minecraft.player.getSkin();
        }
        try {
            return minecraft.playerSkinRenderCache()
                    .getOrDefault(ResolvableProfile.createResolved(minecraft.getGameProfile()))
                    .playerSkin();
        } catch (RuntimeException unavailable) {
            return DefaultPlayerSkin.get(minecraft.getUser().getProfileId());
        }
    }
}

package com.naocraftlab.skins.mc1211;

import com.naocraftlab.skins.client.CurrentPlayerAppearanceSource;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.client.TextureRegistry.TextureHandle;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;


public final class Minecraft1211CurrentPlayerAppearanceSource
        implements CurrentPlayerAppearanceSource {
    private final Function<UUID, Optional<PlayerAppearance>> installedAppearance;

    public Minecraft1211CurrentPlayerAppearanceSource() {
        this(ignored -> Optional.empty());
    }

    public Minecraft1211CurrentPlayerAppearanceSource(
            Function<UUID, Optional<PlayerAppearance>> installedAppearance) {
        this.installedAppearance = Objects.requireNonNull(installedAppearance, "installedAppearance");
    }

    @Override
    public PlayerAppearance currentPlayerAppearance() {
        Minecraft minecraft = Minecraft.getInstance();
        Optional<PlayerAppearance> installed =
                installedAppearance.apply(minecraft.getUser().getProfileId());
        if (installed.isPresent()) {
            return installed.orElseThrow();
        }
        PlayerSkin skin;
        if (minecraft.player != null) {
            skin = minecraft.player.getSkin();
        } else {
            try {
                skin = minecraft.getSkinManager().getInsecureSkin(minecraft.getGameProfile());
            } catch (RuntimeException unavailable) {
                skin = DefaultPlayerSkin.get(minecraft.getUser().getProfileId());
            }
        }
        return new PlayerAppearance(
                new TextureHandle(skin.texture().toString(), 64, 64),
                skin.model() == PlayerSkin.Model.SLIM ? SkinModel.SLIM : SkinModel.CLASSIC,
                Optional.ofNullable(skin.capeTexture())
                        .map(value -> new TextureHandle(value.toString(), 64, 32)));
    }
}

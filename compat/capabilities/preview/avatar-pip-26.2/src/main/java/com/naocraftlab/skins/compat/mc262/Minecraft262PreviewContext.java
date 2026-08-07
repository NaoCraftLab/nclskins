package com.naocraftlab.skins.compat.mc262;

import com.naocraftlab.skins.client.PreviewRenderer;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.ItemStack;

public final class Minecraft262PreviewContext {
    private final AbstractClientPlayer player;
    private final PreviewRenderer.PreviewAppearance appearance;
    private final PlayerSkin skin;
    private final ItemStack chestEquipment;

    Minecraft262PreviewContext(
            AbstractClientPlayer player,
            PreviewRenderer.PreviewAppearance appearance,
            PlayerSkin skin,
            ItemStack chestEquipment) {
        this.player = Objects.requireNonNull(player, "player");
        this.appearance = Objects.requireNonNull(appearance, "appearance");
        this.skin = Objects.requireNonNull(skin, "skin");
        this.chestEquipment = Objects.requireNonNull(chestEquipment, "chestEquipment").copy();
    }

    public Minecraft262PreviewScope open(Minecraft minecraft) {
        return Minecraft262PreviewScope.open(
                minecraft, player, appearance, skin, chestEquipment.copy());
    }
}

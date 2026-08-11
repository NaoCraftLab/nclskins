package com.naocraftlab.skins.compat.mc12111;

import com.naocraftlab.skins.client.PreviewRenderer;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.ItemStack;

public final class Minecraft12111PreviewContext {
    private final AbstractClientPlayer player;
    private final PreviewRenderer.PreviewAppearance appearance;
    private final PlayerSkin skin;
    private final ItemStack chestEquipment;

    Minecraft12111PreviewContext(
            AbstractClientPlayer player,
            PreviewRenderer.PreviewAppearance appearance,
            PlayerSkin skin,
            ItemStack chestEquipment) {
        this.player = Objects.requireNonNull(player, "player");
        this.appearance = Objects.requireNonNull(appearance, "appearance");
        this.skin = Objects.requireNonNull(skin, "skin");
        this.chestEquipment = Objects.requireNonNull(chestEquipment, "chestEquipment").copy();
    }

    public Minecraft12111PreviewScope open(Minecraft minecraft) {
        return Minecraft12111PreviewScope.open(
                minecraft, player, appearance, skin, chestEquipment.copy());
    }
}

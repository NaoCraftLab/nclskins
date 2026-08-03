package com.naocraftlab.skins.compat.v1_20_1.client;

import com.naocraftlab.skins.client.OuterLayerPart;
import com.naocraftlab.skins.client.OuterLayerVisibility;
import com.naocraftlab.skins.client.OuterLayerVisibilityController;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.PlayerModelPart;


public final class Minecraft1201OuterLayerVisibilityController
        implements OuterLayerVisibilityController {
    @Override
    public void applyDurable(OuterLayerVisibility visibility) {
        Objects.requireNonNull(visibility, "visibility");
        Minecraft minecraft = Minecraft.getInstance();
        boolean changed = false;
        changed |= set(minecraft, PlayerModelPart.HAT, visibility.visible(OuterLayerPart.HEAD));
        changed |= set(minecraft, PlayerModelPart.JACKET, visibility.visible(OuterLayerPart.BODY));
        changed |= set(minecraft, PlayerModelPart.LEFT_SLEEVE, visibility.visible(OuterLayerPart.LEFT_ARM));
        changed |= set(minecraft, PlayerModelPart.RIGHT_SLEEVE, visibility.visible(OuterLayerPart.RIGHT_ARM));
        changed |= set(minecraft, PlayerModelPart.LEFT_PANTS_LEG, visibility.visible(OuterLayerPart.LEFT_LEG));
        changed |= set(minecraft, PlayerModelPart.RIGHT_PANTS_LEG, visibility.visible(OuterLayerPart.RIGHT_LEG));
        if (!changed) {
            return;
        }
        minecraft.options.save();
        if (minecraft.getConnection() != null) {
            minecraft.options.broadcastOptions();
        }
    }

    private static boolean set(Minecraft minecraft, PlayerModelPart part, boolean visible) {
        if (minecraft.options.isModelPartEnabled(part) == visible) {
            return false;
        }
        minecraft.options.toggleModelPart(part, visible);
        return true;
    }
}

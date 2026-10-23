package com.naocraftlab.skins.compat.client.resourcelocation.playerinfo;

import com.mojang.blaze3d.systems.RenderSystem;
import com.naocraftlab.skins.client.PlayerPreviewLighting;
import org.joml.Vector3f;

final class BakedPreviewLighting {
    private BakedPreviewLighting() {
    }

    static void setup() {
        PlayerPreviewLighting.Rig rig = PlayerPreviewLighting.centeredFront();
        RenderSystem.setShaderLights(lightDirection(rig.primary()), lightDirection(rig.fill()));
    }

    private static Vector3f lightDirection(PlayerPreviewLighting.Direction direction) {
        return new Vector3f(direction.x(), direction.y(), direction.z());
    }
}

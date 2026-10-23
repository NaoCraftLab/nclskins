package com.naocraftlab.skins.compat.client.identifier.foil;

import com.naocraftlab.skins.client.TextureUseValidity;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;

public final class OrderedCapeFoil {
    public static RenderType pass(TextureUseValidity validity) {
        return CapturedCapePass.material(RenderTypes.entityGlint(), validity, true);
    }

    public static boolean owns(RenderType type) {
        CapturedCapePass capture = CapturedCapePass.of(type);
        return capture != null && capture.orderedFoil();
    }

    private OrderedCapeFoil() {}
}

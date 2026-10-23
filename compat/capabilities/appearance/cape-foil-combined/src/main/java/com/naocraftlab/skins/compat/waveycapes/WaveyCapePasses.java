package com.naocraftlab.skins.compat.waveycapes;

import com.naocraftlab.skins.client.TextureUseValidity;
import com.naocraftlab.skins.compat.client.identifier.foil.CapeTranslucentFoilMaterial;
import com.naocraftlab.skins.compat.client.identifier.foil.CapturedCapePass;
import dev.tr7zw.waveycapes.render.CapePass;
import java.util.List;
import java.util.function.UnaryOperator;
import net.minecraft.resources.Identifier;

final class WaveyCapePasses {
    static List<CapePass> plan(Identifier texture, TextureUseValidity validity) {
        return List.of(new CapePass(CapturedCapePass.material(
                CapeTranslucentFoilMaterial.forTexture(texture), validity, false), UnaryOperator.identity()));
    }
    private WaveyCapePasses() {}
}

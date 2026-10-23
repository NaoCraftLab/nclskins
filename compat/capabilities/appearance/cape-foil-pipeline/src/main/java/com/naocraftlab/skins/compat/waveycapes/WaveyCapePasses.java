package com.naocraftlab.skins.compat.waveycapes;

import com.naocraftlab.skins.client.TextureUseValidity;
import com.naocraftlab.skins.compat.client.identifier.foil.*;
import dev.tr7zw.waveycapes.render.CapePass;
import java.util.List;
import java.util.function.UnaryOperator;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

final class WaveyCapePasses {
    static List<CapePass> plan(Identifier texture, TextureUseValidity validity) {
        RenderType base = CapturedCapePass.material(CapeTranslucentFoilMaterial.forTexture(texture), validity, false);
        if (!CapeTranslucentFoilMaterial.inWorld()) return List.of(new CapePass(base, UnaryOperator.identity()));
        CapeVertexReplay stream = new CapeVertexReplay(validity);
        return List.of(new CapePass(base, stream::record),
                new CapePass(OrderedCapeFoil.pass(validity), stream::replay));
    }
    private WaveyCapePasses() {}
}

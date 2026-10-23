package com.naocraftlab.skins.compat.client.identifier.foil;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.TextureTransform;
import net.minecraft.resources.Identifier;

public final class CapeTranslucentFoilMaterial {
    private static final int MAX_CACHED_TEXTURES = 256;
    private static final RenderPipeline PIPELINE = CapeFoilPipeline.create();
    private static final Map<Identifier, RenderType> TYPES = new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Identifier, RenderType> eldest) {
            return size() > MAX_CACHED_TEXTURES;
        }
    };

    public static RenderType forTexture(Identifier texture) {
        return forTexture(texture, inWorld());
    }

    static synchronized RenderType forTexture(Identifier texture, boolean inWorld) {
        if (inWorld) return RenderTypes.entityTranslucent(texture);
        return TYPES.computeIfAbsent(texture, CapeTranslucentFoilMaterial::create);
    }

    public static boolean inWorld() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft != null && minecraft.level != null;
    }

    private static RenderType create(Identifier texture) {
        RenderSetup setup = RenderSetup.builder(PIPELINE)
                .withTexture("Sampler0", texture)
                .withTexture("GlintSampler", Identifier.withDefaultNamespace("textures/misc/enchanted_glint_item.png"))
                .setTextureTransform(TextureTransform.ENTITY_GLINT_TEXTURING)
                .useLightmap()
                .useOverlay()
                .affectsCrumbling()
                .sortOnUpload()
                .setOutline(RenderSetup.OutlineProperty.AFFECTS_OUTLINE)
                .createRenderSetup();
        return RenderType.create("nclskins_cape_translucent_foil", setup);
    }

    private CapeTranslucentFoilMaterial() {}
}

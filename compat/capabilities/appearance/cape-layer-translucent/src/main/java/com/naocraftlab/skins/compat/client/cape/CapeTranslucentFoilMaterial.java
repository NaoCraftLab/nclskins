package com.naocraftlab.skins.compat.client.cape;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;

public final class CapeTranslucentFoilMaterial extends RenderType {
    public static final ResourceLocation SHADER = ResourceLocation.tryParse("nclskins:cape_entity_foil");
    private static final ResourceLocation GLINT = ResourceLocation.tryParse(
            "minecraft:textures/misc/enchanted_glint_item.png");
    private static final int MAX_CACHED_TEXTURES = 256;
    private static final Map<ResourceLocation, RenderType> TYPES = new LinkedHashMap<>(16, 0.75f, true);
    private static ShaderInstance shader;
    private final ResourceLocation texture;

    private CapeTranslucentFoilMaterial(ResourceLocation texture, RenderStateShard[] states) {
        super("nclskins_cape_translucent_foil", DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.QUADS, 1536, true, true,
                () -> { for (RenderStateShard state : states) state.setupRenderState(); },
                () -> { for (RenderStateShard state : states) state.clearRenderState(); });
        this.texture = texture;
    }

    public static void shaderLoaded(ShaderInstance loaded) {
        shader = loaded;
    }

    public static RenderType forTexture(ResourceLocation texture) {
        return forTexture(texture, inWorld());
    }

    static synchronized RenderType forTexture(ResourceLocation texture, boolean inWorld) {
        if (inWorld) return RenderType.entityTranslucent(texture);
        if (shader == null) return RenderType.entityTranslucent(texture);
        RenderType type = TYPES.get(texture);
        if (type == null) {
            type = create(texture);
            TYPES.put(texture, type);
            if (TYPES.size() > MAX_CACHED_TEXTURES) TYPES.remove(TYPES.keySet().iterator().next());
        }
        return type;
    }

    public static boolean inWorld() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft != null && minecraft.level != null;
    }

    public static VertexConsumer withFoil(MultiBufferSource buffers, Supplier<VertexConsumer> base, boolean direct) {
        VertexConsumer foil = buffers.getBuffer(
                direct ? RenderType.entityGlintDirect() : RenderType.entityGlint());
        return VertexMultiConsumer.create(foil, base.get());
    }

    public static VertexConsumer buffer(MultiBufferSource buffers, ResourceLocation texture,
            boolean glint, RenderType plain) {
        RenderType type = glint ? forTexture(texture) : plain;
        if (glint && inWorld()) return withFoil(buffers, () -> buffers.getBuffer(type), true);
        return buffers.getBuffer(type);
    }

    private static RenderType create(ResourceLocation texture) {
        RenderStateShard[] states = {
                new ShaderStateShard(() -> shader), new CapeTextureState(texture),
                TRANSLUCENT_TRANSPARENCY, LEQUAL_DEPTH_TEST, NO_CULL,
                LIGHTMAP, OVERLAY, ENTITY_GLINT_TEXTURING, COLOR_DEPTH_WRITE, MAIN_TARGET
        };
        return new CapeTranslucentFoilMaterial(texture, states);
    }

    @Override
    public Optional<RenderType> outline() {
        return Optional.of(RenderType.outline(texture));
    }

    private static final class CapeTextureState extends TextureStateShard {
        private CapeTextureState(ResourceLocation texture) {
            super(texture, false, false);
        }

        @Override
        public void setupRenderState() {
            super.setupRenderState();
            RenderSystem.setShaderTexture(3, GLINT);
        }
    }
}

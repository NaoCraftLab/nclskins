package com.naocraftlab.skins.compat.client.cape.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.authlib.GameProfile;
import com.naocraftlab.skins.runtime.CapeProjection;
import com.naocraftlab.skins.compat.client.cape.CapeTranslucentFoilMaterial;
import com.naocraftlab.skins.client.ExactLocalPlayerScope;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(CapeLayer.class)
public abstract class CapeLayerTransparencyMixin {
    @WrapOperation(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/player/AbstractClientPlayer;FFFFFF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/RenderType;entitySolid(Lnet/minecraft/resources/ResourceLocation;)Lnet/minecraft/client/renderer/RenderType;"),
            require = 1, expect = 1, allow = 1)
    private RenderType nclskins$capeTransparency(ResourceLocation texture,
            Operation<RenderType> original, @Local(argsOnly = true) AbstractClientPlayer player,
            @Share("nativeFoil") LocalBooleanRef nativeFoil,
            @Share("directFoil") LocalBooleanRef directFoil) {
        boolean glint = false;
        boolean preview = false;
        if (texture != null) {
            Boolean previewGlint = ExactLocalPlayerScope.capeGlint(player, texture.toString());
            if (previewGlint != null) {
                preview = true;
                glint = previewGlint;
            } else {
                GameProfile profile = player.getGameProfile();
                if (profile.getId() != null && profile.getName() != null) {
                    boolean self = Minecraft.getInstance().getUser().getProfileId().equals(profile.getId());
                    CapeProjection.Result selected = CapeProjection.material(profile.getId(), profile.getName(), self);
                    glint = selected.capeGlint() && texture.toString().equals(selected.capeLocation());
                }
            }
        }
        nativeFoil.set(glint && CapeTranslucentFoilMaterial.inWorld());
        directFoil.set(preview);
        if (glint) return CapeTranslucentFoilMaterial.forTexture(texture);
        RenderType selected = original.call(texture);
        boolean owned = texture.getNamespace().equals("nclskins")
                || texture.getPath().startsWith("dynamic/nclskins/");
        return owned && selected == RenderType.entitySolid(texture)
                ? RenderType.entityTranslucent(texture) : selected;
    }

    @WrapOperation(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/player/AbstractClientPlayer;FFFFFF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/MultiBufferSource;getBuffer(Lnet/minecraft/client/renderer/RenderType;)Lcom/mojang/blaze3d/vertex/VertexConsumer;"),
            require = 1, expect = 1, allow = 1)
    private VertexConsumer nclskins$capeFoilBuffer(MultiBufferSource buffers, RenderType type,
            Operation<VertexConsumer> original, @Share("nativeFoil") LocalBooleanRef nativeFoil,
            @Share("directFoil") LocalBooleanRef directFoil) {
        if (!nativeFoil.get()) return original.call(buffers, type);
        MultiBufferSource foilBuffers = directFoil.get()
                ? Minecraft.getInstance().renderBuffers().bufferSource() : buffers;
        return CapeTranslucentFoilMaterial.withFoil(foilBuffers,
                () -> original.call(buffers, type), directFoil.get());
    }
}

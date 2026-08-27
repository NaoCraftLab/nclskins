package com.naocraftlab.skins.compat.client.identifier.submission.mixin;

import com.naocraftlab.skins.client.PreviewDepthEnvelope;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Mixin(PictureInPictureRenderer.class)
abstract class PictureInPictureRendererDepthMixin {
    @ModifyArgs(
            method = "<init>(Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/CachedOrthoProjectionMatrixBuffer;<init>(Ljava/lang/String;FFZ)V"),
            require = 1,
            expect = 1,
            allow = 1)
    private void nclskins$expandPipDepth(Args args) {
        args.set(1, -PreviewDepthEnvelope.MAXIMUM);
        args.set(2, PreviewDepthEnvelope.MAXIMUM);
    }
}

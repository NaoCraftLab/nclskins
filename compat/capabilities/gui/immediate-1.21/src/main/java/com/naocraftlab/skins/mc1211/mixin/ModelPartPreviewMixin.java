package com.naocraftlab.skins.mc1211.mixin;

import com.naocraftlab.skins.mc1211.Minecraft1211PreviewModelAnchors;
import java.util.List;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.RandomSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ModelPart.class)
abstract class ModelPartPreviewMixin {
    @Shadow
    @Final
    private List<ModelPart.Cube> cubes;

    @Inject(method = "getRandomCube", at = @At("HEAD"), cancellable = true)
    private void nclskins$provideEditorAnchor(
            RandomSource random,
            CallbackInfoReturnable<ModelPart.Cube> callback) {
        ModelPart.Cube anchor = cubes.isEmpty()
                ? Minecraft1211PreviewModelAnchors.anchor((ModelPart) (Object) this)
                : null;
        if (anchor != null) {
            callback.setReturnValue(anchor);
        }
    }
}

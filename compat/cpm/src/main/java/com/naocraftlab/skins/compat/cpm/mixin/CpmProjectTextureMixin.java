package com.naocraftlab.skins.compat.cpm.mixin;

import com.naocraftlab.skins.compat.cpm.CpmProjectImportScope;
import com.tom.cpm.shared.editor.Editor;
import com.tom.cpm.shared.editor.ETextures;
import com.tom.cpm.shared.model.TextureSheetType;
import java.util.function.BiConsumer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.tom.cpm.shared.editor.Generators", remap = false)
abstract class CpmProjectTextureMixin implements com.naocraftlab.skins.compat.cpm.CpmProjectTextureAccess {
    @Inject(method = {
            "lambda$loadTextures$0(Lcom/tom/cpm/shared/editor/Editor;Ljava/util/function/BiConsumer;Lcom/tom/cpm/shared/model/TextureSheetType;)V",
            "lambda$loadTextures$15(Lcom/tom/cpm/shared/editor/Editor;Ljava/util/function/BiConsumer;Lcom/tom/cpm/shared/model/TextureSheetType;)V"},
            at = @At(value = "INVOKE", target = "Lcom/tom/cpm/shared/MinecraftClientAccess;get()Lcom/tom/cpm/shared/MinecraftClientAccess;", remap = false),
            cancellable = true, remap = false, require = 1, expect = 1, allow = 1)
    private static void nclskins$projectTexture(Editor editor, BiConsumer<TextureSheetType, ETextures> textures,
            TextureSheetType sheet, CallbackInfo callback) {
        if (CpmProjectImportScope.owns(editor)) callback.cancel();
    }
}

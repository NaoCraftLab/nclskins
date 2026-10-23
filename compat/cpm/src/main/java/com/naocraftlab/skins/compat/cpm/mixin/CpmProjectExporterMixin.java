package com.naocraftlab.skins.compat.cpm.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.naocraftlab.skins.compat.cpm.CpmProjectImportScope;
import com.tom.cpm.shared.editor.Editor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets="com.tom.cpm.shared.editor.Exporter",remap=false)
abstract class CpmProjectExporterMixin {
    @WrapOperation(method="prepareDefinition(Lcom/tom/cpm/shared/editor/Editor;)Lcom/tom/cpm/shared/parts/ModelPartDefinition;",
        at=@At(value="INVOKE",target="Lcom/tom/cpm/shared/util/Log;info(Ljava/lang/Object;)V",remap=false),remap=false,require=1,expect=1,allow=1)
    private static void nclskins$projectLog(Object payload,Operation<Void> original,Editor editor) {
        if(!CpmProjectImportScope.owns(editor))original.call(payload);
    }
}

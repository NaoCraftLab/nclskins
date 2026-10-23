package com.naocraftlab.skins.compat.cpm.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.naocraftlab.skins.compat.cpm.CpmProjectImportScope;
import com.tom.cpm.shared.editor.Editor;
import com.tom.cpm.shared.editor.project.JsonMap;
import com.tom.cpm.shared.definition.*;
import com.tom.cpm.shared.config.ResourceLoader.ResourceEncoding;
import java.io.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets="com.tom.cpm.shared.editor.template.EditorTemplate",remap=false)
abstract class CpmProjectTemplateMixin {
    @WrapOperation(method="load(Lcom/tom/cpm/shared/editor/Editor;Lcom/tom/cpm/shared/editor/project/JsonMap;)Lcom/tom/cpm/shared/editor/template/EditorTemplate;",
        at=@At(value="INVOKE",target="Lcom/tom/cpm/shared/definition/ModelDefinitionLoader;load(Lcom/tom/cpm/shared/definition/Link;Lcom/tom/cpm/shared/config/ResourceLoader$ResourceEncoding;Lcom/tom/cpm/shared/definition/ModelDefinition;)Ljava/io/InputStream;",remap=false),remap=false,require=1,expect=1,allow=1)
    private static InputStream nclskins$projectTemplate(ModelDefinitionLoader<?> loader,Link link,ResourceEncoding encoding,ModelDefinition definition,Operation<InputStream> original,Editor editor,JsonMap data) throws IOException {
        return CpmProjectImportScope.owns(editor)?CpmProjectImportScope.template(editor,link,encoding,definition):original.call(loader,link,encoding,definition);
    }
}

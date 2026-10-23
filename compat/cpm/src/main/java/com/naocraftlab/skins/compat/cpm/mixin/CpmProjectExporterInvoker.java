package com.naocraftlab.skins.compat.cpm.mixin;

import com.tom.cpm.shared.editor.Editor;
import com.tom.cpm.shared.parts.ModelPartDefinition;
import java.io.IOException;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Invoker;

@Pseudo
@Mixin(targets="com.tom.cpm.shared.editor.Exporter",remap=false)
public interface CpmProjectExporterInvoker {
    @SuppressWarnings("deprecation")
    @Invoker("prepareDefinition")
    static ModelPartDefinition nclskins$prepareDefinition(Editor editor) throws IOException { throw new AssertionError(); }
}

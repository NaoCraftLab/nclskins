package com.naocraftlab.skins.compat.cpm.mixin;

import com.tom.cpm.shared.definition.Link;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(targets = "com.tom.cpm.shared.io.ModelFile", remap = false)
public interface CpmModelFileBackupAccessor {
    @Accessor("link") Link nclskins$modelLink();
    @Accessor("overflowLocal") byte[] nclskins$modelBackup();
}

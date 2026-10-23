package com.naocraftlab.skins.compat.cpm;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

public final class CpmMixinPlugin implements IMixinConfigPlugin {
    @Override public void onLoad(String mixinPackage) { org.spongepowered.asm.mixin.injection.selectors.TargetSelector.register(MergedCpmSelector.class, "nclskins"); }
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String target, String mixin) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null || loader.getResource("com/tom/cpm/client/PlayerProfile.class") == null) return false;
        boolean state = loader.getResource("com/tom/cpm/client/PlayerRenderStateAccess.class") != null;
        if (mixin.endsWith("CpmModelDefinitionPartsAccessor") || mixin.endsWith("CpmModelSkinTextureAccessor")
                || mixin.endsWith("CpmModelTextureAccessor") || mixin.endsWith("CpmModelFileBackupAccessor")) return CpmModelFileBindings.supported(loader);
        if (mixin.contains("CpmProject")) return CpmProjectBindings.supported(loader);
        if (mixin.endsWith("CapeBranchBoundPlayerMixin")) return !state;
        if (mixin.endsWith("CapeBranchRenderStateMixin")) return state;
        return true;
    }
    @Override public void acceptTargets(Set<String> own, Set<String> other) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String name, ClassNode node, String mixin, IMixinInfo info) {}
    @Override public void postApply(String name, ClassNode node, String mixin, IMixinInfo info) {
        if (!mixin.endsWith("CapeBranchBoundPlayerMixin") && !mixin.endsWith("CapeBranchRenderStateMixin")) return;
        long count = node.methods.stream().filter(method -> {
            var merged = org.spongepowered.asm.util.Annotations.getVisible(method,
                    org.spongepowered.asm.mixin.transformer.meta.MixinMerged.class);
            return method.name.matches("handler\\$[a-z0-9]+\\$(?:cpm\\$)?(?:onRender|submitCape)")
                    && merged != null && java.util.List.of("com.tom.cpm.mixin.CapeLayerMixin",
                    "com.tom.cpm.mixin.render.CapeLayerMixin").contains(
                    org.spongepowered.asm.util.Annotations.getValue(merged, "mixin"));
        }).count();
        if (count != 1) throw new IllegalStateException("Exact CPM cape handler cardinality: " + count);
    }
}

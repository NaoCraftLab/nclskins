package com.naocraftlab.skins.compat.client.resourcelocation.skinlookup.ears;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

public final class EarsMeasurementPlugin implements IMixinConfigPlugin {
    @Override public void onLoad(String name) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String target, String mixin) {
        var loader = Thread.currentThread().getContextClassLoader();
        return loader != null && loader.getResource("diy/y2k/five/buzz/support/EarsFeaturesHolder.class") != null;
    }
    @Override public void acceptTargets(Set<String> own, Set<String> other) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String name, ClassNode node, String mixin, IMixinInfo info) {}
    @Override public void postApply(String name, ClassNode node, String mixin, IMixinInfo info) {}
}

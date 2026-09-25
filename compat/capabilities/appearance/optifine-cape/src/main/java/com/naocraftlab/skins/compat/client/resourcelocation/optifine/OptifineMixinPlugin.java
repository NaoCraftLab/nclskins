package com.naocraftlab.skins.compat.client.resourcelocation.optifine;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

public final class OptifineMixinPlugin implements IMixinConfigPlugin {
    private static final String CAPE_UTILS = "srg/net/optifine/player/CapeUtils.class";

    @Override
    public void onLoad(String mixinPackage) {}

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader != null && (loader.getResource(CAPE_UTILS) != null
                || loader.getResource("net/optifine/player/CapeUtils.class") != null);
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass,
            String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass,
            String mixinClassName, IMixinInfo mixinInfo) {}
}

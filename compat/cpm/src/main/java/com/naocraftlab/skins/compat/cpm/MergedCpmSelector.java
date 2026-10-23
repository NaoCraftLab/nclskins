package com.naocraftlab.skins.compat.cpm;

import org.spongepowered.asm.mixin.injection.selectors.*;
import org.spongepowered.asm.mixin.transformer.meta.MixinMerged;
import org.spongepowered.asm.util.Annotations;

@ITargetSelectorDynamic.SelectorId(namespace = "nclskins", value = "cpmaccessories")
public final class MergedCpmSelector implements ITargetSelectorDynamic {
    private final String owner;
    private final String mixin;
    private final String name;
    private final java.util.List<String> descriptors;
    private MergedCpmSelector(String owner, String mixin, String name, String descriptor) {
        this.owner = owner; this.mixin = mixin; this.name = name; this.descriptors = java.util.List.of(descriptor.split("\\|", -1));
    }
    public static MergedCpmSelector parse(String input, ISelectorContext context) {
        String[] parts = input.split(";", 3);
        if (parts.length != 3) throw new IllegalArgumentException(input);
        return new MergedCpmSelector(context.getMixin().getTargetClassRef(), parts[0], parts[1], parts[2]);
    }
    public ITargetSelector next() { return null; }
    public ITargetSelector configure(Configure config, String... args) { return this; }
    public ITargetSelector validate() { return this; }
    public ITargetSelector attach(ISelectorContext context) { return this; }
    public int getMinMatchCount() { return 1; }
    public int getMaxMatchCount() { return 1; }
    public <T> MatchResult match(ElementNode<T> node) {
        var method = node.getMethod();
        if (method == null || !owner.equals(node.getOwner()) || !descriptors.contains(node.getDesc()) || !node.getName().matches("handler\\$[a-z0-9]+\\$(?:cpm\\$)?" + java.util.regex.Pattern.quote(name))) return MatchResult.NONE;
        var merged = Annotations.getVisible(method, MixinMerged.class);
        return merged != null && mixin.equals(Annotations.getValue(merged, "mixin")) ? MatchResult.EXACT_MATCH : MatchResult.NONE;
    }
}

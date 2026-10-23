package com.naocraftlab.skins.buildlogic.probe;

import com.naocraftlab.skins.compat.cpm.MergedCpmSelector;

import java.lang.reflect.Proxy;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.injection.selectors.ElementNode;
import org.spongepowered.asm.mixin.injection.selectors.ISelectorContext;
import org.spongepowered.asm.mixin.injection.selectors.MatchResult;
import org.spongepowered.asm.mixin.refmap.IMixinContext;
import org.spongepowered.asm.mixin.transformer.meta.MixinMerged;
import org.spongepowered.asm.util.Annotations;

public final class CpmMergedSelectorProbe {
    private static final String OWNER = "net/minecraft/client/renderer/entity/layers/EquipmentLayerRenderer";
    private static final String MIXIN = "com.tom.cpm.mixin.render.EquipmentLayerRendererMixin";
    private static int assertions;

    public static void main(String[] args) throws Exception {
        String baseDescriptor = "(Ljava/lang/Object;Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V";
        String input = MIXIN + ";grabTexture;" + baseDescriptor + "|" + baseDescriptor.replace("CallbackInfo;)V", "CallbackInfo;Lcom/llamalad7/mixinextras/sugar/ref/LocalRef;)V");
        var mixinContext = (IMixinContext) Proxy.newProxyInstance(CpmMergedSelectorProbe.class.getClassLoader(), new Class<?>[]{IMixinContext.class},
            (proxy, method, values) -> method.getName().equals("getTargetClassRef") ? OWNER : null);
        var context = (ISelectorContext) Proxy.newProxyInstance(CpmMergedSelectorProbe.class.getClassLoader(), new Class<?>[]{ISelectorContext.class},
            (proxy, method, values) -> method.getName().equals("getMixin") ? mixinContext : null);
        var selector = MergedCpmSelector.parse(input, context);
        String[] descriptors = input.split(";", 3)[2].split("\\|", -1);
        check(descriptors.length == 2, "two explicitly declared descriptors");
        check(descriptors[1].equals(descriptors[0].replace("CallbackInfo;)V", "CallbackInfo;Lcom/llamalad7/mixinextras/sugar/ref/LocalRef;)V")), "only the pinned sugar parameter differs");
        for (String descriptor : descriptors) {
            checkMatch(selector, OWNER, "handler$abc000$grabTexture", descriptor, MIXIN, true);
            checkMatch(selector, OWNER, "handler$abc000$cpm$grabTexture", descriptor, MIXIN, true);
            checkMatch(selector, OWNER + "Other", "handler$abc000$cpm$grabTexture", descriptor, MIXIN, false);
            checkMatch(selector, OWNER, "handler$abc000$other$grabTexture", descriptor, MIXIN, false);
            checkMatch(selector, OWNER, "handler$abc000$cpm$otherTexture", descriptor, MIXIN, false);
            checkMatch(selector, OWNER, "handler$abc000$cpm$grabTexture", descriptor, MIXIN + "Other", false);
            checkMatch(selector, OWNER, "handler$abc000$cpm$grabTexture", descriptor, null, false);
        }
        checkMatch(selector, OWNER, "handler$abc000$cpm$grabTexture", descriptors[0].replace(")V", "I)V"), MIXIN, false);
        checkMatch(selector, OWNER, "handler$abc000$cpm$grabTexture", "()V", MIXIN, false);
        ClassNode host = new ClassNode(); host.name = OWNER;
        check(selector.match(ElementNode.of(host, new FieldNode(0, "handler$abc000$cpm$grabTexture", descriptors[0], null, null))) == MatchResult.NONE, "fields rejected");
        check(selector.getMinMatchCount() == 1 && selector.getMaxMatchCount() == 1, "single target required");
        var single = MergedCpmSelector.parse(MIXIN + ";grabTexture;" + descriptors[0], context);
        checkMatch(single, OWNER, "handler$abc000$cpm$grabTexture", descriptors[1], MIXIN, false);
        System.out.println("Exact merged selector: " + assertions + " assertions passed");
    }

    private static void checkMatch(MergedCpmSelector selector, String owner, String name, String descriptor, String provenance, boolean expected) {
        ClassNode host = new ClassNode(); host.name = owner;
        MethodNode method = new MethodNode(0, name, descriptor, null, null);
        if (provenance != null) Annotations.setVisible(method, MixinMerged.class, "mixin", provenance);
        check((selector.match(ElementNode.of(host, method)) == MatchResult.EXACT_MATCH) == expected, "exact owner/name/descriptor/provenance");
    }

    private static void check(boolean value, String label) {
        assertions++;
        if (!value) throw new AssertionError(label);
    }
}

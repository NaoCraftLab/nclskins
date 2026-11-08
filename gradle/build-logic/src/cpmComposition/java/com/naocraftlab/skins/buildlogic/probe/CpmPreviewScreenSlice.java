package com.naocraftlab.skins.buildlogic.probe;

import java.io.IOException;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

final class CpmPreviewScreenSlice {
    private static final List<String> OWNERS = List.of(
            "com.naocraftlab.skins.compat.gui.immediate.NclSkinsImmediateScreen",
            "com.naocraftlab.skins.compat.client.identifier.submission.NclSkinsScreen",
            "com.naocraftlab.skins.compat.client.identifier.extraction.NclSkinsScreen");

    static String availableOwner() {
        var present = OWNERS.stream().filter(name -> CpmPreviewScreenSlice.class.getClassLoader()
                .getResource(name.replace('.', '/') + ".class") != null).toList();
        if (present.size() != 1) throw new AssertionError("Expected exactly one target-compiled screen family: " + present);
        return present.get(0);
    }

    static Class<?> load(String owner) throws Exception {
        return load(owner, false);
    }

    static Class<?> load(String owner, boolean textConsumers) throws Exception {
        return load(owner, textConsumers, null);
    }

    static Class<?> loadNative(String owner, Class<?> renderer) throws Exception { return load(owner, false, renderer); }

    static Class<?> loadInput(String owner) throws Exception { return load(owner, false, null, true); }
    static Class<?> loadFocus(String owner) throws Exception { return load(owner, true, null, true); }
    static Class<?> loadRootPaint(String owner) throws Exception { return load(owner, true, null, false, false, true); }

    private static Class<?> load(String owner, boolean textConsumers, Class<?> rendererType) throws Exception {
        return load(owner, textConsumers, rendererType, false);
    }

    static Class<?> loadNativeLifecycle(String owner, Class<?> renderer) throws Exception {
        return load(owner, false, renderer, false, true);
    }

    private static Class<?> load(String owner, boolean textConsumers, Class<?> rendererType, boolean inputConsumers) throws Exception {
        return load(owner, textConsumers, rendererType, inputConsumers, false);
    }

    private static Class<?> load(String owner, boolean textConsumers, Class<?> rendererType, boolean inputConsumers, boolean lifecycleConsumers) throws Exception {
        return load(owner, textConsumers, rendererType, inputConsumers, lifecycleConsumers, false);
    }

    private static Class<?> load(String owner, boolean textConsumers, Class<?> rendererType, boolean inputConsumers, boolean lifecycleConsumers, boolean rootPaint) throws Exception {
        String internalOwner = owner.replace('.', '/');
        String namespace = internalOwner.substring(0, internalOwner.lastIndexOf('/') + 1);
        Map<String, String> names = new HashMap<>();
        names.put("net/minecraft/client/gui/GuiGraphics", internal(CpmOwnedPreviewDispatchProbe.Graphics.class));
        names.put("net/minecraft/client/gui/GuiGraphicsExtractor", internal(CpmOwnedPreviewDispatchProbe.Graphics.class));
        if (textConsumers) {
            names.put("net/minecraft/client/gui/components/events/GuiEventListener", internal(CpmOwnedPreviewDispatchProbe.InputListener.class));
            if (rootPaint) names.put("net/minecraft/client/gui/components/Renderable", internal(CpmOwnedPreviewDispatchProbe.RenderFrame.class));
            names.put("net/minecraft/resources/Identifier", internal(CpmOwnedPreviewDispatchProbe.Text.class));
            names.put("net/minecraft/client/gui/screens/Screen", internal(CpmOwnedPreviewDispatchProbe.ScreenTerminal.class));
            names.put("net/minecraft/client/Minecraft", internal(CpmOwnedPreviewDispatchProbe.ClientTerminal.class));
            names.put("net/minecraft/client/gui/Font", internal(CpmOwnedPreviewDispatchProbe.FontTerminal.class));
            names.put("net/minecraft/client/gui/components/AbstractWidget", internal(CpmOwnedPreviewDispatchProbe.WidgetTerminal.class));
            for (String type : List.of("net/minecraft/network/chat/Component", "net/minecraft/network/chat/FormattedText", "net/minecraft/util/FormattedCharSequence")) {
                names.put(type, internal(CpmOwnedPreviewDispatchProbe.TextValue.class));
            }
            names.put("net/minecraft/network/chat/MutableComponent", internal(CpmOwnedPreviewDispatchProbe.Text.class));
            names.put(namespace + "ExtractionGuiApi", internal(CpmOwnedPreviewDispatchProbe.GuiTerminal.class));
        }
        names.put("com/naocraftlab/skins/runtime/ClientRuntime", internal(CpmOwnedPreviewDispatchProbe.RuntimeBoundary.class));
        names.put("net/minecraft/resources/Identifier", internal(CpmOwnedPreviewDispatchProbe.Text.class));
        names.put("net/minecraft/resources/ResourceLocation", internal(CpmOwnedPreviewDispatchProbe.Text.class));
        names.put("com/mojang/blaze3d/pipeline/RenderPipeline", "java/lang/Object");
        names.put("com/mojang/renderpearl/api/pipeline/RenderPipeline", "java/lang/Object");
        names.put("net/minecraft/client/renderer/RenderPipelines", internal(CpmOwnedPreviewDispatchProbe.PipelinesTerminal.class));
        if (inputConsumers) {
            names.remove("com/naocraftlab/skins/runtime/ClientRuntime");
            names.put("net/minecraft/client/input/KeyEvent", internal(CpmOwnedPreviewDispatchProbe.KeyEvent.class));
            names.put("net/minecraft/client/Minecraft", internal(CpmOwnedPreviewDispatchProbe.ClientTerminal.class));
            names.put("net/minecraft/client/gui/Font", internal(CpmOwnedPreviewDispatchProbe.FontTerminal.class));
            names.put(namespace + "SubmissionComponents", internal(CpmOwnedPreviewDispatchProbe.TextComponents.class));
            if (!textConsumers) names.put(internalOwner + "$ActionButton", internal(CpmOwnedPreviewDispatchProbe.WidgetTerminal.class));
            if (textConsumers && !owner.contains("submission")) names.put("net/minecraft/client/gui/components/AbstractButton", internal(CpmOwnedPreviewDispatchProbe.WidgetTerminal.class));
            if (textConsumers) {
                names.put("net/minecraft/client/gui/components/AbstractWidget$WithInactiveMessage", internal(CpmOwnedPreviewDispatchProbe.WidgetTerminal.class));
                names.put("net/minecraft/client/gui/ActiveTextCollector", internal(CpmOwnedPreviewDispatchProbe.ActiveTextTerminal.class));
                names.put("net/minecraft/client/gui/GuiGraphics$HoveredTextEffects", internal(CpmOwnedPreviewDispatchProbe.HoveredTextEffects.class));
            }
            names.put("net/minecraft/client/gui/components/Tooltip", internal(CpmPreviewScopeProbe.FrameTooltip.class));
            for (String text : List.of("Component", "MutableComponent", "FormattedText"))
                names.put("net/minecraft/network/chat/" + text, internal(CpmOwnedPreviewDispatchProbe.TextValue.class));
            if (textConsumers) names.put("net/minecraft/network/chat/MutableComponent", internal(CpmOwnedPreviewDispatchProbe.Text.class));
            names.put("net/minecraft/client/gui/screens/Screen", internal(CpmOwnedPreviewDispatchProbe.ScreenTerminal.class));
            names.put("net/minecraft/client/gui/components/events/GuiEventListener", internal(CpmOwnedPreviewDispatchProbe.InputListener.class));
            names.put("net/minecraft/client/gui/components/AbstractWidget", internal(CpmOwnedPreviewDispatchProbe.WidgetTerminal.class));
            names.put("net/minecraft/client/gui/components/EditBox", internal(CpmOwnedPreviewDispatchProbe.FieldTerminal.class));
            names.put("net/minecraft/client/input/MouseButtonEvent", internal(CpmOwnedPreviewDispatchProbe.MouseEvent.class));
            names.put("net/minecraft/client/input/InputWithModifiers", internal(CpmOwnedPreviewDispatchProbe.InputListener.class));
            names.put(namespace + "SubmissionScrollController", internal(CpmOwnedPreviewDispatchProbe.ScrollTerminal.class));
            names.put(namespace + "ExtractionScrollController", internal(CpmOwnedPreviewDispatchProbe.ScrollTerminal.class));
            names.put("com/naocraftlab/skins/compat/gui/immediate/NativeScrollController", internal(CpmOwnedPreviewDispatchProbe.ScrollPort.class));
        }
        if (lifecycleConsumers) {
            if (owner.endsWith("NclSkinsImmediateScreen")) {
                names.put("com/naocraftlab/skins/compat/gui/immediate/NativeScrollController", internal(CpmOwnedPreviewDispatchProbe.ScrollPort.class));
                names.put(internalOwner + "$NativeTabGroup", internal(CpmPreviewScopeProbe.FrameTab.class));
                names.put("net/minecraft/client/gui/components/tabs/TabNavigationBar", internal(CpmPreviewScopeProbe.FrameWidget.class));
                names.put("net/minecraft/client/gui/components/Tooltip", internal(CpmPreviewScopeProbe.FrameTooltip.class));
                for (String widget : List.of("CapeCardWidget", "CollectionHeaderWidget", "IconButtonWidget", "PassiveIndicatorWidget"))
                    names.put(internalOwner + "$" + widget, internal(CpmPreviewScopeProbe.FrameWidget.class));
            }
            names.put("net/minecraft/client/gui/screens/Screen", internal(CpmOwnedPreviewDispatchProbe.ScreenTerminal.class));
            names.put("net/minecraft/client/Minecraft", internal(CpmOwnedPreviewDispatchProbe.ClientTerminal.class));
            names.put("net/minecraft/client/gui/Font", internal(CpmOwnedPreviewDispatchProbe.FontTerminal.class));
            names.put("net/minecraft/client/gui/components/AbstractWidget", internal(CpmOwnedPreviewDispatchProbe.WidgetTerminal.class));
            names.put("net/minecraft/client/gui/components/EditBox", internal(CpmOwnedPreviewDispatchProbe.FieldTerminal.class));
            names.put("net/minecraft/client/gui/components/events/GuiEventListener", internal(CpmOwnedPreviewDispatchProbe.InputListener.class));
            names.put(namespace + "ExtractionGuiApi", internal(CpmOwnedPreviewDispatchProbe.GuiTerminal.class));
            names.put(namespace + "ExtractionScrollController", internal(CpmOwnedPreviewDispatchProbe.ScrollTerminal.class));
            names.put(namespace + "SubmissionScrollController", internal(CpmOwnedPreviewDispatchProbe.ScrollTerminal.class));
            names.put("net/minecraft/client/gui/components/Tooltip",internal(CpmPreviewScopeProbe.FrameTooltip.class));
            names.put(namespace + "SubmissionComponents", internal(CpmOwnedPreviewDispatchProbe.TextComponents.class));
            names.put(internalOwner + "$ActionButton", internal(CpmPreviewScopeProbe.FrameWidget.class));
            names.put("net/minecraft/client/gui/components/Renderable", internal(CpmOwnedPreviewDispatchProbe.RenderFrame.class));
            names.put(namespace + "NativeSurfaceAdapter", internal(CpmBakedDisplayProbe.FrameSurface.class));
            names.put(namespace + "MinecraftClientComponents", internal(CpmOwnedPreviewDispatchProbe.TextComponents.class));
            for (String type : List.of("Component", "FormattedText", "MutableComponent"))
                names.put("net/minecraft/network/chat/" + type, internal(type.equals("MutableComponent") && owner.endsWith("NclSkinsImmediateScreen")
                        ? CpmOwnedPreviewDispatchProbe.Text.class : CpmOwnedPreviewDispatchProbe.TextValue.class));
        }
        names.put("com/naocraftlab/skins/compat/gui/immediate/ImmediateScreenCapabilities", internal(CpmOwnedPreviewDispatchProbe.Capabilities.class));
        names.put("com/naocraftlab/skins/compat/client/identifier/IdentifierTextureRegistry", internal(CpmOwnedPreviewDispatchProbe.Registry.class));
        for (String renderer : List.of("SubmissionPreviewRenderer", "SimplePreviewRenderer", "AvatarRenderStatePreviewRenderer", "AvatarPipPreviewRenderer")) {
            names.put(namespace + renderer, internal(CpmOwnedPreviewDispatchProbe.RenderTerminal.class));
        }
        if (rendererType != null) {
            names.remove("com/naocraftlab/skins/runtime/ClientRuntime");
            if (owner.endsWith("NclSkinsImmediateScreen"))
                names.put("net/minecraft/client/gui/GuiGraphics", internal(CpmPreviewScopeProbe.Graphics.class));
            names.put("net/minecraft/client/gui/GuiGraphicsExtractor", internal(CpmExtractionBodyProbe.Graphics.class));
            if (namespace.contains("submission")) {
                names.put("net/minecraft/client/gui/GuiGraphics", internal(CpmBakedDisplayProbe.Graphics.class));
                names.remove(namespace + "SubmissionPreviewRenderer");
            }
            names.remove(namespace + "AvatarRenderStatePreviewRenderer");
            names.put(namespace + "AvatarPipPreviewRenderer", internal(CpmExtractionBodyProbe.Baked.class));
        }
        ClassLoader loader = new ClassLoader(rendererType == null ? CpmPreviewScreenSlice.class.getClassLoader() : rendererType.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                boolean components = textConsumers && (name.equals(namespace.replace('/', '.') + "SubmissionComponents")
                        || name.equals(namespace.replace('/', '.') + "MinecraftClientComponents"))
                        || (inputConsumers || lifecycleConsumers) && name.equals(namespace.replace('/', '.') + "ExtractionInputAdapter");
                boolean nativeButton = textConsumers && inputConsumers && (name.equals("net.minecraft.client.gui.components.AbstractButton")
                        || name.equals("net.minecraft.client.gui.components.WidgetSprites"));
                if (!name.equals(owner) && !name.startsWith(owner + "$") && !components && !nativeButton) return super.loadClass(name, resolve);
                Class<?> type = findLoadedClass(name);
                if (type == null) {
                    try (var input = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                        if (input == null) throw new ClassNotFoundException(name);
                        var node = new ClassNode(Opcodes.ASM9);
                        new ClassReader(input).accept(node, 0);
                        node = namedForgeScreen(node);
                        if (nativeButton && name.endsWith("AbstractButton")) node.methods.removeIf(method -> !List.of("<init>","<clinit>",
                                "renderDefaultSprite","renderDefaultLabel","onClick","onPress","renderContents","setOverrideRenderHighlightedSprite").contains(method.name));
                        if (textConsumers && inputConsumers && List.of("CatalogCardWidget","CapeCardWidget","IconButtonWidget","PassiveIndicatorWidget").stream().anyMatch(s->name.equals(owner+"$"+s)) && !owner.contains("submission"))
                            node.methods.removeIf(method -> !List.of("<init>","<clinit>","renderWidget","extractContents").contains(method.name));
                        if (name.equals(owner)) {
                            verifyRetirementWiring(node);
                            verifyPaintOrdering(node);
                            if (rootPaint) rootPaintSlice(node);
                            isolate(node, textConsumers, inputConsumers, lifecycleConsumers);
                        }
                        if (textConsumers && inputConsumers) node.innerClasses.removeIf(info -> names.containsKey(info.name)
                                && names.get(info.name).equals(names.get(info.outerName)));
                        var writer = new ClassWriter(rootPaint ? ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS : 0);
                        node.accept(new ClassRemapper(writer, new Remapper(Opcodes.ASM9) {
                            @Override public String map(String value) { return names.getOrDefault(value, value); }
                            @Override public String mapMethodName(String owner, String name, String descriptor) {
                                return textConsumers && owner.equals("net/minecraft/client/gui/GuiGraphics")
                                        && name.equals("drawString") && descriptor.endsWith(")I") ? "drawStringResult" : name;
                            }
                        }));
                        byte[] bytes = writer.toByteArray();
                        if (lifecycleConsumers && name.equals(owner) && (owner.endsWith("NclSkinsImmediateScreen") || owner.contains("submission"))) {
                            var terminalNode = new ClassNode(); new ClassReader(bytes).accept(terminalNode, 0);
                            var missing = new java.util.TreeSet<String>();
                            for (var method : terminalNode.methods) for (var instruction : method.instructions) {
                                if (!(instruction instanceof MethodInsnNode call) || !call.owner.startsWith("com/naocraftlab/skins/buildlogic/probe/")) continue;
                                try {
                                    Class<?> terminal = Class.forName(call.owner.replace('/', '.'));
                                    boolean found = call.name.equals("<init>") ? java.util.Arrays.stream(terminal.getDeclaredConstructors())
                                            .anyMatch(c -> org.objectweb.asm.Type.getConstructorDescriptor(c).equals(call.desc))
                                            : java.util.Arrays.stream(terminal.getMethods()).anyMatch(m -> m.getName().equals(call.name) && org.objectweb.asm.Type.getMethodDescriptor(m).equals(call.desc));
                                    if (!found) missing.add(call.owner + "." + call.name + call.desc);
                                } catch (ClassNotFoundException failure) { missing.add(call.owner); }
                            }
                            if (!missing.isEmpty()) throw new AssertionError("Unbound frame terminal closure: " + missing);
                        }
                        type = defineClass(name, bytes, 0, bytes.length);
                    } catch (IOException failure) { throw new ClassNotFoundException(name, failure); }
                }
                if (resolve) resolveClass(type);
                return type;
            }
        };
        return loader.loadClass(owner);
    }

    static Class<?> loadSurface(String owner) throws Exception {
        String name = owner.substring(0, owner.lastIndexOf('.') + 1) + "NativeSurfaceAdapter";
        Map<String,String> names = new HashMap<>();
        names.put("net/minecraft/client/gui/GuiGraphics", internal(CpmOwnedPreviewDispatchProbe.Graphics.class));
        names.put("net/minecraft/client/gui/GuiGraphicsExtractor", internal(CpmOwnedPreviewDispatchProbe.Graphics.class));
        names.put("net/minecraft/client/Minecraft", internal(CpmOwnedPreviewDispatchProbe.ClientTerminal.class));
        names.put("net/minecraft/client/gui/screens/Screen", internal(CpmOwnedPreviewDispatchProbe.ScreenTerminal.class));
        names.put("net/minecraft/resources/Identifier", internal(CpmOwnedPreviewDispatchProbe.Text.class));
        names.put("com/mojang/blaze3d/pipeline/RenderPipeline", "java/lang/Object");
        names.put("com/mojang/renderpearl/api/pipeline/RenderPipeline", "java/lang/Object");
        names.put("net/minecraft/client/multiplayer/ClientLevel", "java/lang/Object");
        names.put("net/minecraft/client/renderer/RenderPipelines", internal(CpmOwnedPreviewDispatchProbe.PipelinesTerminal.class));
        var node = new ClassNode(Opcodes.ASM9);
        try (var input = CpmPreviewScreenSlice.class.getClassLoader().getResourceAsStream(name.replace('.', '/') + ".class")) {
            if (input == null) throw new AssertionError("Native surface class absent: " + name);
            new ClassReader(input).accept(node,0);
        }
        String root = owner.contains("submission") ? "renderFramePanels" : "drawFrameBackgrounds";
        node.methods.removeIf(method -> !List.of(root,"blitSeparator","<clinit>").contains(method.name) && !method.name.startsWith("lambda$"+root+"$"));
        node.access = Opcodes.ACC_PUBLIC;
        node.methods.forEach(method -> method.access = (method.access & ~Opcodes.ACC_PRIVATE) | Opcodes.ACC_PUBLIC);
        var writer = new ClassWriter(0);
        node.accept(new ClassRemapper(writer, new Remapper(Opcodes.ASM9) {
            @Override public String map(String value) { return names.getOrDefault(value,value); }
        }));
        byte[] bytes = writer.toByteArray();
        return new ClassLoader(CpmPreviewScreenSlice.class.getClassLoader()) {
            Class<?> define() { return defineClass(name,bytes,0,bytes.length); }
        }.define();
    }

    private static void isolate(ClassNode node, boolean textConsumers, boolean inputConsumers, boolean lifecycleConsumers) {
        boolean immediate = node.name.endsWith("NclSkinsImmediateScreen");
        List<String> roots = textConsumers ? List.of("getNarrationMessage", immediate || node.name.contains("submission") ? "renderText" : "drawText", "renderSurfaceOverlays", "renderStateFrames", immediate || node.name.contains("submission") ? "renderPassiveIndicatorTooltip" : "drawPassiveIndicatorTooltip")
                : immediate ? List.of("synchronizePreviews", "renderPreview", "releasePreviews")
                : node.name.contains("submission") ? List.of("synchronizePreviewAssets", "renderPreviews", "releasePreviewBody", "releasePreviewAccessories")
                : List.of("syncPreviewAssets", "drawPreviews", "releasePreviewBody", "releasePreviewAccessories");
        if (node.methods.stream().anyMatch(m -> m.name.equals("nclskins$paintRoot"))) roots = List.of("nclskins$paintRoot");
        if (!textConsumers) roots = new java.util.ArrayList<>(roots);
        if (!textConsumers) roots.add("renderStaticPreviews");
        if (!textConsumers) roots.add(immediate ? "renderBackEquipmentPreviews" : node.name.contains("submission") ? "renderBackEquipment" : "drawBackEquipmentPreviews");
        if (lifecycleConsumers) {
            roots.addAll(immediate || node.name.contains("submission") ? List.of("render", "snapshotChanged") : List.of("init", "snapshotChanged", "clearWidgets", "setInitialFocus"));
            if (node.name.contains("submission")) for (var method : node.methods) {
                if (List.of("renderListPanels","renderCardBackgrounds","renderBackEquipment","renderScrollbar","renderSurfaceOverlays",
                        "renderProgressDecorations","renderIconDecorations","renderTabHighlights","renderTexts","renderStateFrames","renderTooltips","renderPassiveIndicatorTooltip").contains(method.name)) {
                    method.instructions.clear();method.tryCatchBlocks.clear();method.localVariables=null;
                    if (org.objectweb.asm.Type.getReturnType(method.desc).getSort()==org.objectweb.asm.Type.BOOLEAN)method.visitInsn(Opcodes.ICONST_0);
                    method.visitInsn(org.objectweb.asm.Type.getReturnType(method.desc).getSort()==org.objectweb.asm.Type.BOOLEAN ? Opcodes.IRETURN : Opcodes.RETURN);method.maxStack=1;
                }
                if (method.name.equals("createWidget")) {
                    method.instructions.clear();method.tryCatchBlocks.clear();method.localVariables=null;
                    method.visitVarInsn(Opcodes.ALOAD,1);method.visitMethodInsn(Opcodes.INVOKESTATIC,internal(CpmPreviewScopeProbe.class),"frameWidget",
                            "(Lcom/naocraftlab/skins/runtime/ViewSpec$Widget;)L"+internal(CpmOwnedPreviewDispatchProbe.WidgetTerminal.class)+";",false);
                    method.visitInsn(Opcodes.ARETURN);method.maxStack=1;
                }
            }
            if (immediate) {
                for (var method : node.methods) {
                    if (List.of("renderEpochBackground", "renderCardBackgrounds", "renderStaticPreviews", "renderBackEquipmentPreviews",
                            "renderScrollbar", "renderSurfaceOverlays", "renderProgressDecorations", "renderIconDecorations",
                            "renderTabHighlights", "renderText", "renderStateFrames", "renderPreciseTooltip", "renderPassiveIndicatorTooltip",
                            "consumeFocusRequest").contains(method.name)) {
                        method.instructions.clear(); method.tryCatchBlocks.clear(); method.localVariables = null;
                        method.instructions.add(new InsnNode(Opcodes.RETURN)); method.maxStack = 0;
                        method.access &= ~(Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE);
                        method.maxLocals = 1 + java.util.Arrays.stream(org.objectweb.asm.Type.getArgumentTypes(method.desc)).mapToInt(org.objectweb.asm.Type::getSize).sum();
                    }
                    if (method.name.equals("createWidget")) {
                        method.instructions.clear(); method.tryCatchBlocks.clear(); method.localVariables = null;
                        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
                        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, internal(CpmPreviewScopeProbe.class), "frameWidget",
                                "(Lcom/naocraftlab/skins/runtime/ViewSpec$Widget;)L" + internal(CpmOwnedPreviewDispatchProbe.WidgetTerminal.class) + ";", false));
                        method.instructions.add(new InsnNode(Opcodes.ARETURN)); method.maxStack = 1;
                    }
                    if (method.name.equals("createTabGroup")) {
                        method.instructions.clear(); method.tryCatchBlocks.clear(); method.localVariables = null;
                        method.instructions.add(new org.objectweb.asm.tree.TypeInsnNode(Opcodes.NEW, "java/lang/AssertionError"));
                        method.instructions.add(new InsnNode(Opcodes.DUP));
                        method.instructions.add(new org.objectweb.asm.tree.LdcInsnNode("Gallery frame unexpectedly constructs horizontal tabs"));
                        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,"java/lang/AssertionError","<init>","(Ljava/lang/Object;)V",false));
                        method.instructions.add(new InsnNode(Opcodes.ATHROW)); method.maxStack = 3;
                    }
                }
            }
            for (var method : node.methods) {
                boolean widgets = List.of("addNativeWidgets", "addNativeTabGroups", "syncNativeWidgetState", "syncNativeTabState").contains(method.name);
                boolean excludedLink = List.of("showOptiFineLink", "showSneakyEditorLink", "showFixedAccountLink").contains(method.name);
                if (!widgets && !excludedLink) continue;
                method.instructions.clear(); method.tryCatchBlocks.clear(); method.localVariables = null;
                if (excludedLink) {
                    method.instructions.add(new org.objectweb.asm.tree.TypeInsnNode(Opcodes.NEW, "java/lang/AssertionError"));
                    method.instructions.add(new InsnNode(Opcodes.DUP));
                    method.instructions.add(new org.objectweb.asm.tree.LdcInsnNode("Metadata component cannot open native link UI"));
                    method.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,"java/lang/AssertionError","<init>","(Ljava/lang/Object;)V",false));
                    method.instructions.add(new InsnNode(Opcodes.ATHROW)); method.maxStack = 3;
                } else {
                    if (method.name.equals("addNativeWidgets")) {
                        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0)); method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
                        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/client/gui/screens/Screen", "constructWidgets", "(Lcom/naocraftlab/skins/runtime/ViewSpec;)V", false));
                    }
                    method.instructions.add(new InsnNode(Opcodes.RETURN)); method.maxStack = 2;
                }
            }
        }
        if (inputConsumers) {
            scrollFrameSlice(node);
            roots = new java.util.ArrayList<>(List.of("mouseClicked", "mouseDragged", "mouseReleased", immediate ? "forwardScroll" : "mouseScrolled",
                    immediate || node.name.contains("submission") ? "renderScrollbar" : "drawScrollbar", "nclskins$syncScroll"));
            if (textConsumers) {
                roots.addAll(List.of("renderSurfaceOverlays", "renderStateFrames"));
                if (node.name.contains("submission")) roots.addAll(List.of("iconTexture", "drawCardFocusFrame", "renderTabHighlights"));
                else if(immediate) roots.addAll(List.of("drawCardFocusFrame","renderActionIcon"));
                else roots.addAll(List.of("extractCardFocusFrame","extractActionIcon"));
            }
            if (node.name.contains("submission")) {
                roots.addAll(List.of("keyPressed", "applyFocusRequest", "rebuildNativeWidgets"));
                for (var method : node.methods) if (method.name.equals("createWidget")) {
                    method.instructions.clear(); method.tryCatchBlocks.clear(); method.localVariables = null;
                    if(textConsumers) method.visitVarInsn(Opcodes.ALOAD,0);
                    method.visitVarInsn(Opcodes.ALOAD,1);
                    method.visitMethodInsn(Opcodes.INVOKESTATIC,internal(CpmOwnedPreviewDispatchProbe.class),textConsumers?"navigationFocusWidget":"navigationWidget",
                            (textConsumers?"(Ljava/lang/Object;":"(")+"Lcom/naocraftlab/skins/runtime/ViewSpec$Widget;)L"+internal(CpmOwnedPreviewDispatchProbe.WidgetTerminal.class)+";",false);
                    method.visitInsn(Opcodes.ARETURN); method.maxStack=2;
                }
            }
        }
        if (inputConsumers) node.methods.stream().filter(method -> method.name.equals("refresh")).forEach(method -> {
            method.instructions.clear();
            method.tryCatchBlocks.clear();
            method.localVariables = null;
            method.instructions.add(new org.objectweb.asm.tree.TypeInsnNode(Opcodes.NEW,"java/lang/AssertionError"));
            method.instructions.add(new InsnNode(Opcodes.DUP));
            method.instructions.add(new org.objectweb.asm.tree.LdcInsnNode("Input component cannot enter native widget reconstruction"));
            method.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,"java/lang/AssertionError","<init>","(Ljava/lang/Object;)V",false));
            method.instructions.add(new InsnNode(Opcodes.ATHROW));
            method.maxStack=3;
            method.maxLocals=1;
        });
        Map<String, MethodNode> methods = new HashMap<>();
        for (MethodNode method : node.methods) methods.put(method.name + method.desc, method);
        Set<String> retained = new HashSet<>(), fields = new HashSet<>();
        var pending = new ArrayDeque<String>();
        for (String root : roots) {
            var matches = node.methods.stream().filter(method -> method.name.equals(root)).toList();
            boolean iconOverloads=root.equals("renderActionIcon") || root.equals("extractActionIcon");
            if (matches.isEmpty() || !iconOverloads && matches.size()!=1) throw new AssertionError("Exact screen seam absent/ambiguous: " + root);
            for(var match:matches) pending.add(match.name + match.desc);
        }
        while (!pending.isEmpty()) {
            String identity = pending.remove();
            if (!retained.add(identity)) continue;
            MethodNode method = methods.get(identity);
            if (method == null) throw new AssertionError("Missing own compiled helper: " + identity);
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && call.owner.equals(node.name)) {
                    if (methods.containsKey(call.name + call.desc)) pending.add(call.name + call.desc);
                    else if (!(textConsumers && call.name.equals("getFocused") && call.desc.equals("()Lnet/minecraft/client/gui/components/events/GuiEventListener;"))
                            && (!(inputConsumers || lifecycleConsumers) || !List.of("getFocused", "setFocused", "setDragging", "isDragging", "hasShiftDown", "rebuildWidgets", "triggerImmediateNarration", "clearWidgets", "clearFocus", "setInitialFocus", "addRenderableWidget").contains(call.name)))
                        throw new AssertionError("Unbound inherited screen helper: " + call.name + call.desc);
                } else if (instruction instanceof FieldInsnNode field && field.owner.equals(node.name)) {
                    fields.add(field.name);
                } else if (instruction instanceof InvokeDynamicInsnNode dynamic) {
                    for (Object argument : dynamic.bsmArgs) {
                        if (argument instanceof Handle handle && handle.getOwner().equals(node.name)) {
                            pending.add(handle.getName() + handle.getDesc());
                        }
                    }
                }
            }
        }
        if (immediate) fields.add("capabilities");
        if (!textConsumers && !inputConsumers && !lifecycleConsumers) for (String field : List.of("width","height")) {
            if (fields.contains(field) && node.fields.stream().noneMatch(value -> value.name.equals(field)))
                node.fields.add(new org.objectweb.asm.tree.FieldNode(Opcodes.ACC_PUBLIC,field,"I",null,null));
        }
        node.methods.removeIf(method -> !retained.contains(method.name + method.desc));
        node.fields.removeIf(field -> !fields.contains(field.name));
        node.fields.forEach(field -> field.access = (field.access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED | Opcodes.ACC_FINAL)) | Opcodes.ACC_PUBLIC);
        node.superName = textConsumers || inputConsumers || lifecycleConsumers ? "net/minecraft/client/gui/screens/Screen" : "java/lang/Object";
        node.interfaces.clear();
        node.signature = null;
        node.access = (node.access & ~(Opcodes.ACC_ABSTRACT | Opcodes.ACC_FINAL)) | Opcodes.ACC_PUBLIC;
        var constructor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, node.superName, "<init>", "()V", false));
        constructor.instructions.add(new InsnNode(Opcodes.RETURN));
        constructor.maxStack = 1;
        constructor.maxLocals = 1;
        node.methods.add(constructor);
        long branches = node.methods.stream().flatMap(method -> java.util.stream.StreamSupport.stream(method.instructions.spliterator(), false))
                .filter(instruction -> instruction instanceof MethodInsnNode call && call.owner.equals("com/naocraftlab/skins/runtime/ViewSpec$Preview") && call.name.equals("requiresLoadedSkin")).count();
        long expectedBranches = 3;
        if (!textConsumers && !inputConsumers && branches != expectedBranches) throw new AssertionError("Production loaded/borrowed predicates must remain executable: " + branches);
    }

    private static void scrollFrameSlice(ClassNode node) {
        String name=node.name.endsWith("NclSkinsImmediateScreen") ? "render" : node.name.contains("submission") ? "refresh" : "extractRenderState";
        var parent=node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
        MethodInsnNode terminal=null;
        for (var insn:parent.instructions) if (insn instanceof MethodInsnNode call && call.name.equals("synchronize")
                && call.desc.equals("(Ljava/util/Optional;)V")) { terminal=call; break; }
        if (terminal==null) throw new AssertionError("Actual frame scroll binding absent");
        var code=new java.util.ArrayList<org.objectweb.asm.tree.AbstractInsnNode>();
        for (org.objectweb.asm.tree.AbstractInsnNode insn=terminal;insn!=null && code.size()<9;insn=insn.getPrevious()) if(insn.getOpcode()>=0) code.add(0,insn);
        boolean fieldView=node.name.contains("submission"), port=node.name.endsWith("NclSkinsImmediateScreen");
        if(fieldView) {
            if(code.size()!=9 || !(code.get(3) instanceof FieldInsnNode view) || !view.name.equals("view")
                    || !view.desc.equals("Lcom/naocraftlab/skins/runtime/ViewSpec;")) throw new AssertionError("Exact submission frame view field");
            code.remove(3);
        } else if(code.size()==9) code.remove(0);
        int invoke=port ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL;
        int[] expected={Opcodes.ALOAD,Opcodes.GETFIELD,Opcodes.ALOAD,Opcodes.ALOAD,Opcodes.GETFIELD,invoke,Opcodes.INVOKESTATIC,invoke};
        if(code.size()!=expected.length) throw new AssertionError("Exact frame binding length");
        for(int i=0;i<expected.length;i++) if(code.get(i).getOpcode()!=expected[i]) throw new AssertionError("Exact frame binding operand producers: "+name+":"+i);
        if (!((MethodInsnNode)code.get(5)).name.equals("surfaceId") || !((MethodInsnNode)code.get(6)).name.equals("nativeScrollSurface"))
            throw new AssertionError("Actual selected-surface policy binding absent");
        var slice=new MethodNode(Opcodes.ACC_PUBLIC,"nclskins$syncScroll","(Lcom/naocraftlab/skins/runtime/ViewSpec;)V",null,null);
        for(int i=0;i<code.size();i++) {
            var copy=code.get(i).clone(new HashMap<>());
            if(i==2) ((VarInsnNode)copy).var=1;
            slice.instructions.add(copy);
        }
        slice.instructions.add(new InsnNode(Opcodes.RETURN));slice.maxStack=4;slice.maxLocals=2;node.methods.add(slice);
        System.out.println("BOUND actual compiled selected-scroll frame binding "+node.name+"."+name);
    }

    private static void rootPaintSlice(ClassNode node) {
        boolean immediate = node.name.endsWith("NclSkinsImmediateScreen");
        var root = node.methods.stream().filter(m -> m.name.equals(immediate || node.name.contains("submission") ? "render" : "extractRenderState")
                && m.desc.endsWith(";IIF)V")).findFirst().orElseThrow();
        var overlays = java.util.stream.StreamSupport.stream(root.instructions.spliterator(),false)
                .filter(i -> i instanceof MethodInsnNode call && call.owner.equals(node.name) && call.name.equals("renderSurfaceOverlays")).toList();
        if (overlays.size()!=2) throw new AssertionError("Exact root Gallery/non-Gallery overlay branches");
        var first = overlays.get(0);
        var viewLoad = first.getPrevious();
        while (!(viewLoad instanceof VarInsnNode)) viewLoad = viewLoad.getPrevious();
        int view = ((VarInsnNode)viewLoad).var;
        org.objectweb.asm.tree.AbstractInsnNode start = first;
        while (!(start instanceof org.objectweb.asm.tree.LdcInsnNode ldc && ldc.cst.equals("gallery"))) start = start.getPrevious();
        if (immediate) {
            while (!(start instanceof MethodInsnNode call && call.name.equals("pointerOwnerAt"))) start = start.getPrevious();
            var pointer = (MethodInsnNode)start;
            if (pointer.getOpcode()!=Opcodes.INVOKESTATIC || !pointer.owner.equals(node.name)
                    || !pointer.desc.equals("(Lcom/naocraftlab/skins/runtime/ViewSpec;DD)Ljava/util/Optional;"))
                throw new AssertionError("Exact immediate pointer lookup signature");
            int[] producers = {Opcodes.I2D,Opcodes.ILOAD,Opcodes.I2D,Opcodes.ILOAD,Opcodes.ALOAD};
            int[] locals = {-1,3,-1,2,view};
            for (int i=0;i<producers.length;i++) {
                do { start=start.getPrevious(); } while(start!=null && start.getOpcode()<0);
                if (start==null || start.getOpcode()!=producers[i]
                        || locals[i]>=0 && ((VarInsnNode)start).var!=locals[i])
                    throw new AssertionError("Exact immediate pointer argument producers");
            }
        }
        var end = overlays.get(1);
        while (!(end instanceof MethodInsnNode call && (call.name.equals("renderProgressDecorations") || call.name.equals("drawProgressDecorations")))) end=end.getNext();
        for(int i=0;i<3;i++) { do { end=end.getPrevious(); } while(end.getOpcode()<0); }
        var labels = new HashMap<org.objectweb.asm.tree.LabelNode,org.objectweb.asm.tree.LabelNode>();
        for(var instruction:root.instructions) if(instruction instanceof org.objectweb.asm.tree.LabelNode label) labels.put(label,new org.objectweb.asm.tree.LabelNode());
        var slice = new MethodNode(Opcodes.ACC_PUBLIC,"nclskins$paintRoot",root.desc.replace(")V","Lcom/naocraftlab/skins/runtime/ViewSpec;)V"),null,null);
        slice.instructions.add(new VarInsnNode(Opcodes.ALOAD,5));slice.instructions.add(new VarInsnNode(Opcodes.ASTORE,view));
        for(var instruction=start;instruction!=end;instruction=instruction.getNext()) {
            if(instruction==null)throw new AssertionError("Root paint phase end absent");
            if(!(instruction instanceof org.objectweb.asm.tree.FrameNode) && !(instruction instanceof org.objectweb.asm.tree.LineNumberNode)) slice.instructions.add(instruction.clone(labels));
        }
        slice.instructions.add(new InsnNode(Opcodes.RETURN));slice.maxLocals=root.maxLocals;slice.maxStack=root.maxStack;
        node.methods.add(slice);
        System.out.println("BOUND actual compiled root paint phase " + node.name + "." + root.name + root.desc + " viewLocal=" + view);
    }

    private static void verifyPaintOrdering(ClassNode node) {
        boolean immediate = node.name.endsWith("NclSkinsImmediateScreen");
        boolean submission = node.name.contains("submission");
        String entry = immediate || submission ? "render" : "extractRenderState";
        String preview = immediate || submission ? "renderPreviews" : "drawPreviews";
        String reconcile = immediate ? "synchronizeWidgets" : submission ? "refresh" : "snapshotChanged";
        String controls = immediate ? "pointerOwnerAt" : submission ? "renderWidgetsClipped" : "extractRenderablesClipped";
        var entries = node.methods.stream().filter(candidate -> candidate.name.equals(entry)).toList();
        if (entries.size() != 1) throw new AssertionError("Expected one exact native paint entrypoint: " + node.name);
        var method = entries.get(0);
        int previewAt = -1, reconcileAt = -1, controlsAt = -1, index = 0;
        int workspaceAt = -1, staticAt = -1;
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(node.name)) {
                if (call.name.equals(preview) && previewAt < 0) previewAt = index;
                if (previewAt >= 0 && (call.name.equals("drawListPanels") || call.name.equals("renderListPanels") || call.name.equals("renderCardBackgrounds")) && workspaceAt < 0) workspaceAt = index;
                if (call.name.equals("renderStaticPreviews")) staticAt = index;
                if (previewAt >= 0 && call.name.equals(reconcile) && reconcileAt < 0) reconcileAt = index;
                if (call.name.equals(controls) && controlsAt < 0) controlsAt = index;
            }
            index++;
        }
        if (!(previewAt >= 0 && workspaceAt > previewAt && staticAt > workspaceAt && controlsAt > staticAt))
            throw new AssertionError("Compiled Gallery body -> workspace -> static cards -> chrome order: " + node.name);
        if (!(previewAt >= 0 && reconcileAt > previewAt && controlsAt > reconcileAt)) {
            throw new AssertionError("Actual compiled paint must reconcile native widgets after preview and before controls: " + node.name);
        }
    }

    private static void verifyRetirementWiring(ClassNode node) {
        boolean immediate = node.name.endsWith("NclSkinsImmediateScreen");
        String release = immediate ? "releasePreviews" : "releasePreviewBody";
        var removals = node.methods.stream().filter(method -> method.desc.equals("()V")
                && method.name.equals("removed")).toList();
        if (removals.size() != 1) throw new AssertionError("Expected one exact native retirement entrypoint: " + node.name);
        var removed = removals.get(0);
        int releaseAt = -1, accessoryAt = -1, closeAt = -1, clearAt = -1;
        int index = 0;
        for (var instruction : removed.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                if (call.owner.equals(node.name) && call.name.equals(release)) releaseAt = index;
                if (call.owner.equals(node.name) && call.name.equals("releasePreviewAccessories")) accessoryAt = index;
                if (call.name.equals("close") && call.owner.endsWith("PreviewRenderer")) closeAt = index;
            }
            if (instruction instanceof FieldInsnNode field && field.name.equals("bodyCapability") && field.getOpcode() == Opcodes.PUTFIELD) clearAt = index;
            index++;
        }
        if (releaseAt < 0 || !immediate && !(clearAt > releaseAt && closeAt > clearAt))
            throw new AssertionError("Compiled removed must retire input and clear the renderer query before owned close");
        if (!immediate && !(accessoryAt >= 0 && accessoryAt < releaseAt))
            throw new AssertionError("Compiled removed must release owned accessories before body cleanup can publish");
    }

    private static ClassNode namedForgeScreen(ClassNode node) throws IOException {
        String screen = "com/naocraftlab/skins/compat/gui/immediate/NclSkinsImmediateScreen";
        if (!node.name.startsWith(screen) || node.methods.stream().noneMatch(method -> method.name.startsWith("m_"))) return node;
        var mappings = java.util.Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .map(Path::of).filter(path -> path.getFileName().toString().startsWith("nclskins-")
                        && (path.getFileName().toString().endsWith("-forge.jar")
                        || path.getFileName().toString().endsWith("-forge-dev.jar")) && path.getParent() != null
                        && path.getParent().getFileName().toString().equals("libs"))
                .map(path -> path.getParent().getParent().resolve("createMcpToSrg/output.tsrg"))
                .distinct().toList();
        if (mappings.size() != 1 || !Files.isRegularFile(mappings.get(0)))
            throw new AssertionError("Exact Forge artifact member mapping is unavailable");
        Map<String, String> methods = new HashMap<>(), fields = new HashMap<>();
        for (String line : Files.readAllLines(mappings.get(0))) {
            if (!line.startsWith("\t") || line.startsWith("\t\t")) continue;
            String[] parts = line.trim().split("\\s+");
            if (parts.length == 3 && parts[1].startsWith("(") && parts[2].startsWith("m_")) {
                String old = methods.putIfAbsent(parts[2] + parts[1], parts[0]);
                if (old != null && !old.equals(parts[0])) throw new AssertionError("Ambiguous Forge method mapping");
            } else if (parts.length == 2 && parts[1].startsWith("f_")) {
                String old = fields.putIfAbsent(parts[1], parts[0]);
                if (old != null && !old.equals(parts[0])) throw new AssertionError("Ambiguous Forge field mapping");
            }
        }
        if (!"removed".equals(methods.get("m_7861_()V"))
                || !"render".equals(methods.get("m_88315_(Lnet/minecraft/client/gui/GuiGraphics;IIF)V")))
            throw new AssertionError("Forge screen entrypoint mapping differs");
        var named = new ClassNode(Opcodes.ASM9);
        node.accept(new ClassRemapper(named, new Remapper(Opcodes.ASM9) {
            private boolean nativeOwner(String owner) {
                return owner.startsWith("net/minecraft/") || owner.startsWith("com/mojang/") || owner.startsWith(screen);
            }
            @Override public String mapMethodName(String owner, String name, String descriptor) {
                return nativeOwner(owner) ? methods.getOrDefault(name + descriptor, name) : name;
            }
            @Override public String mapFieldName(String owner, String name, String descriptor) {
                return nativeOwner(owner) ? fields.getOrDefault(name, name) : name;
            }
        }));
        return named;
    }

    private static String internal(Class<?> type) { return type.getName().replace('.', '/'); }
}

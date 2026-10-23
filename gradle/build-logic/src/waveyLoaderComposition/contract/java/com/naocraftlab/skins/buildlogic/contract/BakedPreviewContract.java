package com.naocraftlab.skins.buildlogic.contract;

import com.naocraftlab.skins.buildlogic.terminal.BakedPreviewBindings;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public final class BakedPreviewContract {
    private static ClassNode read(String owner, List<Path> inputs) throws Exception {
        for (Path path : inputs) {
            byte[] bytes = null;
            if (Files.isDirectory(path) && Files.isRegularFile(path.resolve(owner + ".class"))) bytes = Files.readAllBytes(path.resolve(owner + ".class"));
            else if (Files.isRegularFile(path)) try (ZipFile zip = new ZipFile(path.toFile())) {
                var entry = zip.getEntry(owner + ".class");
                if (entry != null) bytes = zip.getInputStream(entry).readAllBytes();
            }
            if (bytes != null) { ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0); return node; }
        }
        throw new AssertionError("BAKED_PINNED_INPUT_MISSING " + owner);
    }
    private static MethodNode target(ClassNode node) {
        String name = node.name.endsWith("Lighting") ? "setupFor" : "renderToBuffer";
        String desc = node.name.endsWith("Lighting") ? "(Lcom/mojang/blaze3d/platform/Lighting$Entry;)V"
                : "(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V";
        return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst().orElseThrow();
    }
    private static byte[] bytes(ClassNode node) { ClassWriter writer = new ClassWriter(0); node.accept(writer); return writer.toByteArray(); }
    private static void rejected(ClassNode node) {
        try { BakedPreviewBindings.apply(node); throw new IllegalStateException("BAKED_BAD_TERMINAL_ACCEPTED"); }
        catch (AssertionError expected) { if (!expected.getMessage().startsWith("BAKED_DESCRIPTOR_COUNT")) throw expected; }
    }
    private static ClassNode copy(ClassNode node) { ClassNode result = new ClassNode(); new ClassReader(bytes(node)).accept(result, 0); return result; }
    private static void rejects(Runnable action, String prefix) {
        try { action.run(); throw new IllegalStateException("BAKED_BAD_INPUT_ACCEPTED " + prefix); }
        catch (AssertionError expected) { if (!expected.getMessage().startsWith(prefix)) throw expected; }
    }
    private static void methodControls(ClassNode node, String name, String desc) {
        requireMethod(node, name, desc);
        ClassNode wrong = copy(node), missing = copy(node), duplicate = copy(node), unrelated = copy(node);
        wrong.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst().orElseThrow().desc = "(J)Ljava/lang/Object;";
        missing.methods.removeIf(m -> m.name.equals(name) && m.desc.equals(desc));
        duplicate.methods.add(duplicate.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst().orElseThrow());
        unrelated.methods.add(new MethodNode(Opcodes.ACC_PRIVATE, name, "(J)Ljava/lang/Object;", null, null));
        rejects(() -> requireMethod(wrong, name, desc), "BAKED_METHOD_COUNT");
        rejects(() -> requireMethod(missing, name, desc), "BAKED_METHOD_COUNT");
        rejects(() -> requireMethod(duplicate, name, desc), "BAKED_METHOD_COUNT");
        requireMethod(unrelated, name, desc);
    }
    private static void fieldControls(ClassNode node, String name, String desc, boolean isStatic) {
        requireField(node, name, desc);
        var field = node.fields.stream().filter(f -> f.name.equals(name) && f.desc.equals(desc)).findFirst().orElseThrow();
        if (((field.access & Opcodes.ACC_STATIC) != 0) != isStatic) throw new AssertionError("BAKED_FIELD_OWNERSHIP " + node.name + "." + name);
        ClassNode wrong = copy(node), missing = copy(node), duplicate = copy(node), unrelated = copy(node);
        wrong.fields.stream().filter(f -> f.name.equals(name) && f.desc.equals(desc)).findFirst().orElseThrow().desc = "Ljava/lang/Object;";
        missing.fields.removeIf(f -> f.name.equals(name) && f.desc.equals(desc));
        duplicate.fields.add(duplicate.fields.stream().filter(f -> f.name.equals(name) && f.desc.equals(desc)).findFirst().orElseThrow());
        unrelated.fields.add(new FieldNode(0, name + "Unrelated", "I", null, null));
        rejects(() -> requireField(wrong, name, desc), "BAKED_FIELD_COUNT");
        rejects(() -> requireField(missing, name, desc), "BAKED_FIELD_COUNT");
        rejects(() -> requireField(duplicate, name, desc), "BAKED_FIELD_COUNT");
        requireField(unrelated, name, desc);
    }
    private static void scissor(ClassNode node, String desc) {
        requireMethod(node, "<init>", desc);
        var owners = node.innerClasses.stream().filter(i -> i.name.equals(node.name)).toList();
        if (owners.size() != 1 || (owners.get(0).access & Opcodes.ACC_STATIC) == 0
                || !"net/minecraft/client/gui/GuiGraphicsExtractor".equals(owners.get(0).outerName)
                || node.fields.stream().anyMatch(f -> f.name.equals("this$0"))) throw new AssertionError("BAKED_SCISSOR_OWNER");
    }
    private static void nativeCalls(ClassNode fixture, List<Path> inputs) throws Exception {
        for (MethodInsnNode call : calls(fixture)) if (call.owner.startsWith("net/minecraft/")
                && Set.of("<init>", "prepareGroup", "executeGroup", "finishExecute", "drainPhases", "sortInto", "close").contains(call.name)) {
            ClassNode owner = read(call.owner, inputs);
            while (owner.methods.stream().noneMatch(m -> m.name.equals(call.name) && m.desc.equals(call.desc))) {
                if (owner.superName == null || owner.superName.equals("java/lang/Object")) throw new AssertionError("BAKED_METHOD_COUNT " + call.owner + "." + call.name + call.desc);
                owner = read(owner.superName, inputs);
            }
            methodControls(owner, call.name, call.desc);
        }
    }
    public static void verifyNativeInputs(List<Path> inputs) throws Exception {
        if (!Boolean.getBoolean("nclskins.wavey.extraction")) return;
        String mc = "net/minecraft/client/", renderer = mc + "renderer/", feature = renderer + "feature/";
        String probe = "com/naocraftlab/skins/buildlogic/probe/wavey/";
        String mode = System.getProperty("nclskins.wavey.mode", "buffered");
        ClassNode helper = read(probe + "BakedGuiInputs", inputs);
        requireMethod(helper, "scissorStack", "()Ljava/lang/Object;");
        if (helper.methods.stream().flatMap(m -> Arrays.stream(m.instructions.toArray())).filter(i -> i instanceof LdcInsnNode ldc
                && "net.minecraft.client.gui.GuiGraphicsExtractor$ScissorStack".equals(ldc.cst)).count() != 1
                || calls(helper).stream().anyMatch(c -> c.owner.startsWith("net/minecraft/") && !c.owner.equals(mc + "gui/navigation/ScreenRectangle")))
            throw new AssertionError("BAKED_SCISSOR_FACTORY");
        long rectangleTypes = helper.methods.stream().flatMap(m -> Arrays.stream(m.instructions.toArray()))
                .filter(i -> i instanceof LdcInsnNode ldc && Type.getObjectType(mc + "gui/navigation/ScreenRectangle").equals(ldc.cst)).count();
        if (rectangleTypes > 1 || calls(helper).stream().filter(c -> c.owner.equals("java/lang/Class") && c.name.equals("getDeclaredConstructor")
                && c.desc.equals("([Ljava/lang/Class;)Ljava/lang/reflect/Constructor;")).count() != 1
                || calls(helper).stream().filter(c -> c.owner.equals("java/lang/reflect/Constructor") && c.name.equals("newInstance")).count() != 1)
            throw new AssertionError("BAKED_SCISSOR_FACTORY");
        String desc = rectangleTypes == 1 ? "(Lnet/minecraft/client/gui/navigation/ScreenRectangle;)V" : "()V";
        ClassNode stack = read(mc + "gui/GuiGraphicsExtractor$ScissorStack", inputs);
        scissor(stack, desc); methodControls(stack, "<init>", desc);
        ClassNode outer = copy(stack); outer.innerClasses.stream().filter(i -> i.name.equals(outer.name)).forEach(i -> i.access &= ~Opcodes.ACC_STATIC);
        rejects(() -> scissor(outer, desc), "BAKED_SCISSOR_OWNER");
        ClassNode outerField = copy(stack); outerField.fields.add(new FieldNode(0, "this$0", "Lnet/minecraft/client/gui/GuiGraphicsExtractor;", null, null));
        rejects(() -> scissor(outerField, desc), "BAKED_SCISSOR_OWNER");
        if (rectangleTypes == 1) {
            methodControls(read(mc + "gui/navigation/ScreenRectangle", inputs), "<init>", "(IIII)V");
            methodControls(read(mc + "gui/navigation/ScreenPosition", inputs), "<init>", "(II)V");
            if (calls(helper).stream().filter(c -> c.owner.equals(mc + "gui/navigation/ScreenRectangle") && c.name.equals("<init>") && c.desc.equals("(IIII)V")).count() != 1)
                throw new AssertionError("BAKED_SCISSOR_RECTANGLE_FACTORY");
            var constructor = calls(helper).stream().filter(c -> c.owner.equals(mc + "gui/navigation/ScreenRectangle") && c.name.equals("<init>")).findFirst().orElseThrow();
            var height = constructor.getPrevious(); var width = height.getPrevious(); var y = width.getPrevious(); var x = y.getPrevious();
            if (!(height instanceof IntInsnNode h) || h.operand != 160 || !(width instanceof IntInsnNode w) || w.operand != 100
                    || x.getOpcode() != Opcodes.ICONST_0 || y.getOpcode() != Opcodes.ICONST_0) throw new AssertionError("BAKED_SCISSOR_POSITIVE_VIEWPORT");
            rejects(() -> scissor(stack, "()V"), "BAKED_METHOD_COUNT");
        }
        for (String[] field : List.of(
                new String[]{mc + "Minecraft", "instance", "Lnet/minecraft/client/Minecraft;", "true"},
                new String[]{mc + "Minecraft", "gameThread", "Ljava/lang/Thread;", "false"},
                new String[]{mc + "Minecraft", "gameRenderer", "Lnet/minecraft/client/renderer/GameRenderer;", "false"},
                new String[]{"net/minecraft/world/entity/Entity", "uuid", "Ljava/util/UUID;", "false"},
                new String[]{renderer + "GameRenderer", "lighting", "Lcom/mojang/blaze3d/platform/Lighting;", "false"},
                new String[]{mc + "gui/GuiGraphicsExtractor", "minecraft", "Lnet/minecraft/client/Minecraft;", "false"},
                new String[]{mc + "gui/GuiGraphicsExtractor", "pose", "Lorg/joml/Matrix3x2fStack;", "false"},
                new String[]{mc + "gui/GuiGraphicsExtractor", "scissorStack", "Lnet/minecraft/client/gui/GuiGraphicsExtractor$ScissorStack;", "false"},
                new String[]{mc + "gui/GuiGraphicsExtractor", "guiRenderState", "Lnet/minecraft/client/renderer/state/gui/GuiRenderState;", "false"}))
            fieldControls(read(field[0], inputs), field[1], field[2], Boolean.parseBoolean(field[3]));
        methodControls(read(renderer + "state/gui/GuiRenderState", inputs), "<init>", "()V");
        methodControls(read("com/naocraftlab/skins/compat/client/identifier/extraction/NclBakedPlayerRenderer$TargetRenderer", inputs), "renderToTexture",
                "(Lcom/naocraftlab/skins/compat/client/identifier/extraction/NclBakedPlayerRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;"
                        + (mode.equals("buffered") ? "" : "Lnet/minecraft/client/renderer/SubmitNodeCollector;") + ")V");
        if (mode.equals("prepared")) {
            ClassNode dispatcher = read(feature + "FeatureRenderDispatcher", inputs);
            fieldControls(dispatcher, "featureRenderers", "Lnet/minecraft/client/renderer/feature/FeatureRendererMap;", false);
            fieldControls(dispatcher, "preparedFrame", "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;", false);
            fieldControls(dispatcher, "stagedVertexBuffer", "Lnet/minecraft/client/renderer/StagedVertexBuffer;", false);
            methodControls(dispatcher, "prepareFrameWithContext", "(Lnet/minecraft/client/renderer/feature/FeatureFrameContext;Lnet/minecraft/client/renderer/SubmitNodeStorage;)Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;");
            methodControls(dispatcher, "close", "()V");
            ClassNode frame = read(feature + "FeatureRenderDispatcher$PreparedFrame", inputs);
            methodControls(frame, "<init>", "(Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;)V");
            for (String method : List.of("executeSolid", "executeTranslucent", "executeOutline", "executeTranslucentAfterTerrain", "executeAlwaysOnTop", "close")) methodControls(frame, method, "()V");
        }
        if (!mode.equals("buffered")) {
            for (String fixture : mode.equals("combined") ? List.of("ExtractionBakedConsumer", "ExtractionBakedConsumer$1", "CombinedCapeStaged", "CombinedCapeHost")
                    : List.of("ExtractionBakedConsumer", "CapeNativeDriver", "CapeStaged")) nativeCalls(read(probe + fixture, inputs), inputs);
            String format = mode.equals("combined") ? "com/mojang/renderpearl/api/vertex/VertexFormat" : "com/mojang/blaze3d/vertex/VertexFormat";
            String topology = mode.equals("combined") ? "com/mojang/renderpearl/api/pipeline/PrimitiveTopology" : "com/mojang/blaze3d/PrimitiveTopology";
            methodControls(read(renderer + "StagedVertexBuffer$Draw", inputs), "<init>", "(L" + format + ";L" + topology + ";Lcom/mojang/blaze3d/vertex/VertexSorting;)V");
        }
        if (mode.equals("combined")) {
            fieldControls(read(renderer + "GameRenderer", inputs), "gameRenderState", "Lnet/minecraft/client/renderer/state/GameRenderState;", false);
            fieldControls(read(renderer + "state/GameRenderState", inputs), "optionsRenderState", "Lnet/minecraft/client/renderer/state/OptionsRenderState;", false);
            fieldControls(read(renderer + "state/GameRenderState", inputs), "levelRenderState", "Lnet/minecraft/client/renderer/state/level/LevelRenderState;", false);
            fieldControls(read(renderer + "rendertype/RenderType", inputs), "name", "Ljava/lang/String;", false);
            fieldControls(read(renderer + "rendertype/RenderSetup", inputs), "oitPipelineSet", "Lnet/minecraft/client/renderer/oit/OitPipelineSet;", false);
            methodControls(read(renderer + "state/OptionsRenderState", inputs), "<init>", "()V");
            ClassNode consumer = read(probe + "ExtractionBakedConsumer", inputs), group = read(probe + "ExtractionBakedConsumer$1", inputs);
            if (calls(consumer).stream().filter(c -> c.owner.equals("java/lang/reflect/Field") && c.name.equals("set")).count() < 2
                    || consumer.methods.stream().filter(m -> m.name.equals("consume")).noneMatch(m -> m.tryCatchBlocks.stream().filter(t -> t.type == null).count() >= 2)
                    || calls(consumer).stream().noneMatch(c -> c.name.equals("finishExecute"))
                    || group.methods.stream().flatMap(m -> Arrays.stream(m.instructions.toArray())).noneMatch(i -> i instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD && f.name.equals("groupIndex"))
                    || calls(group).stream().noneMatch(c -> c.owner.equals("java/util/IdentityHashMap") && c.name.equals("<init>"))
                    || calls(group).stream().noneMatch(c -> c.name.equals("remove"))) throw new AssertionError("BAKED_COMBINED_PROTOCOL");
        }
        System.out.println("BAKED_NATIVE_INPUT_ABI_PASS mode=" + mode + " scissor=" + desc + "; wrong/missing/duplicate/outer-owner byte controls; no native definitions");
    }
    public static void verify(List<Path> inputs) throws Exception {
        verifyNativeInputs(inputs);
        boolean extraction = Boolean.getBoolean("nclskins.wavey.extraction");
        Map<String, Map<String, String>> fields = extraction ? Map.of(
                "net/minecraft/client/gui/GuiGraphicsExtractor", Map.of("minecraft", "Lnet/minecraft/client/Minecraft;", "pose", "Lorg/joml/Matrix3x2fStack;", "scissorStack", "Lnet/minecraft/client/gui/GuiGraphicsExtractor$ScissorStack;", "guiRenderState", "Lnet/minecraft/client/renderer/state/gui/GuiRenderState;"),
                "net/minecraft/client/renderer/GameRenderer", Map.of("lighting", "Lcom/mojang/blaze3d/platform/Lighting;")) : Map.of(
                "net/minecraft/client/gui/GuiGraphics", Map.of("minecraft", "Lnet/minecraft/client/Minecraft;", "pose", "Lorg/joml/Matrix3x2fStack;", "scissorStack", "Lnet/minecraft/client/gui/GuiGraphics$ScissorStack;", "guiRenderState", "Lnet/minecraft/client/gui/render/state/GuiRenderState;"),
                "net/minecraft/client/renderer/GameRenderer", Map.of("lighting", "Lcom/mojang/blaze3d/platform/Lighting;"),
                "net/minecraft/client/gui/render/pip/PictureInPictureRenderer", Map.of("bufferSource", "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;"));
        for (var entry : fields.entrySet()) for (var field : entry.getValue().entrySet()) {
            ClassNode owner = read(entry.getKey(), inputs);
            requireField(owner, field.getKey(), field.getValue());
            owner.fields.stream().filter(f -> f.name.equals(field.getKey())).findFirst().orElseThrow().desc = "Ljava/lang/Object;";
            try { requireField(owner, field.getKey(), field.getValue()); throw new IllegalStateException("BAKED_WRONG_FIELD_ACCEPTED"); }
            catch (AssertionError expected) { if (!expected.getMessage().startsWith("BAKED_FIELD_COUNT")) throw expected; }
        }
        for (String owner : BakedPreviewBindings.OWNERS) {
            ClassNode node = read(owner, inputs);
            var original = target(node);
            Map<String, byte[]> retained = new HashMap<>();
            for (MethodNode method : node.methods) if (method != original) {
                ClassNode single = new ClassNode(); single.version = node.version; single.access = node.access; single.name = owner; single.superName = "java/lang/Object"; single.methods.add(method);
                retained.put(method.name + method.desc, bytes(single));
            }
            if (BakedPreviewBindings.apply(node) != 1) throw new AssertionError("BAKED_PUBLIC_CONSUMER_TERMINAL_MISSING " + owner);
            MethodNode result = target(node);
            if (owner.endsWith("Lighting")) {
                if (result.instructions.size() != 1 || result.instructions.getFirst().getOpcode() != Opcodes.RETURN)
                    throw new AssertionError("BAKED_GPU_SINK");
            } else {
                var ops = Arrays.stream(result.instructions.toArray()).filter(i -> i.getOpcode() >= 0).toList();
                if (ops.size() != 7 || !(ops.get(0) instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD || load.var != 2
                        || !(ops.get(4) instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKEINTERFACE || !call.itf
                        || !call.owner.equals("com/mojang/blaze3d/vertex/VertexConsumer") || !call.name.equals("addVertex")
                        || !call.desc.equals("(FFF)Lcom/mojang/blaze3d/vertex/VertexConsumer;") || ops.get(5).getOpcode() != Opcodes.POP
                        || ops.get(6).getOpcode() != Opcodes.RETURN) throw new AssertionError("BAKED_SUPPLIED_CONSUMER_SINK");
            }
            for (MethodNode method : node.methods) if (method != result) {
                ClassNode single = new ClassNode(); single.version = node.version; single.access = node.access; single.name = owner; single.superName = "java/lang/Object"; single.methods.add(method);
                if (!Arrays.equals(retained.get(method.name + method.desc), bytes(single))) throw new AssertionError("BAKED_UNRELATED_BODY_CHANGED");
            }
            ClassNode wrong = read(owner, inputs); target(wrong).desc = "()V"; rejected(wrong);
            ClassNode duplicate = read(owner, inputs); duplicate.methods.add(target(duplicate)); rejected(duplicate);
        }
        ClassNode unknown = new ClassNode(); unknown.name = "net/minecraft/client/Minecraft";
        if (BakedPreviewBindings.apply(unknown) != 0) throw new AssertionError("BAKED_UNKNOWN_OWNER");
        if (extraction) {
            String probe = "com/naocraftlab/skins/buildlogic/probe/wavey/";
            ClassNode batch = read(probe + "ClientCapeBatch", inputs);
            if (batch.methods.stream().flatMap(m -> Arrays.stream(m.instructions.toArray())).noneMatch(i -> i instanceof LdcInsnNode literal
                    && (probe + "ExtractionBakedPreviewProbe").replace('/', '.').equals(literal.cst))) throw new AssertionError("EXTRACTION_BAKED_NOT_REGISTERED");
            ClassNode entry = read(probe + "ExtractionBakedPreviewProbe", inputs);
            long consumes = entry.methods.stream().flatMap(m -> Arrays.stream(m.instructions.toArray())).filter(i -> i instanceof MethodInsnNode call
                    && call.owner.equals(probe + "ExtractionBakedConsumer") && call.name.equals("consume")
                    && call.desc.equals("(Lcom/naocraftlab/skins/compat/client/identifier/extraction/NclBakedPlayerRenderState;Z)V")).count();
            if (consumes != 3) throw new AssertionError("EXTRACTION_BAKED_CONSUMER_CONTROLS");
            ClassNode consumer = read(probe + "ExtractionBakedConsumer", inputs);
            if (BakedPreviewBindings.apply(consumer) != 0) throw new AssertionError("EXTRACTION_BAKED_CONSUMER_REPLACED");
            if (calls(consumer).stream().anyMatch(c -> c.owner.equals(probe + "ExtractionBakedConsumer$Buffers") && c.name.equals("<init>"))) {
                bufferedPartition(read(probe + "ExtractionBakedConsumer$Buffers", inputs), consumer);
                ClassNode missing = read(probe + "ExtractionBakedConsumer$Buffers", inputs);
                calls(missing).stream().filter(c -> c.owner.equals("java/util/Map") && c.name.equals("of")).forEach(c -> c.desc = "()Ljava/util/Map;");
                try { bufferedPartition(missing, consumer); throw new IllegalStateException("BAKED_EMPTY_PARTITION_ACCEPTED"); }
                catch (AssertionError expected) { if (!expected.getMessage().equals("BAKED_BUFFERED_PARTITION_STRUCTURE")) throw expected; }
                ClassNode nativeBuffers = read("net/minecraft/client/renderer/MultiBufferSource$BufferSource", inputs);
                requireMethod(nativeBuffers, "<init>", "(Lcom/mojang/blaze3d/vertex/ByteBufferBuilder;Ljava/util/SequencedMap;)V");
                requireMethod(nativeBuffers, "getBuffer", "(Lnet/minecraft/client/renderer/rendertype/RenderType;)Lcom/mojang/blaze3d/vertex/VertexConsumer;");
                requireMethod(nativeBuffers, "endBatch", "()V");
                requireMethod(read("net/minecraft/client/model/Model", inputs), "renderToBuffer", "(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;II)V");
                System.out.println("BAKED_BUFFERED_PARTITION_STRUCTURAL_PASS native ABI and owned close; not behavioral drain proof");
            }
        }
        System.out.println("BAKED_PINNED_TERMINAL_CONTRACT_PASS exact2 wrong-descriptor/duplicate/unknown-owner; supplied-consumer and unrelated bodies preserved; no native definitions");
    }
    private static void requireField(ClassNode owner, String name, String desc) {
        if (owner.fields.stream().filter(f -> f.name.equals(name) && f.desc.equals(desc)).count() != 1)
            throw new AssertionError("BAKED_FIELD_COUNT " + owner.name + "." + name + desc);
    }
    private static List<MethodInsnNode> calls(ClassNode owner) {
        return owner.methods.stream().flatMap(m -> Arrays.stream(m.instructions.toArray()))
                .filter(i -> i instanceof MethodInsnNode).map(i -> (MethodInsnNode) i).toList();
    }
    private static void requireMethod(ClassNode owner, String name, String desc) {
        if (owner.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).count() != 1)
            throw new AssertionError("BAKED_METHOD_COUNT " + owner.name + "." + name + desc);
    }
    private static void bufferedPartition(ClassNode buffers, ClassNode consumer) {
        List<MethodInsnNode> setup = calls(buffers), use = calls(consumer);
        if (setup.stream().filter(c -> c.owner.equals("java/util/Map") && c.name.equals("of")
                        && c.desc.equals("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/Map;")).count() != 1
                || setup.stream().filter(c -> c.name.equals("entityGlint") && c.desc.equals("()Lnet/minecraft/client/renderer/rendertype/RenderType;")).count() != 1
                || setup.stream().filter(c -> c.owner.equals("net/minecraft/client/renderer/MultiBufferSource$BufferSource") && c.name.equals("getBuffer")).count() != 1
                || use.stream().filter(c -> c.owner.equals("com/mojang/blaze3d/vertex/ByteBufferBuilder") && c.name.equals("close") && c.desc.equals("()V")).count() != 2
                || consumer.methods.stream().filter(m -> m.name.equals("consume")).noneMatch(m -> !m.tryCatchBlocks.isEmpty())
                || use.stream().anyMatch(c -> c.name.equals("endBatch"))) throw new AssertionError("BAKED_BUFFERED_PARTITION_STRUCTURE");
    }
    public static void main(String[] args) throws Exception { verify(Arrays.stream(args).map(Path::of).toList()); }
}

package com.naocraftlab.skins.buildlogic.probe;

import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.provider.AppearanceProviders;
import com.naocraftlab.skins.core.provider.BuiltinProvider;
import com.naocraftlab.skins.runtime.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceMethodVisitor;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

public final class ProviderRowClippingProbe {
    private static final String PREFIX = "com/naocraftlab/skins/buildlogic/probe/";
    private static final String SLICE = PREFIX + "CompiledProviderFrame";
    private static String widgetField;

    public static final class FocusWidget {
        private final boolean focused;
        public FocusWidget(boolean focused) { this.focused = focused; }
        public boolean isFocused() { return focused; }
    }

    public record Fill(int left, int top, int right, int bottom, int color, Bounds clip) {
        boolean covers(int x, int y) {
            return left <= x && x < right && top <= y && y < bottom
                    && (clip == null || clip.contains(x, y));
        }
    }

    public static final class RecordingGraphics {
        public final List<Fill> fills = new ArrayList<>();
        private final Deque<Bounds> clips = new ArrayDeque<>();
        private int throwAt = -1;
        public void enableScissor(int left, int top, int right, int bottom) {
            clips.push(new Bounds(left, top, right - left, bottom - top));
        }
        public void disableScissor() { clips.pop(); }
        public void fill(int left, int top, int right, int bottom, int color) {
            fills.add(new Fill(left, top, right, bottom, color, clips.peek()));
            if (fills.size() == throwAt) throw new IllegalStateException("controlled draw exception");
        }
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return node;
    }

    private static String trace(MethodNode node) {
        Textifier text = new Textifier();
        node.accept(new TraceMethodVisitor(text));
        StringWriter result = new StringWriter();
        text.print(new PrintWriter(result));
        return result.toString();
    }

    private static byte[] slice(byte[] original, String family, Path work) throws Exception {
        ClassNode source = read(original);
        String frame = family.equals("extraction") ? "drawCardBackgrounds" : "renderCardBackgrounds";
        String wrapper = switch (family) {
            case "immediate" -> "renderClipped";
            case "submission" -> "clipped";
            case "extraction" -> "drawClipped";
            default -> throw new IllegalArgumentException(family);
        };
        String graphics = family.equals("extraction")
                ? "net/minecraft/client/gui/GuiGraphicsExtractor" : "net/minecraft/client/gui/GuiGraphics";
        Map<String, String> mapping = Map.of(source.name, SLICE, graphics,
                PREFIX + "ProviderRowClippingProbe$RecordingGraphics",
                "net/minecraft/client/gui/components/AbstractWidget", PREFIX + "ProviderRowClippingProbe$FocusWidget");
        Remapper names = new Remapper(Opcodes.ASM9) {
            @Override public String map(String name) { return mapping.getOrDefault(name, name); }
        };
        Map<String, MethodNode> available = new LinkedHashMap<>();
        List<String> bindings = new ArrayList<>();
        for (String nativeName : List.of(graphics, "net/minecraft/client/gui/components/AbstractWidget")) {
            String resource = nativeName + ".class";
            try (var stream = ProviderRowClippingProbe.class.getClassLoader().getResourceAsStream(resource)) {
                require(stream != null, "native dependency class missing: " + resource);
                byte[] nativeBytes = stream.readAllBytes();
                ClassNode nativeClass = read(nativeBytes);
                require((nativeClass.access & Opcodes.ACC_INTERFACE) == 0, "native ABI is not the expected class: " + nativeName);
                List<String> methods = nativeName.equals(graphics)
                        ? List.of("fill(IIIII)V", "enableScissor(IIII)V", "disableScissor()V") : List.of("isFocused()Z");
                for (String signature : methods) require(nativeClass.methods.stream()
                        .anyMatch(m -> (m.name + m.desc).equals(signature)), "exact native ABI missing: " + nativeName + "." + signature);
                bindings.add(resource + " origin=" + ProviderRowClippingProbe.class.getClassLoader().getResource(resource)
                        + " sha256=" + sha(nativeBytes) + " kind=class");
            }
        }
        Files.writeString(work.resolve("native-bindings.txt"), String.join("\n", bindings) + "\n");
        source.methods.forEach(m -> available.put(m.name + m.desc, m));
        Set<String> keep = new LinkedHashSet<>();
        source.methods.stream().filter(m -> m.name.equals(frame) || m.name.equals(wrapper)
                && Type.getArgumentTypes(m.desc).length == 4).forEach(m -> keep.add(m.name + m.desc));
        require(keep.size() == 2, "exact frame and four-argument clip wrapper binding");
        boolean changed;
        do {
            changed = false;
            for (String key : List.copyOf(keep)) {
                MethodNode m = available.get(key);
                require(m != null, "reachable origin method missing: " + key);
                for (AbstractInsnNode insn : m.instructions) {
                    if (insn instanceof MethodInsnNode call && mapping.containsKey(call.owner)
                            && !call.owner.equals(source.name)) {
                        require(call.getOpcode() == Opcodes.INVOKEVIRTUAL && !call.itf,
                                "native binding opcode/interface mismatch: " + call.owner + "." + call.name);
                    }
                    if (insn instanceof MethodInsnNode call && call.owner.equals(source.name))
                        changed |= keep.add(call.name + call.desc);
                    if (insn instanceof InvokeDynamicInsnNode dynamic) {
                        for (Object arg : dynamic.bsmArgs) {
                            if (arg instanceof Handle h && h.getOwner().equals(source.name))
                                changed |= keep.add(h.getName() + h.getDesc());
                        }
                    }
                }
            }
            require(keep.size() <= 12, "DESIGN_FAILURE: frame graph exceeds bounded slice");
        } while (changed);
        Set<String> fields = new LinkedHashSet<>();
        for (String key : keep) {
            for (AbstractInsnNode insn : available.get(key).instructions) {
                if (insn instanceof FieldInsnNode f && f.owner.equals(source.name)) fields.add(f.name);
            }
        }
        require(fields.equals(Set.of(family.equals("submission") ? "widgets" : "nativeWidgets", "keyboardNavigation")),
                "DESIGN_FAILURE: unexpected frame state " + fields);
        ClassNode selected = new ClassNode();
        selected.version = source.version;
        selected.access = Opcodes.ACC_PUBLIC;
        selected.name = source.name;
        selected.superName = "java/lang/Object";
        selected.fields.addAll(source.fields.stream().filter(f -> fields.contains(f.name)).toList());
        selected.methods.addAll(keep.stream().map(available::get).toList());
        ClassNode expected = new ClassNode();
        selected.accept(new ClassRemapper(expected, names));
        MethodVisitor constructor = expected.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();
        ClassWriter output = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override protected String getCommonSuperClass(String a, String b) {
                if (a.equals(b)) return a;
                if (a.startsWith("java/") && b.startsWith("java/")) return super.getCommonSuperClass(a, b);
                throw new IllegalStateException("DESIGN_FAILURE: unexpected frame merge " + a + " " + b);
            }
        };
        expected.accept(output);
        byte[] bytes = output.toByteArray();
        ClassNode actual = read(bytes);
        List<String> receipt = new ArrayList<>();
        receipt.add("origin=" + source.name + " sha256=" + sha(original));
        receipt.add("slice=" + SLICE + " sha256=" + sha(bytes));
        receipt.add("mapping=" + mapping);
        for (String key : keep) {
            MethodNode origin = available.get(key);
            MethodNode mapped = expected.methods.stream().filter(m -> m.name.equals(origin.name)
                    && m.desc.equals(names.mapMethodDesc(origin.desc))).findFirst().orElseThrow();
            MethodNode emitted = actual.methods.stream().filter(m -> m.name.equals(mapped.name)
                    && m.desc.equals(mapped.desc)).findFirst().orElseThrow();
            String pre = trace(mapped);
            String post = trace(emitted);
            Files.writeString(work.resolve(origin.name + "-origin.txt"), trace(origin));
            Files.writeString(work.resolve(origin.name + "-mapped.txt"), pre);
            Files.writeString(work.resolve(origin.name + "-emitted.txt"), post);
            require(pre.equals(post), "opcode/operand/control-flow drift in " + key);
            receipt.add(key + " origin=" + sha(trace(origin).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    + " mapped=emitted=" + sha(pre.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        Files.write(work.resolve("CompiledProviderFrame.class"), bytes);
        Files.writeString(work.resolve("method-origin.txt"), String.join("\n", receipt) + "\n");
        return bytes;
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static ViewSpec view(AppearanceProviders.Component component, boolean selected, int height,
            double offset, boolean feedback) {
        return providerView(AppearanceProviders.initial(), BuiltinProvider.OFFLINE, component, selected, height, offset, feedback);
    }

    private static ViewSpec providerView(AppearanceProviders providers, BuiltinProvider provider,
            AppearanceProviders.Component component, boolean selected, int height, double offset, boolean feedback) {
        return new ProvidersPresenter().present(providers, component, false, false,
                PreviewInteractionModel.editor(height, PreviewRenderer.CapeMode.CAPE), SkinVariant.CLASSIC,
                854, height, component == AppearanceProviders.Component.SKIN && selected ? provider : null,
                component == AppearanceProviders.Component.CAPE && selected ? provider : null,
                Optional.empty(), false, feedback ? UiMessage.literal("x", UiMessage.Severity.INFO) : null,
                offset, UiMessage::key);
    }

    private static RecordingGraphics invoke(Class<?> host, Method method, ViewSpec view,
            String focus, boolean keyboard) throws Exception {
        Object instance = host.getConstructor().newInstance();
        var field = host.getDeclaredField(widgetField);
        field.setAccessible(true);
        field.set(instance, focus == null ? Map.of() : Map.of(focus, new FocusWidget(true)));
        var keyboardField = host.getDeclaredField("keyboardNavigation");
        keyboardField.setAccessible(true);
        keyboardField.setBoolean(instance, keyboard);
        RecordingGraphics graphics = new RecordingGraphics();
        Object result = method.invoke(instance, graphics, view, -1000, -1000);
        if (method.getReturnType() == boolean.class) require(Boolean.TRUE.equals(result)
                || graphics.fills.isEmpty(), "rendered return contract");
        graphics.fill(10, 130, 30, 140, 0xFF123456);
        Fill following = graphics.fills.remove(graphics.fills.size() - 1);
        require(following.covers(10, 130) && following.clip == null, "C5 following control clipped by leaked scissor");
        require(graphics.clips.isEmpty(), "clip restoration");
        return graphics;
    }

    private static ViewSpec ordinaryControl(ViewSpec source) {
        var control = ViewSpec.Widget.selectableCard("providers.edit.OFFLINE", new Bounds(435, 55, 411, 36),
                UiMessage.literal("control", UiMessage.Severity.INFO), true, true);
        return new ViewSpec(source.screenId(), source.title(), source.width(), source.height(),
                source.panels(), source.texts(), List.of(control), source.previews(), source.scrollbar(),
                source.tabGroups(), source.focusRequest(), source.clipRegions(), source.backEquipmentPreviews(),
                source.iconDecorations(), source.scrollSurfaces(), source.tooltipRegions(),
                source.progressDecorations(), List.of());
    }

    private static boolean coverage(Fill f, Bounds viewport, boolean outside) {
        for (int y = f.top; y < f.bottom; y++) for (int x = f.left; x < f.right; x++)
            if (f.covers(x, y) && viewport.contains(x, y) != outside) return true;
        return false;
    }

    private static ViewSpec withWidgets(ViewSpec source, List<ViewSpec.Widget> widgets, boolean clips) {
        return new ViewSpec(source.screenId(), source.title(), source.width(), source.height(),
                source.panels(), source.texts(), widgets, source.previews(), source.scrollbar(),
                source.tabGroups(), source.focusRequest(), clips ? source.clipRegions() : List.of(), source.backEquipmentPreviews(),
                source.iconDecorations(), source.scrollSurfaces(), source.tooltipRegions(),
                source.progressDecorations(), List.of());
    }

    private static void additionalControls(Class<?> host, Method frame, List<String> observations) throws Exception {
        for (var component : AppearanceProviders.Component.values()) {
            for (boolean top : new boolean[]{true, false}) {
                var providers = AppearanceProviders.initial().enable(component, BuiltinProvider.MINECRAFT);
                BuiltinProvider selected = top ? BuiltinProvider.OFFLINE : BuiltinProvider.MINECRAFT;
                ViewSpec hidden = providerView(providers, selected, component, true, 110, top ? 40 : 0, false);
                Bounds viewport = new Bounds(435, 65, 411, 12);
                require(hidden.clipFor("providers.row." + selected).orElseThrow().equals(viewport), "hidden literal viewport");
                Bounds hiddenRow = hidden.widget("providers.row." + selected).orElseThrow().bounds();
                require(top ? hiddenRow.bottom() <= 65 : hiddenRow.y() >= 77, "hidden producer prerequisite");
                RecordingGraphics g = invoke(host, frame, hidden, null, false);
                require(g.fills.size() == 2, "hidden row must reach both frame fills");
                require(g.fills.stream().noneMatch(f -> coverage(f, viewport, true) || coverage(f, viewport, false)),
                        "C3 hidden frame coverage top=" + top);
                observations.add("C3_PASS hidden " + component + " top=" + top);
            }
            ViewSpec source = view(component, true, 150, 10, false);
            ViewSpec.Widget row = source.widget("providers.row.OFFLINE").orElseThrow();
            ViewSpec.Widget following = ViewSpec.Widget.selectableCard("following", new Bounds(10, 130, 20, 10),
                    UiMessage.literal("following", UiMessage.Severity.INFO), true, true);
            RecordingGraphics next = invoke(host, frame, withWidgets(source, List.of(row, following), true), null, false);
            require(next.fills.size() == 3 && next.fills.get(2).clip == null && next.fills.get(2).covers(10, 130),
                    "C5 following unrelated no-clip control");
            RecordingGraphics noClip = invoke(host, frame, withWidgets(source, List.of(row), false), null, false);
            require(noClip.fills.size() == 2 && noClip.fills.get(0).clip == null && noClip.fills.get(0).covers(435, 55)
                    && noClip.fills.get(1).covers(436, 56), "C5 allowed no-clip provider preserves fills");
            for (int throwAt : new int[]{1, 2}) {
                Object instance = host.getConstructor().newInstance();
                var field = host.getDeclaredField(widgetField); field.setAccessible(true); field.set(instance, Map.of());
                RecordingGraphics graphics = new RecordingGraphics(); graphics.throwAt = throwAt;
                try {
                    frame.invoke(instance, graphics, source, -1000, -1000);
                    throw new AssertionError("controlled draw did not throw");
                } catch (java.lang.reflect.InvocationTargetException failure) {
                    require(failure.getCause() instanceof IllegalStateException
                            && failure.getCause().getMessage().equals("controlled draw exception"), "actual draw exception identity");
                }
                require(graphics.fills.size() == throwAt && graphics.clips.isEmpty(), "C5 exception clip restoration layer=" + throwAt);
                graphics.throwAt = -1;
                frame.invoke(instance, graphics, withWidgets(source, List.of(following), true), -1000, -1000);
                require(graphics.fills.get(graphics.fills.size()-1).covers(10,130), "C5 following control after draw exception");
            }
            for (int width : new int[]{360, 1024}) {
                int height = width == 360 ? 130 : 300;
                ViewSpec resized = new ProvidersPresenter().present(AppearanceProviders.initial(), component, false, false,
                        PreviewInteractionModel.editor(height, PreviewRenderer.CapeMode.CAPE), SkinVariant.CLASSIC,
                        width, height, component == AppearanceProviders.Component.SKIN ? BuiltinProvider.OFFLINE : null,
                        component == AppearanceProviders.Component.CAPE ? BuiltinProvider.OFFLINE : null,
                        Optional.empty(), false, null, 10, UiMessage::key);
                Bounds expected = width == 360 ? new Bounds(188,65,164,32) : new Bounds(520,65,496,202);
                require(resized.clipFor("providers.row.OFFLINE").orElseThrow().equals(expected), "C4 independent resized viewport");
                RecordingGraphics g = invoke(host, frame, resized, null, false);
                require(g.fills.size()==2, "C4 resized both fills");
                for (Fill f : g.fills) require(coverage(f,expected,false) && !coverage(f,expected,true), "C4 resized inside/outside coverage");
            }
            observations.add("C4_C5_PASS " + component + " resized/restoration/exception/following/no-clip");
        }
    }

    public static void main(String[] args) throws Exception {
        widgetField = args[1].equals("submission") ? "widgets" : "nativeWidgets";
        Path work = Path.of(args[3]);
        Files.createDirectories(work);
        byte[] original = Files.readAllBytes(Path.of(args[0]));
        byte[] bytes = slice(original, args[1], work);
        Class<?> host = new ClassLoader(ProviderRowClippingProbe.class.getClassLoader()) {
            Class<?> loadSlice() { return defineClass(SLICE.replace('/', '.'), bytes, 0, bytes.length); }
        }.loadSlice();
        String methodName = args[1].equals("extraction") ? "drawCardBackgrounds" : "renderCardBackgrounds";
        Method frame = host.getDeclaredMethod(methodName, RecordingGraphics.class, ViewSpec.class, int.class, int.class);
        frame.setAccessible(true);
        List<String> observations = new ArrayList<>();
        int defects = 0;
        int cases = 0;
        for (var component : AppearanceProviders.Component.values()) {
            ViewSpec visible = view(component, true, 240, 0, false);
            RecordingGraphics baseline = invoke(host, frame, visible, null, false);
            Bounds row = visible.widget("providers.row.OFFLINE").orElseThrow().bounds();
            require(row.equals(new Bounds(435, 65, 411, 36)), "literal fully-visible row geometry " + row);
            require(baseline.fills.size() == 2, "selected frame must reach both native fills");
            require(baseline.fills.get(0).color == 0xFF808080 && baseline.fills.get(1).color == 0xFF000000,
                    "literal selected colors");
            require(baseline.fills.get(0).covers(435, 65) && baseline.fills.get(1).covers(436, 66),
                    "independent visible exterior/interior sentinels");
            observations.add("SETUP_PASS " + component + " visible gray/black native fills=" + baseline.fills);
            RecordingGraphics ordinary = invoke(host, frame, ordinaryControl(view(component, false, 150, 10, false)), null, false);
            require(ordinary.fills.size() == 1, "ordinary frame lambda binding");
            Fill ordinaryFill = ordinary.fills.get(0);
            require(ordinaryFill.color == 0x665A8FCB && new Bounds(435, 65, 411, 52).equals(ordinaryFill.clip)
                    && ordinaryFill.covers(435, 65) && !ordinaryFill.covers(435, 64), "ordinary actual wrapper clipping positive");
            observations.add("SETUP_PASS " + component + " ordinary actual wrapper/lambda snapshot=" + ordinaryFill);
            for (boolean feedback : new boolean[]{false, true}) {
                if (feedback && component == AppearanceProviders.Component.SKIN) continue;
                for (boolean top : new boolean[]{true, false}) {
                    int height = top ? 150 : feedback ? 130 : 110;
                    ViewSpec source = view(component, true, height, top ? 10 : 0, feedback);
                    Bounds literalViewport = new Bounds(435, 65, 411,
                            top ? (feedback ? 32 : 52) : 12);
                    require(source.clipFor("providers.row.OFFLINE").orElseThrow().equals(literalViewport),
                            "presenter viewport differs from independent literal " + literalViewport);
                    List<String> focusTargets = new ArrayList<>();
                    focusTargets.add(null);
                    source.widgets().stream().filter(w -> w.enabled()
                            && ViewHostPolicy.belongsToProviderRow(w.id(), "OFFLINE"))
                            .map(ViewSpec.Widget::id).forEach(focusTargets::add);
                    for (String focus : focusTargets) {
                        for (boolean selected : focus == null ? new boolean[]{true} : new boolean[]{true, false}) {
                            ViewSpec current = view(component, selected, height, top ? 10 : 0, feedback);
                            RecordingGraphics graphics = invoke(host, frame, current, focus, focus != null);
                            require(graphics.fills.size() == 2, "row/descendant binding must reach both fills " + focus);
                            int expected = focus == null ? 0xFF808080 : 0xFFFFFFFF;
                            require(graphics.fills.get(0).color == expected && graphics.fills.get(1).color == 0xFF000000,
                                    "literal focus frame colors " + focus);
                            for (int layer = 0; layer < 2; layer++) {
                                Fill fill = graphics.fills.get(layer);
                                int rowTop = top ? 55 : 65;
                                require(fill.left == 435 + layer && fill.top == rowTop + layer
                                        && fill.right == 846 - layer && fill.bottom == rowTop + 36 - layer,
                                        "unchanged literal fill bounds layer=" + layer);
                                require(fill.covers(435 + layer, 65 + layer), "independent inside sentinel layer=" + layer);
                                require(coverage(fill, literalViewport, false), "inside coverage missing layer=" + layer);
                                boolean outside = coverage(fill, literalViewport, true);
                                int outsideY = top ? 64 : literalViewport.bottom();
                                require(outside == fill.covers(435 + layer, outsideY), "outside coverage must match independent edge sentinel");
                                if (outside) defects++;
                                observations.add((outside ? "OUTSIDE_FILL" : "CLIPPED_FILL") + " component=" + component
                                        + " edge=" + (top ? "top" : "bottom") + " feedback=" + feedback
                                        + " selected=" + selected + " focus=" + focus + " layer=" + layer
                                        + " outsideSentinel=" + (435 + layer) + "," + outsideY
                                        + " viewport=" + literalViewport + " fill=" + fill);
                            }
                            cases++;
                        }
                    }
                }
            }
            for (BuiltinProvider provider : component == AppearanceProviders.Component.SKIN
                    ? List.of(BuiltinProvider.MINECRAFT) : List.of(BuiltinProvider.MINECRAFT, BuiltinProvider.OPTIFINE)) {
                var providers = AppearanceProviders.initial().enable(component, provider);
                int index = provider == BuiltinProvider.MINECRAFT ? 1 : 2;
                for (boolean top : new boolean[]{true, false}) {
                    double offset = index * 40 + (top ? 10 : 0);
                    ViewSpec source = providerView(providers, provider, component, false, 110, offset, false);
                    Bounds viewport = new Bounds(435, 65, 411, 12);
                    require(source.clipFor("providers.row." + provider).orElseThrow().equals(viewport), "additional provider literal viewport");
                    for (var descendant : source.widgets()) {
                        if (!descendant.enabled() || !ViewHostPolicy.belongsToProviderRow(descendant.id(), provider.name())) continue;
                        if (descendant.kind() == ViewSpec.WidgetKind.PASSIVE_INDICATOR)
                            require(source.navigationNodes().stream().filter(n -> n.id().equals(descendant.id()))
                                    .findFirst().orElseThrow().activationActionId().isEmpty(), "passive focus must not acquire an action");
                        for (boolean selected : new boolean[]{true, false}) {
                            ViewSpec current = providerView(providers, provider, component, selected, 110, offset, false);
                            RecordingGraphics graphics = invoke(host, frame, current, descendant.id(), true);
                            require(graphics.fills.size() == 2 && graphics.fills.get(0).color == 0xFFFFFFFF
                                    && graphics.fills.get(1).color == 0xFF000000, "additional descendant literal frame " + descendant.id());
                            for (int layer = 0; layer < 2; layer++) {
                                Fill fill = graphics.fills.get(layer);
                                require(fill.covers(435 + layer, 65 + layer), "additional descendant inside sentinel " + descendant.id());
                                boolean outside = coverage(fill, viewport, true);
                                if (outside) defects++;
                                observations.add((outside ? "OUTSIDE_FILL" : "CLIPPED_FILL") + " component=" + component
                                        + " edge=" + (top ? "top" : "bottom") + " selected=" + selected
                                        + " focus=" + descendant.id() + " layer=" + layer + " viewport=" + viewport + " fill=" + fill);
                            }
                            cases++;
                        }
                    }
                }
            }
            ViewSpec unselected = view(component, false, 240, 0, false);
            require(invoke(host, frame, unselected, "providers.row.OFFLINE", false).fills.isEmpty(),
                    "pointer-only unselected focus must not create keyboard frame");
        }
        Files.writeString(work.resolve("observations.txt"), String.join("\n", observations) + "\n");
        System.out.println("SETUP_PASS actual compiled host " + args[1] + " cases=" + cases + " outsideLayers=" + defects);
        if (args[2].equals("characterize")) return;
        require(defects == 0, "BEHAVIORAL_RED: actual host recorded " + defects + " out-of-viewport exterior/interior fills");
        additionalControls(host, frame, observations);
        Files.writeString(work.resolve("observations.txt"), String.join("\n", observations) + "\n");
    }
}

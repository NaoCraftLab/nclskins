package com.naocraftlab.skins.buildlogic.probe;

import com.naocraftlab.skins.client.CenteredPlayerPreviewGeometry;
import com.naocraftlab.skins.client.CurrentPlayerAppearanceSource;
import com.naocraftlab.skins.client.GameSessionTokenSource;
import com.naocraftlab.skins.client.OuterLayerVisibility;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.client.TextureRegistry;
import com.naocraftlab.skins.core.config.MenuPreviewPlacement;
import com.naocraftlab.skins.runtime.Bounds;
import com.naocraftlab.skins.runtime.ClientOperations;
import com.naocraftlab.skins.runtime.ClientRuntime;
import com.naocraftlab.skins.runtime.ClientSnapshot;
import com.naocraftlab.skins.runtime.NativeMenuRuntimeFixture;
import com.naocraftlab.skins.runtime.PreviewModelFitStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;

public final class NativeMenuFamilyProbe {
    static ClientRuntime runtime;
    static boolean available = true;
    static boolean failDraw;
    static int draws, flushes, lighting, clips, depth, poses;
    static float scale;
    static String material;
    static Model drawn;
    static final List<Map<String, Object>> definitions = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        check(args.length == 2, "family and genuine FULL producer manifest required");
        String family = args[0];
        check(Set.of("playerinfo", "skinlookup", "submission").contains(family), "known menu family");
        var manifest = com.google.gson.JsonParser.parseString(Files.readString(Path.of(args[1]))).getAsJsonObject();
        var selected = manifest.getAsJsonArray("cases").asList().stream()
                .map(com.google.gson.JsonElement::getAsJsonObject)
                .filter(row -> row.get("case").getAsString().equals("cpm-tall-translated-jump"))
                .findFirst().orElseThrow();
        String content = selected.get("contentKey").getAsString();
        var key = new PreviewModelFitStore.Key(new UUID(0, 801), content.split("/")[0],
                com.naocraftlab.skins.core.model.SkinVariant.CLASSIC);
        var storage = new com.naocraftlab.skins.core.storage.NclSkinsStorage(
                Path.of(manifest.getAsJsonObject("numericRoots").get(content).getAsString()),
                new com.naocraftlab.skins.core.png.PngValidator(), java.time.Clock.systemUTC());
        var stored = new com.naocraftlab.skins.runtime.infrastructure.PreviewModelFitStorageAdapter(storage).load(key);
        check(stored.status() == PreviewModelFitStore.Status.PRESENT
                && stored.value().fit().normalization() < 0.5, "genuine durable FULL prerequisite");
        var handle = new TextureRegistry.TextureHandle("nclskins:menu/" + content.split("/")[0], 64, 64);
        var operations = (ClientOperations) Proxy.newProxyInstance(NativeMenuFamilyProbe.class.getClassLoader(),
                new Class<?>[]{ClientOperations.class}, (p, method, values) -> {
            if (Set.of("close", "onCapeObservation", "closeOptiFineCapes").contains(method.getName())) return null;
            if (method.getName().equals("sessionIdentity")) {
                return new GameSessionTokenSource.SessionIdentity(new UUID(0, 801), "Fixture");
            }
            throw new AssertionError("menu operations " + method.getName());
        });
        var store = new PreviewModelFitStore() {
            public Result load(Key key) {
                throw new AssertionError("menu numeric load");
            }
            public Result create(Key key, Value value, java.util.function.BooleanSupplier current) {
                throw new AssertionError("menu numeric create");
            }
        };
        var rows = new ArrayList<Map<String, Object>>();
        var current = new CurrentPlayerAppearanceSource.PlayerAppearance[]{
                new CurrentPlayerAppearanceSource.PlayerAppearance(handle, SkinModel.CLASSIC, Optional.empty())};
        var mask = new OuterLayerVisibility[]{OuterLayerVisibility.allVisible()};
        try (var cold = coldMenu(operations, store, () -> {
            if (current[0] == null) throw new IllegalStateException("synthetic unavailable appearance");
            return current[0];
        }, mask)) {
            runtime = cold;
            if (family.equals("submission")) {
                submission(cold, handle, current, mask, rows);
            } else {
                immediate(family, cold, handle, current, mask, rows);
            }
        } finally {
            runtime = null;
            available = true;
            failDraw = false;
        }
        Path output = Path.of(System.getProperty("probe.dir"));
        Files.writeString(output.resolve("native-menu-cases.json"), new com.google.gson.Gson().toJson(rows));
        Files.writeString(output.resolve("native-menu-definitions.json"), new com.google.gson.Gson().toJson(definitions));
        Files.writeString(output.resolve("full-prerequisite.json"), new com.google.gson.Gson().toJson(Map.of(
                "manifest", args[1], "content", content, "normalization", stored.value().fit().normalization())));
        System.out.println("NATIVE_MENU_FAMILY_PASS family=" + family + " cases=" + rows.size() + " GalleryEver=false");
    }

    static void immediate(String family, ClientRuntime cold, TextureRegistry.TextureHandle handle,
            CurrentPlayerAppearanceSource.PlayerAppearance[] current, OuterLayerVisibility[] mask,
            List<Map<String, Object>> rows) throws Exception {
        var loader = new ImmediateLoader(family);
        Class<?> menu = loader.loadClass(loader.prefix + "NclSkinsMenuPreview");
        for (boolean pause : List.of(false, true)) {
            var screen = pause ? new Pause() : new Title();
            check((boolean) menu.getMethod("supports", Screen.class).invoke(null, screen), "actual title/pause supports");
            check(!(boolean) menu.getMethod("supports", Screen.class).invoke(null, new Screen()), "foreign screen rejected");
            Object host = allocate(menu);
            set(host, "screen", screen);
            set(host, "placement", MenuPreviewPlacement.RIGHT);
            var action = new Action();
            set(host, "action", action);
            if (family.equals("playerinfo")) {
                set(host, "previewState", allocate(loader.loadClass(loader.prefix + "NclSkinsMenuPreview$PreviewState")));
            } else {
                set(host, "renderer", loader.loadClass(loader.prefix + "SimplePreviewRenderer")
                        .getConstructor(Client.class).newInstance(new Client()));
            }
            var render = menu.getMethod("render", Graphics.class, int.class, int.class, float.class);
            for (String scenario : List.of("own", "unavailable", "no-appearance", "no-anchor", "draw-failure", "replacement")) {
                reset();
                available = !scenario.equals("unavailable");
                screen.anchor = !scenario.equals("no-anchor");
                failDraw = scenario.equals("draw-failure");
                boolean replacement = scenario.equals("replacement");
                var expected = replacement ? new TextureRegistry.TextureHandle("nclskins:menu/replacement", 64, 64) : handle;
                current[0] = scenario.equals("no-appearance") ? null : new CurrentPlayerAppearanceSource.PlayerAppearance(
                        expected, replacement ? SkinModel.SLIM : SkinModel.CLASSIC, Optional.empty());
                mask[0] = replacement ? OuterLayerVisibility.noneVisible() : OuterLayerVisibility.allVisible();
                Throwable failure = null;
                try {
                    invoke(render, host, new Graphics(), 0, 0, 0F);
                } catch (RuntimeException | Error thrown) {
                    failure = thrown;
                }
                boolean positive = scenario.equals("own") || replacement;
                check(positive ? failure == null : scenario.equals("draw-failure")
                        ? failure instanceof IllegalStateException
                            && failure.getMessage().equals("synthetic menu draw failure")
                            && failure.getSuppressed().length == 0
                        : failure == null, "expected menu outcome " + scenario + " " + failure);
                check(clips == 0 && poses == 0, "menu/scissor/pose cleanup " + scenario);
                if (positive) {
                    check(draws == 1 && flushes == 1 && lighting == 1 && depth == 1,
                            "complete immediate draw/flush/lighting/depth");
                    check(expected.location().equals(material)
                            && drawn == (replacement ? Models.INSTANCE.slim : Models.INSTANCE.classic),
                            "own current source/model/material");
                    check(Math.abs(scale - CenteredPlayerPreviewGeometry.fittedScale(110, 0.92F)) < 0.001,
                            "baked baseline ignores FULL");
                    check(action.bounds.equals(Optional.of(new Bounds(531, 142, 104, 116))), "actual menu layout");
                    check(drawn.hat.visible == !replacement && drawn.jacket.visible == !replacement
                            && drawn.leftPants.visible == !replacement, "own mask");
                } else {
                    check(draws == (failDraw ? 1 : 0) && flushes == 0 && depth == 0,
                            "negative has no successful foreign draw");
                    check(lighting == (failDraw ? 1 : 0), "native thrown-draw lighting restoration");
                }
                check(cold.snapshot().lifecycle() == ClientSnapshot.Lifecycle.NEW, "no Gallery/init");
                rows.add(Map.of("family", family, "role", pause ? "pause" : "title", "case", scenario,
                        "draws", draws, "flushes", flushes, "cleanup", clips == 0 && poses == 0,
                        "depth", depth, "scale", scale, "source", material == null ? "" : material));
            }
        }
    }
    static ClientRuntime coldMenu(ClientOperations operations, PreviewModelFitStore store,
            CurrentPlayerAppearanceSource source, OuterLayerVisibility[] mask) {
        return NativeMenuRuntimeFixture.create(operations, store, source, mask);
    }

    static void submission(ClientRuntime cold, TextureRegistry.TextureHandle handle,
            CurrentPlayerAppearanceSource.PlayerAppearance[] current, OuterLayerVisibility[] mask,
            List<Map<String, Object>> rows) throws Exception {
        for (boolean pause : List.of(false, true)) {
            for (String scenario : List.of("own", "no-appearance", "no-anchor",
                    "draw-failure", "retired-target", "changed-source")) {
                Map<String, Object> row;
                try (var selected = new CpmBakedDisplayProbe.SelectedFallback(false)) {
                    boolean changed = scenario.equals("changed-source");
                    boolean admitted = !Set.of("no-appearance", "no-anchor").contains(scenario);
                    var expected = changed ? new TextureRegistry.TextureHandle("nclskins:menu/replacement", 64, 64) : handle;
                    var model = changed ? SkinModel.SLIM : SkinModel.CLASSIC;
                    current[0] = scenario.equals("no-appearance") ? null
                            : new CurrentPlayerAppearanceSource.PlayerAppearance(expected, model, Optional.empty());
                    mask[0] = changed ? OuterLayerVisibility.noneVisible() : OuterLayerVisibility.allVisible();
                    selected.menu(cold, pause, !scenario.equals("no-anchor"), admitted);
                    check(selected.primaryDraws == 0 && selected.materialPasses == 0, "submission queue is not consumed");
                    if (admitted) selected.assertMenuAppearance(expected, model, mask[0]);
                    if (scenario.equals("retired-target")) selected.retireMenuTarget();
                    selected.failDraw = scenario.equals("draw-failure");
                    Throwable failure = null;
                    try {
                        selected.consume();
                    } catch (RuntimeException | Error thrown) {
                        failure = thrown;
                    }
                    if (scenario.equals("own") || changed) {
                        check(failure == null && selected.primaryDraws == 1 && selected.materialPasses == 1,
                                "actual submission native material/draw " + failure);
                        check(expected.location().equals(selected.source), "own submission source");
                        check(Math.abs(selected.scale - CenteredPlayerPreviewGeometry.fittedScale(110, 0.92F)) < 0.001,
                                "submission baseline ignores FULL");
                    } else if (admitted) {
                        check(failure instanceof IllegalStateException && failure.getSuppressed().length == 0
                                && selected.materialPasses == 0, "submission failure/retirement refuses material " + failure);
                        if (selected.failDraw) check(failure.getMessage().equals("synthetic selected fallback draw failure"),
                                "exact thrown draw branch");
                    } else {
                        check(failure == null && selected.primaryDraws == 0 && selected.materialPasses == 0,
                                "nonadmitted menu cannot draw material");
                    }
                    check(cold.snapshot().lifecycle() == ClientSnapshot.Lifecycle.NEW, "submission never Gallery/init");
                    definitions.addAll(selected.definitions());
                    selected.freezeDefinitions(Path.of(System.getProperty("probe.dir")).resolve("native-bound"));
                    row = Map.of("family", "submission", "role", pause ? "pause" : "title", "case", scenario,
                            "draws", selected.primaryDraws, "materials", selected.materialPasses, "scale", selected.scale,
                            "source", selected.source == null ? "" : selected.source, "cleanup", true);
                }
                rows.add(row);
            }
        }
    }
    static void reset() {
        draws = flushes = lighting = clips = depth = poses = 0;
        scale = 1;
        material = null;
        drawn = null;
    }

    static Object allocate(Class<?> type) throws Exception {
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return ((sun.misc.Unsafe) field.get(null)).allocateInstance(type);
    }

    static void set(Object owner, String name, Object value) throws Exception {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }

    static Object invoke(Method method, Object owner, Object... args) {
        try {
            method.setAccessible(true);
            return method.invoke(owner, args);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Error error) throw error;
            if (failure.getCause() instanceof RuntimeException exception) throw exception;
            throw new AssertionError(failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static final class RuntimePort {
        public static RuntimePort instance() { return new RuntimePort(); }
        public boolean menuPreviewAvailable() { return available; }
        public ClientRuntime runtime() { return Objects.requireNonNull(NativeMenuFamilyProbe.runtime); }
    }
    public static class Widget extends CpmBakedDisplayProbe.MenuWidget {
        public int getRight() { return getX() + getWidth(); }
        public int getBottom() { return getY() + getHeight(); }
    }
    public static final class Action extends Widget {
        Optional<Bounds> bounds = Optional.empty();
        public boolean isFocused() { return false; }
        public void applyBounds(Optional<Bounds> value) { bounds = value; }
    }
    public static class Screen {
        public int width = 854, height = 480;
        boolean anchor = true;
        public List<Widget> children() { return anchor ? List.of(new Widget()) : List.of(); }
    }
    public static final class Title extends Screen {}
    public static final class Pause extends Screen {}
    public static final class Client {
        public static final boolean ON_OSX = false;
    }
    public static final class Graphics {
        public void fill(int x, int y, int right, int bottom, int color) {}
        public void enableScissor(int x, int y, int right, int bottom) { clips++; }
        public void disableScissor() { clips--; }
        public Pose pose() { return new Pose(); }
        public Buffers bufferSource() { return new Buffers(); }
        public void flush() { flushes++; }
    }
    public static final class Pose {
        public void pushPose() { poses++; }
        public void popPose() { poses--; }
        public void scale(float x, float y, float z) { scale *= Math.abs(x); }
        public void translate(float x, float y, float z) {}
        public void translate(double x, double y, double z) {}
        public void mulPose(org.joml.Quaternionf rotation) {}
        public void mulPose(org.joml.Quaternionfc rotation) {}
        public Entry last() { return new Entry(); }
    }
    public static final class Entry {
        public org.joml.Matrix4f pose() {
            return new org.joml.Matrix4f() {
                @Override public org.joml.Matrix4f scale(float x, float y, float z) {
                    scale *= Math.abs(x);
                    return super.scale(x, y, z);
                }
            };
        }
        public org.joml.Matrix3f normal() { return new org.joml.Matrix3f(); }
    }
    public static final class Model {
        public float attackTime;
        public boolean crouching, riding, young;
        public final CpmBakedDisplayProbe.Part head = new CpmBakedDisplayProbe.Part(), body = new CpmBakedDisplayProbe.Part(),
                rightArm = new CpmBakedDisplayProbe.Part(), leftArm = new CpmBakedDisplayProbe.Part(),
                rightLeg = new CpmBakedDisplayProbe.Part(), leftLeg = new CpmBakedDisplayProbe.Part();
        public final CpmBakedDisplayProbe.Part hat = new CpmBakedDisplayProbe.Part(), jacket = new CpmBakedDisplayProbe.Part(),
                leftSleeve = new CpmBakedDisplayProbe.Part(), rightSleeve = new CpmBakedDisplayProbe.Part(),
                leftPants = new CpmBakedDisplayProbe.Part(), rightPants = new CpmBakedDisplayProbe.Part();
        public void setAllVisible(boolean visible) {}
        public Material renderType(CpmPreviewScopeProbe.Location location) { return new Material(location.value()); }
        public void renderToBuffer(Pose pose, CpmBakedDisplayProbe.Vertex vertex, int light, int overlay, int color) { emit(); }
        public void renderToBuffer(Pose pose, CpmBakedDisplayProbe.Vertex vertex, int light, int overlay,
                float red, float green, float blue, float alpha) { emit(); }
        void emit() {
            draws++;
            drawn = this;
            if (failDraw) throw new IllegalStateException("synthetic menu draw failure");
        }
    }
    public static final class Models {
        static final Models INSTANCE = new Models();
        public final Model classic = new Model(), slim = new Model(), elytra = new Model();
        public final CpmBakedDisplayProbe.Part classicCloak = new CpmBakedDisplayProbe.Part(),
                slimCloak = new CpmBakedDisplayProbe.Part(), elytraRoot = new CpmBakedDisplayProbe.Part();
        public static Models instance() { return INSTANCE; }
    }
    public record Material(String source) {
        public static Material entityTranslucent(CpmPreviewScopeProbe.Location id) { return new Material(id.value()); }
    }
    public static final class Buffers {
        public CpmBakedDisplayProbe.Vertex getBuffer(Material value) {
            material = value.source();
            return new CpmBakedDisplayProbe.Vertex() {};
        }
        public void endBatch() { flushes++; }
    }
    public static final class Light {
        public static void setupForEntityInInventory() {}
        public static void setupFor3DItems() { lighting++; }
        public static void setShaderLights(org.joml.Vector3f first, org.joml.Vector3f second) {}
        public static void clear(int mask, boolean osx) {
            check(mask == 256, "native depth clear mask");
            depth++;
        }
    }
    static final class ImmediateLoader extends ClassLoader {
        final String prefix;
        final Map<String, Class<?>> types = new HashMap<>();

        ImmediateLoader(String family) {
            super(NativeMenuFamilyProbe.class.getClassLoader());
            prefix = "com.naocraftlab.skins.compat.client.resourcelocation." + family + ".";
            types.put("net/minecraft/client/Minecraft", Client.class);
            types.put("net/minecraft/client/gui/GuiGraphics", Graphics.class);
            types.put("net/minecraft/client/gui/screens/Screen", Screen.class);
            types.put("net/minecraft/client/gui/screens/TitleScreen", Title.class);
            types.put("net/minecraft/client/gui/screens/PauseScreen", Pause.class);
            types.put("net/minecraft/client/gui/components/AbstractWidget", Widget.class);
            types.put("net/minecraft/client/gui/components/events/GuiEventListener", Widget.class);
            types.put("com/naocraftlab/skins/compat/gui/immediate/MenuScreenSupport$NoChromeAction", Action.class);
            types.put((prefix + "ImmediateClientRuntime").replace('.', '/'), RuntimePort.class);
            types.put((prefix + "VanillaPreviewModels").replace('.', '/'), Models.class);
            types.put("net/minecraft/client/model/EntityModel", Model.class);
            types.put("net/minecraft/client/model/HumanoidModel", Model.class);
            types.put("net/minecraft/client/model/PlayerModel", Model.class);
            types.put("net/minecraft/client/model/ElytraModel", Model.class);
            types.put("net/minecraft/client/model/geom/ModelPart", CpmBakedDisplayProbe.Part.class);
            types.put("net/minecraft/resources/ResourceLocation", CpmPreviewScopeProbe.Location.class);
            types.put("net/minecraft/client/renderer/RenderType", Material.class);
            types.put("net/minecraft/client/renderer/MultiBufferSource$BufferSource", Buffers.class);
            types.put("com/mojang/blaze3d/vertex/VertexConsumer", CpmBakedDisplayProbe.Vertex.class);
            types.put("com/mojang/blaze3d/vertex/PoseStack", Pose.class);
            types.put("com/mojang/blaze3d/vertex/PoseStack$Pose", Entry.class);
            types.put("com/mojang/blaze3d/platform/Lighting", Light.class);
            types.put("com/mojang/blaze3d/systems/RenderSystem", Light.class);
        }

        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            String support = "com.naocraftlab.skins.compat.gui.immediate.MenuScreenSupport";
            if (!(name.startsWith(prefix + "SimplePreviewRenderer") || name.startsWith(prefix + "NclSkinsMenuPreview")
                    || name.equals(prefix + "PreviewPoseScale") || name.equals(prefix + "BakedPlayerPose")
                    || name.equals(prefix + "BakedPreviewLighting") || name.equals(support))) {
                return super.loadClass(name, resolve);
            }
            Class<?> found = findLoadedClass(name);
            if (found == null) {
                var origin = getParent().getResource(name.replace('.', '/') + ".class");
                if (origin == null) throw new ClassNotFoundException(name);
                try (var input = origin.openStream()) {
                    byte[] bytes = input.readAllBytes();
                    var node = new ClassNode();
                    new ClassReader(bytes).accept(node, 0);
                    if (name.equals(prefix + "NclSkinsMenuPreview")) {
                        node.interfaces.clear();
                        node.methods.removeIf(method -> !Set.of("render", "supports", "updateLayout", "<clinit>")
                                .contains(method.name) && !method.name.startsWith("lambda$updateLayout$"));
                    }
                    if (name.equals(support)) {
                        node.methods.removeIf(method -> !method.name.equals("topMainAction")
                                && !method.name.startsWith("lambda$topMainAction$"));
                    }
                    var writer = new ClassWriter(0);
                    node.accept(new ClassRemapper(writer, new Remapper(Opcodes.ASM9) {
                        @Override public String map(String internal) {
                            var type = types.get(internal);
                            return type == null ? internal : Type.getInternalName(type);
                        }
                    }));
                    byte[] bound = writer.toByteArray();
                    var boundNode = new ClassNode();
                    new ClassReader(bound).accept(boundNode, 0);
                    definitions.add(Map.of("class", name, "origin", origin.toString(), "original", sha(bytes),
                            "bound", sha(bound), "members", boundNode.methods.stream()
                                    .map(method -> method.name + method.desc).toList()));
                    var file = Path.of(System.getProperty("probe.dir"), "native-bound", name.replace('.', '/') + ".class");
                    Files.createDirectories(file.getParent());
                    Files.write(file, bound);
                    found = defineClass(name, bound, 0, bound.length);
                } catch (java.io.IOException failure) {
                    throw new ClassNotFoundException(name, failure);
                }
            }
            if (resolve) resolveClass(found);
            return found;
        }
    }

    static String sha(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new AssertionError(failure);
        }
    }
}

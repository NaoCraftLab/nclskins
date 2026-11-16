package com.naocraftlab.skins.buildlogic.probe;

import com.tom.cpl.config.ModConfigFile;
import com.tom.cpl.math.MatrixStack;
import com.tom.cpl.math.Vec2i;
import com.tom.cpl.math.Vec3f;
import com.tom.cpl.render.VBuffers;
import com.tom.cpl.render.VertexBuffer;
import com.tom.cpl.tag.AllTagManagers;
import com.tom.cpl.util.Image;
import com.tom.cpl.util.ImageIO.IImageIO;
import com.tom.cpl.util.ILogger;
import com.tom.cpm.shared.MinecraftClientAccess;
import com.tom.cpm.shared.MinecraftCommonAccess;
import com.tom.cpm.shared.MinecraftObjectHolder;
import com.tom.cpm.shared.animation.*;
import com.tom.cpm.shared.config.Player;
import com.tom.cpm.shared.definition.*;
import com.tom.cpm.shared.editor.Editor;
import com.tom.cpm.shared.editor.Exporter;
import com.tom.cpm.shared.editor.anim.AnimFrame;
import com.tom.cpm.shared.editor.anim.EditorAnim;
import com.tom.cpm.shared.editor.elements.*;
import com.tom.cpm.shared.definition.Link;
import com.tom.cpm.shared.model.*;
import com.tom.cpm.shared.model.builtin.VanillaPlayerModel;
import com.tom.cpm.shared.model.render.*;
import com.tom.cpm.shared.skin.PlayerTextureLoader;
import com.tom.cpm.shared.skin.TextureProvider;
import com.tom.cpm.shared.util.PlayerModelLayer;
import com.tom.cpm.shared.config.ResourceLoader.ResourceEncoding;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

public final class CpmNeutralGeometryProbe {
    private static ModelDefinitionLoader<UUID> loader;
    private static ModConfigFile config;
    private static AllTagManagers tags;
    private static final List<Runnable> GAME = new ArrayList<>();

    private static final class Subject extends Player<UUID> {
        public SkinType getSkinType() { return SkinType.DEFAULT; }
        public boolean isClientPlayer() { return true; }
        public String getName() { return "SyntheticNeutral"; }
        public UUID getUUID() { return new UUID(0, 822); }
        public UUID getGameProfile() { return getUUID(); }
        protected PlayerTextureLoader initTextures() {
            return new PlayerTextureLoader(null) {
                protected java.util.concurrent.CompletableFuture<Void> load0() { return java.util.concurrent.CompletableFuture.completedFuture(null); }
                public java.util.concurrent.CompletableFuture<Image> getTexture(com.tom.cpm.shared.skin.TextureType type) {
                    return java.util.concurrent.CompletableFuture.completedFuture(type == com.tom.cpm.shared.skin.TextureType.SKIN ? new Image(SkinType.DEFAULT.getSkinTexture()) : null);
                }
            };
        }
        public void updateFromPlayer(AnimationState state, UUID player) { throw new AssertionError("world read forbidden"); }
        public void updateFromModel(AnimationState state, Object model) { throw new AssertionError("world mutation forbidden"); }
    }

    @SuppressWarnings("unchecked")
    private static <T> T host(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> switch (method.getName()) {
            case "getConfig" -> config;
            case "getLogger" -> host(ILogger.class);
            case "getBlockStateHandler" -> CpmHostTags.BLOCKS;
            case "getItemStackHandler" -> CpmHostTags.ITEMS;
            case "getEntityTypeHandler" -> CpmHostTags.ENTITIES;
            case "getBiomeHandler", "getVersionCheck" -> null;
            case "isInGame" -> false;
            case "getConnectedServer" -> "fixture.invalid";
            case "getBuiltinTags" -> tags;
            case "getDefinitionLoader" -> loader;
            case "getImageIO" -> IMAGES;
            case "i18nFormat" -> args[0];
            case "error" -> { for (var arg : args) if (arg instanceof Throwable failure) failure.printStackTrace(); throw new AssertionError("dependency logged error " + Arrays.toString(args)); }
            case "executeOnGameThread" -> { GAME.add((Runnable) args[0]); yield null; }
            default -> throw new AssertionError("forbidden host operation: " + type.getSimpleName() + "." + method.getName());
        });
    }

    private static final IImageIO IMAGES = new IImageIO() {
        public Image read(File file) { throw new AssertionError("user files forbidden"); }
        public Image read(InputStream input) throws IOException {
            var png = javax.imageio.ImageIO.read(input);
            return new Image(png.getRGB(0, 0, png.getWidth(), png.getHeight(), null, 0, png.getWidth()), png.getWidth());
        }
        public void write(Image image, File file) { throw new AssertionError("external image write forbidden"); }
        public void write(Image image, OutputStream output) throws IOException {
            var png = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
            png.setRGB(0, 0, image.getWidth(), image.getHeight(), image.getData(), 0, image.getWidth());
            javax.imageio.ImageIO.write(png, "PNG", output);
        }
        public Vec2i getSize(InputStream input) throws IOException {
            var png = javax.imageio.ImageIO.read(input);
            return new Vec2i(png.getWidth(), png.getHeight());
        }
    };

    private static final class Renderer extends DirectModelRenderManager<Object> {
        protected DirectHolderPlayer<Object> createHolder(VanillaPlayerModel model, String argument) {
            return new DirectHolderPlayer<>(this, model, argument) {
                protected void setupRenderSystem(Object terminal, TextureSheetType sheet) {
                    for (var mode : RenderMode.values()) renderTypes.put(mode, new VBuffers.NativeRenderType(0));
                }
                protected void bindTexture(Object terminal, TextureProvider texture, TextureSheetType sheet) {}
                protected void bindDefaultTexture(Object terminal, TextureSheetType sheet) {}
            };
        }
    }

    private static final class Extents implements VertexBuffer {
        double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
        double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        private float x, y, z;
        int vertices;
        public VertexBuffer pos(float x, float y, float z) { this.x = x; this.y = y; this.z = z; return this; }
        public VertexBuffer tex(float u, float v) { return this; }
        public VertexBuffer color(float r, float g, float b, float a) { return this; }
        public VertexBuffer normal(float x, float y, float z) { return this; }
        public void endVertex() {
            check(Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(z), "finite dependency vertices");
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z); vertices++;
        }
        public void finish() {}
        double height() { check(vertices > 0 && maxY > minY, "nonempty positive height"); return maxY - minY; }
        double centerY() { return (minY + maxY) / 2; }
        public String toString() { return "[" + minX + "," + minY + "," + minZ + ";" + maxX + "," + maxY + "," + maxZ + "] vertices=" + vertices; }
    }

    private record Fixture(boolean decor, boolean equipment, boolean jump, boolean small, float scale, float translation, PlayerModelParts marker) {
        Fixture(boolean decor, boolean equipment, boolean jump, boolean small, float scale, float translation) {
            this(decor, equipment, jump, small, scale, translation, null);
        }
    }

    private static Image fixture(Fixture fixture) {
        var editor = new Editor(); editor.setUI(host(com.tom.cpl.gui.UI.class)); editor.skinType = SkinType.DEFAULT;
        var roots = new ArrayList<ModelElement>();
        for (var part : PlayerModelParts.VALUES) if (part != PlayerModelParts.CUSTOM_PART) {
            var root = new ModelElement(editor, ElementType.ROOT_PART, part);
            root.hidden = true; editor.elements.add(root); roots.add(root);
            cube(editor, root, new Vec3f(0, part == fixture.marker() ? -48 : 0, 0), new Vec3f(2, 2, 2));
        }
        var body = roots.stream().filter(r -> r.typeData == PlayerModelParts.BODY).findFirst().orElseThrow();
        if (!fixture.small()) {
            var tall = cube(editor, body, new Vec3f(0, -24, 0), new Vec3f(8, 24, 8));
            tall.meshScale = new Vec3f(1, 2, 1);
        }
        if (fixture.decor()) {
            var parent = cube(editor, body, new Vec3f(0, -32, 0), new Vec3f(0, 0, 0));
            parent.rotation = new Vec3f(0, 0, 90);
            cube(editor, parent, new Vec3f(-16, 0, 0), new Vec3f(16, 8, 8));
        }
        if (fixture.equipment()) for (var part : List.of(RootModelType.CAPE, RootModelType.ELYTRA_LEFT, RootModelType.ELYTRA_RIGHT)) {
            var root = new ModelElement(editor, ElementType.ROOT_PART, part);
            root.hidden = true; editor.elements.add(root);
            cube(editor, root, new Vec3f(0, -48, 0), new Vec3f(24, 24, 24)).meshScale = new Vec3f(1, 8, 1);
        }
        editor.scalingElem.enabled = true;
        editor.scalingElem.scale = new Vec3f(fixture.scale(), fixture.scale(), fixture.scale());
        editor.scalingElem.pos = new Vec3f(0, fixture.translation(), 0);
        if (fixture.jump()) {
            var animation = new EditorAnim(editor, "standing", AnimationType.POSE, false);
            animation.pose = VanillaPose.STANDING; animation.duration = 1000;
            for (int offset : new int[]{-48, -32}) {
                var frame = new AnimFrame(animation);
                for (var root : roots) frame.setPos(root, new Vec3f(0, offset, 0));
                animation.getFrames().add(frame);
            }
            editor.animations.add(animation);
        }
        editor.vanillaSkin = SkinType.DEFAULT.getSkinTexture();
        var skin = editor.textures.get(TextureSheetType.SKIN);
        skin.setDefaultImg(editor.vanillaSkin); skin.setImage(new Image(editor.vanillaSkin));
        skin.provider.size = new Vec2i(64, 64); skin.setEdited(false);
        var exported = new AtomicReference<Image>();
        Exporter.exportSkin(editor, editor.ui, exported::set, false);
        check(exported.get() != null, "real public exporter produced carrier");
        return exported.get();
    }

    private static ModelElement cube(Editor editor, ModelElement parent, Vec3f position, Vec3f size) {
        var cube = new ModelElement(editor); cube.parent = parent; cube.pos = position; cube.size = size;
        cube.name = "Synthetic"; parent.children.add(cube);
        return cube;
    }

    private static ModelDefinition load(Image image, Subject subject) throws IOException {
        var definition = loader.loadModel(new Image(image), subject);
        check(definition != null, "real carrier importer admitted fixture"); definition.resolveAll();
        check(definition.getError() == null, "resolved dependency definition"); return definition;
    }

    private static Extents render(ModelDefinition definition) {
        return render(definition, null, 1, 0);
    }

    private static Extents render(ModelDefinition definition, PlayerModelLayer layer, float normalization, float centerY) {
        var renderer = new Renderer(); var model = renderer.getModel(SkinType.DEFAULT); model.reset();
        var extent = new Extents(); var buffers = new VBuffers(type -> extent).setBatched(true);
        try {
            var stack = new MatrixStack(); stack.scale(normalization, normalization, normalization); stack.translate(0, -centerY, 0);
            if (layer == null) {
                renderer.setupSkin(new Object(), model, buffers, definition, new AnimationState());
                model.render(stack, extent);
            } else {
                renderer.setupLayer(new Object(), layer, model, buffers, definition, new AnimationState());
                model.renderLayer(stack, extent, layer);
            }
            buffers.finishAll();
            System.out.println("dependency body " + extent); extent.height(); return extent;
        } finally { renderer.unbindModel(model); }
    }

    private static void animate(ModelDefinition definition, long millis) {
        var animations = definition.getAnimations().getPoseAnimations(VanillaPose.STANDING);
        check(animations != null && !animations.isEmpty(), "serialized standing animation imported");
        definition.resetAnimationPos();
        for (var trigger : animations) for (var animation : trigger.animations)
            animation.animate(new AnimationState(), millis, definition, AnimationEngine.AnimationMode.PLAYER);

    }

    private static void near(double actual, double expected, String message) {
        check(Math.abs(actual - expected) < 0.0001, message + ": expected=" + expected + " actual=" + actual);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    private static String liveState(ModelDefinition definition) {
        var state = new StringBuilder();
        for (var part : PlayerModelParts.VALUES) definition.getModelElementFor(part).forEach(root -> appendState(root, state));
        return state.toString();
    }

    private static void appendState(RenderedCube cube, StringBuilder state) {
        var position = cube.getTransformPosition(); var rotation = cube.getTransformRotation(); var scale = cube.getRenderScale();
        state.append(cube.getId()).append(':');
        for (float value : new float[]{position.x, position.y, position.z, rotation.x, rotation.y, rotation.z, scale.x, scale.y, scale.z})
            state.append(Float.floatToRawIntBits(value)).append(',');
        state.append(cube.isVisible()).append(';');
        if (cube.children != null) for (var child : cube.children) appendState(child, state);
    }

    private static Map<String, Object> measurement(String name, Extents extent, double normalization) {
        return Map.of("case", name, "height", extent.height(), "centerY", extent.centerY(), "normalization", normalization,
                "min", List.of(extent.minX, extent.minY, extent.minZ), "max", List.of(extent.maxX, extent.maxY, extent.maxZ));
    }

    private static Extents measured(Fixture fixture, Subject subject) throws IOException {
        var definition = load(fixture(fixture), subject);
        try { return render(definition); } finally { definition.cleanup(); }
    }

    public static void main(String[] args) throws Exception {
        var output = Path.of(args[0]); Files.createDirectories(output);
        config = new ModConfigFile(output.resolve("synthetic-config.json").toFile()) {
            public void save() { throw new AssertionError("configuration mutation forbidden"); }
        };
        MinecraftObjectHolder.setCommonObject(host(MinecraftCommonAccess.class));
        MinecraftObjectHolder.setClientObject(host(MinecraftClientAccess.class));
        tags = new AllTagManagers();
        loader = new ModelDefinitionLoader<>(id -> { throw new AssertionError("profile load forbidden"); }, id -> id, id -> "SyntheticNeutral") {
            public InputStream load(Link link, ResourceEncoding encoding, ModelDefinition definition) { throw new AssertionError("resource transport forbidden"); }
            public void putLocalResource(Link link, byte[] data) { throw new AssertionError("cache mutation forbidden"); }
        };
        var subject = new Subject(); var image = fixture(new Fixture(true, true, true, false, 1, 0));
        try (var stream = Files.newOutputStream(output.resolve("authored-jump-decor-equipment.png"))) { IMAGES.write(image, stream); }
        var live = load(image, subject);
        var assignment = java.util.concurrent.CompletableFuture.completedFuture(live);
        subject.setModelDefinition(assignment, false);
        var installedAssignment = subject.getDefinitionFuture();
        check(installedAssignment.join() == live, "real subject owns resolved live definition before measurement");
        var rows = new ArrayList<Map<String, Object>>();
        try {
            animate(live, 0);
            var firstJump = render(live); var state = liveState(live); var animations = live.getAnimations();
            var neutral = load(image, subject);
            try {
                check(neutral != live && neutral.getAnimations() != animations, "independent imported definition and animation owner");
                check(neutral.getModelElementFor(PlayerModelParts.BODY).get() != live.getModelElementFor(PlayerModelParts.BODY).get(), "independent BODY root");
                var actual = args.length > 1 && args[1].equals("sample-live") ? render(live)
                        : args.length > 1 && args[1].equals("drop-decor") ? measured(new Fixture(false, false, false, false, 1, 0), subject)
                        : args.length > 1 && args[1].equals("include-equipment") ? render(neutral, PlayerModelLayer.CAPE, 1, 0) : render(neutral);
                near(actual.height(), 4.5, "all native body transforms including nested rotated decoration");
                near(actual.centerY(), -0.75, "authored neutral must not sample initial jump at time zero");
                check(Math.abs(firstJump.centerY() - actual.centerY()) > 2, "serialized initial jump changes the live stream by several blocks");
                near(render(neutral, PlayerModelLayer.CAPE, 1, 0).height(), 12, "genuine accessory geometry is nonempty and larger than BODY");
                check(render(neutral, PlayerModelLayer.ELYTRA, 1, 0).height() > actual.height(), "both elytra roots emit excluded geometry");
                rows.add(measurement("decor+equipment+jump-neutral", actual, 1));
                var without = measured(new Fixture(true, false, false, false, 1, 0), subject);
                near(without.height(), actual.height(), "native CAPE/ELYTRA do not affect BODY height");
                near(without.centerY(), actual.centerY(), "native CAPE/ELYTRA do not affect BODY center");
                rows.add(measurement("without-equipment", without, 1));
                var undecorated = measured(new Fixture(false, false, false, false, 1, 0), subject);
                near(undecorated.height(), 3, "removing BODY decoration reduces measured geometry");
                rows.add(measurement("without-body-decoration", undecorated, 1));
                for (var part : PlayerModelParts.VALUES) if (part != PlayerModelParts.CUSTOM_PART) {
                    var marked = measured(new Fixture(false, false, false, true, 1, 0, part), subject);
                    double pivot = switch (part) { case LEFT_ARM, RIGHT_ARM -> 0.125; case LEFT_LEG, RIGHT_LEG -> 0.75; default -> 0; };
                    near(marked.minY, -3 + pivot, "aggregate emits authored geometry of " + part);
                    near(marked.maxY, 0.875, "unmarked body roots survive " + part);
                    rows.add(measurement("root-coverage-" + part, marked, 1));
                }
                for (var fixture : List.of(new Fixture(true, false, false, false, 0.5F, 0), new Fixture(true, false, false, false, 2, 0),
                        new Fixture(true, false, false, false, 1, 32), new Fixture(true, false, false, false, 2, 32))) {
                    var definition = load(fixture(fixture), subject);
                    try {
                        near(definition.getScale().getRScale().y, fixture.scale(), "export/import retains authored native render scale");
                        var extents = args.length > 1 && args[1].equals("scale-twice")
                                ? render(definition, null, fixture.scale(), 0) : render(definition);
                        near(extents.height(), 4.5 * fixture.scale(), "native scale enters emitted geometry exactly once");
                        near(extents.centerY(), -0.75 * fixture.scale() + fixture.translation() / 16, "native translation and scale order");
                        rows.add(measurement("scale=" + fixture.scale() + ",translation=" + fixture.translation(), extents, 1));
                    } finally { definition.cleanup(); }
                }
                var ordinary = new VanillaPlayerModel(SkinType.DEFAULT); var reference = new Extents();
                ordinary.render(new MatrixStack(), reference);
                rows.add(measurement("CPM-builtin-reference-not-current-Minecraft-renderer", reference, 1));
                for (boolean small : List.of(false, true)) {
                    var definition = load(fixture(new Fixture(!small, false, false, small, 1, 32)), subject);
                    try {
                        var extent = render(definition); double normalization = Math.min(1, reference.height() / extent.height());
                        var consumed = render(definition, null, (float) normalization, (float) extent.centerY());
                        near(consumed.height(), small ? extent.height() : reference.height(), "numeric height consumed by real dependency renderer");
                        near(consumed.centerY(), 0, "numeric neutral center consumed by real dependency renderer");
                        if (small) near(normalization, 1, "small body must not be enlarged");
                        rows.add(measurement(small ? "small-normalized-dependency-consumer" : "tall-normalized-dependency-consumer", consumed, normalization));
                    } finally { definition.cleanup(); }
                }
            } finally { neutral.cleanup(); }
            check(liveState(live).equals(state) && live.getAnimations() == animations, "independent measurement leaves live pose and registry unchanged");
            check(subject.getDefinitionFuture() == installedAssignment && installedAssignment.join() == live, "live definition assignment unchanged");
            var firstAgain = render(live); near(firstAgain.centerY(), firstJump.centerY(), "live initial pose survives measurement and cleanup");
            animate(live, 500); var next = render(live);
            check(Math.abs(next.centerY() - firstJump.centerY()) > 0.2, "subsequent real imported animation still moves after independent measurement");
            rows.add(measurement("subsequent-serialized-live-animation", next, 1));
        } finally { live.cleanup(); }
        Files.writeString(output.resolve("measurements.json"), new com.google.gson.Gson().toJson(Map.of("component", "CPM public exporter/importer + DirectModelRenderManager", "f0Complete", false, "syntheticHost", true, "cases", rows)));
        System.out.println("CPM authored-neutral discriminator component passed; F0 not complete");
    }
}

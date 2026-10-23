package com.naocraftlab.skins.buildlogic.probe;

import com.naocraftlab.skins.client.CpmModelFileConversion.*;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.compat.cpm.*;
import com.tom.cpl.util.Image;
import com.tom.cpm.shared.MinecraftClientAccess;
import com.tom.cpm.shared.editor.Editor;
import com.tom.cpm.shared.editor.project.*;
import com.tom.cpm.shared.editor.util.GetFreeSkinSlots;
import com.tom.cpm.shared.editor.util.ModelDescription.CopyProtection;
import com.tom.cpm.shared.io.*;
import com.tom.cpm.shared.model.*;
import com.tom.cpm.shared.parts.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class CpmProjectDefaultsProbe {
    private static Path fixtures, output;
    private static final UUID CURRENT = new UUID(0, 1), FOREIGN = new UUID(0, 456);
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static Object field(Object object, String name) throws Exception {
        var field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    private static Editor editor(SkinType type, String name) throws Exception {
        var field = CpmProjectBindingProbe.class.getDeclaredField("fixtures"); field.setAccessible(true); field.set(null, fixtures);
        var method = CpmProjectBindingProbe.class.getDeclaredMethod("editor", SkinType.class, String.class); method.setAccessible(true);
        return (Editor) method.invoke(null, type, name);
    }
    private static void user(UUID id) throws Exception {
        var method = CpmSessionPublicationProbe.class.getDeclaredMethod("user", String.class, String.class, UUID.class); method.setAccessible(true);
        Object value = id == null ? null : method.invoke(null, "Synthetic", "", id);
        Class.forName("net.minecraft.client.Minecraft").getField("current").set(null, value);
    }
    private static Raster base(SkinType type) {
        var pixels = type.getSkinTexture().getData().clone(); pixels[8 + 8 * 64] = 0x00123456;
        return new Raster(type == SkinType.SLIM ? SkinModel.SLIM : SkinModel.CLASSIC, pixels);
    }
    private static Path save(Editor editor, String name) throws Exception {
        var project = new ProjectFile(); ProjectIO.saveProject(editor, project);
        Path path = output.resolve(name + ".cpmproject"); project.save(path.toFile()).join(); return path;
    }
    private static byte[] read(Raster raster, int count) throws IOException {
        return new SkinDataInputStream(new Image(raster.argb(), 64), MinecraftClientAccess.get().getDefinitionLoader().getTemplate(),
                raster.variant() == SkinModel.SLIM ? SkinType.SLIM.getChannel() : SkinType.DEFAULT.getChannel()).readNBytes(count);
    }
    private static List<IModelPart> parts(byte[] payload, int expectedCubes) throws IOException {
        var checksum = new ChecksumInputStream(new ByteArrayInputStream(payload, 1, payload.length - 1)); var reader = new IOHelper(checksum);
        ModelPartDefinition definition = null;
        while (true) {
            var part = reader.readObjectBlock(ModelPartType.VALUES, (kind, in) -> kind.getFactory().create(in, null));
            if (part instanceof ModelPartEnd) break;
            if (part instanceof ModelPartDefinition value) definition = value;
        }
        checksum.checkSum(); check(definition != null, "native definition retained");
        var writer = new IOHelper(); definition.write(writer); var in = writer.flip();
        int count = in.readVarInt(); check(count == expectedCubes, "authored geometry count: " + count); var cube = Cube.loadDefinitionCube(in);
        check(cube.size.x == 3 && cube.size.y == 5 && cube.size.z == 7, "authored geometry dimensions");
        for (int i = 1; i < count; i++) Cube.loadDefinitionCube(in);
        var result = new ArrayList<IModelPart>();
        while (true) { var part = in.readObjectBlock(ModelPartType.VALUES, (kind, data) -> kind.getFactory().create(data, null));
            if (part instanceof ModelPartEnd) break; if (part != null) result.add(part); }
        return result;
    }
    private static void fidelity(Raster raster, byte[] expected, boolean animation, UUID lock) throws Exception {
        fidelity(raster, expected, animation, lock, 1);
    }
    private static void fidelity(Raster raster, byte[] expected, boolean animation, UUID lock, int expectedCubes) throws Exception {
        byte[] payload = read(raster, expected.length);
        check(Arrays.equals(payload, expected), "raw project native carrier exact payload and selected channel");
        check(raster.argb()[8 + 8 * 64] == 0x00123456, "transparent RGB fallback retained");
        var parts = parts(payload, expectedCubes);
        check(parts.stream().anyMatch(p -> p.getType() == ModelPartType.ANIMATION_DATA) == animation, "animation block not dropped");
        if (animation) {
            Object part = parts.stream().filter(p -> p.getType() == ModelPartType.ANIMATION_DATA).findFirst().orElseThrow();
            var data = (Map<?, ?>) field(part, "parsedData"); check(data.size() == 1, "one custom animation retained");
            Object frame = data.values().iterator().next();
            check(field(frame, "frames").equals(1) && field(frame, "duration").equals(1000), "native animation duration and keyframe count");
            check(field(frame, "layerCtrl").equals(true) && (Integer) field(frame, "gid") > 0, "custom gesture encoding remains playable");
            var positions = (com.tom.cpl.math.Vec3f[][]) field(frame, "pos");
            check(positions.length == 1 && positions[0][0].x == 9 && positions[0][0].y == 8 && positions[0][0].z == 7,
                    "independent authored animation position keyframe");
        }
        var locks = parts.stream().filter(ModelPartUUIDLockout.class::isInstance).toList();
        check(locks.size() == (lock == null ? 0 : 1), "UUID lock count preserved");
        if (lock != null) { var w = new IOHelper(); locks.get(0).write(w); check(w.flip().readUUID().equals(lock), "exact current or explicit UUID lock preserved"); }
        var skin = parts.stream().filter(p -> p.getType() == ModelPartType.SKIN).findFirst().orElseThrow();
        var w = new IOHelper(); skin.write(w); var r = w.flip(); r.read2s(); var image = r.readImage(); image.doReadImage();
        try (var zip = new java.util.zip.ZipFile(fixtures.resolve((animation ? "animation" : "canonical") + ".cpmproject").toFile());
                var png = zip.getInputStream(zip.getEntry("skin.png"))) {
            var authored = javax.imageio.ImageIO.read(png);
            check(Arrays.equals(image.getImage().getData(), authored.getRGB(0, 0, 64, 64, null, 0, 64)), "every authored texture pixel retained");
        }
    }
    private static void animation(SkinType type, boolean authored) throws Exception {
        var adapter = new CpmModelFileAdapter(); var e = editor(type, "animation");
        e.animations.get(0).layerControlled = true;
        e.animations.get(0).pose = null;
        e.animations.get(0).filename = "g_synthetic.json";
        e.animations.get(0).type = com.tom.cpm.shared.animation.AnimationType.GESTURE;
        e.animEnc = GetFreeSkinSlots.getDefault(e);
        if (authored) e.animEnc.defaultLayerValue.put(com.tom.cpm.shared.editor.util.PlayerSkinLayer.HAT, true);
        var settings = e.animEnc;
        var expected = adapter.prepareProject(e, 17, Map.of(), base(type)).compiled();
        check(e.animEnc == settings, "authored settings identity unchanged");
        if (!authored) {
            e.animEnc = null;
            var previousSkin = e.vanillaSkin;
            adapter.prepareProject(e, 17, Map.of(), base(type));
            check(e.animEnc == null && e.vanillaSkin == previousSkin && e.skinType == type,
                    "successful export restores temporary native defaults and selected skin state");
        }
        Path path = save(e, "animation-" + type + "-" + authored); byte[] source = Files.readAllBytes(path);
        for (boolean stream : new boolean[]{false, true}) {
            try (var admitted = stream ? adapter.admit(new ByteArrayInputStream(source)) : adapter.admitFile(path)) {
                var result = adapter.prepare(admitted, base(type), 17);
                check(result instanceof Inline, "missing encoding automatically exports without dialog");
                fidelity(((Inline) result).raster(), expected, true, null);
            }
        }
        check(Arrays.equals(source, Files.readAllBytes(path)), "raw source project immutable");
        if (!authored) runtime(e, type, null, "animation");
    }
    private static void impossible(SkinType type) throws Exception {
        var adapter = new CpmModelFileAdapter();
        Path source = output.resolve("animation-" + type + "-false.cpmproject");
        int[] occupied = new int[4096]; Arrays.fill(occupied, 0x01010203);
        try (var admitted = adapter.admitFile(source)) {
            Failure rejection = null;
            try { adapter.prepare(admitted, new Raster(base(type).variant(), occupied), 17); }
            catch (Failure failure) { rejection = failure; }
            check(rejection != null && rejection.reason() == Reason.MODEL_UNSUPPORTED, "native impossible default is typed unsupported, not animation loss");
        }
        var e = editor(type, "animation"); e.animations.get(0).pose = null;
        e.animations.get(0).type = com.tom.cpm.shared.animation.AnimationType.GESTURE;
        e.animations.get(0).layerControlled = true;
        e.animEnc = new com.tom.cpm.shared.editor.anim.AnimationEncodingData();
        var encoding = e.animEnc; boolean rejected = false;
        try { adapter.prepareProject(e, 17, Map.of(), base(type)); }
        catch (CpmProjectImportScope.UnsupportedData failure) { rejected = true; }
        check(rejected && e.animEnc == encoding && encoding.freeLayers.isEmpty(), "authored impossible settings rejected without replacing them");
        check(!CpmProjectImportScope.owns(e), "failed native compiler scope retired");
    }
    private static byte[] png(Raster raster) throws Exception {
        var image = new java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 64, 64, raster.argb(), 0, 64);
        var output = new ByteArrayOutputStream(); javax.imageio.ImageIO.write(image, "png", output); return output.toByteArray();
    }
    private static void runtime(Editor editor, SkinType type, UUID lock, String name) throws Exception {
        var variant = type == SkinType.SLIM ? com.naocraftlab.skins.core.model.SkinVariant.SLIM : com.naocraftlab.skins.core.model.SkinVariant.CLASSIC;
        var fallback = new Raster(base(type).variant(), type.getSkinTexture().getData());
        byte[] bundled = png(fallback);
        if (lock != null) editor.description.uuid = lock;
        var expected = new CpmModelFileAdapter().prepareProject(editor, 17, Map.of(), fallback);
        if (lock != null) editor.description.uuid = null;
        for (boolean url : new boolean[]{false, true}) {
            Path root = Files.createTempDirectory(output, name + "-runtime-" + type + "-" + url + "-");
            Path source = save(editor, name + "-source-" + type + "-" + url), base = root.resolve("base.png");
            Files.write(base, bundled);
            int requests = com.naocraftlab.skins.core.api.CpmUrlIngressFixture.requests;
            String hash = com.naocraftlab.skins.runtime.CpmCallerHarness.saveThroughRuntime(new CpmModelFileAdapter(), bundled,
                    source, base, root.resolve("saved"), variant, url ? "url-automatic" : "automatic");
            var storage = new com.naocraftlab.skins.core.storage.NclSkinsStorage(root.resolve("saved"), new com.naocraftlab.skins.core.png.PngValidator(), java.time.Clock.systemUTC());
            byte[] selected = storage.readAsset(hash);
            check(Arrays.equals(selected, new com.naocraftlab.skins.core.png.PngValidator().projectImport(png(expected.inline().orElseThrow())).pngBytes()),
                    "actual file/public URL caller and Save retain complete independently prepared carrier");
            check(com.naocraftlab.skins.core.api.CpmUrlIngressFixture.requests - requests == (url ? 1 : 0), "exact explicit URL acquisition only");
            Files.delete(base); Files.deleteIfExists(source); check(!Files.exists(source), "source removed before fresh reopen");
            Files.write(root.resolve("selected.png"), selected);
            var command = new ArrayList<String>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Djava.awt.headless=true", "-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"), "-cp", System.getProperty("java.class.path"),
                    "com.naocraftlab.skins.buildlogic.probe.CpmProjectDependencyProbe", "reopen", root.toString(), variant.name()));
            Files.writeString(root.resolve("reopen-command.json"), new com.google.gson.Gson().toJson(command));
            var child = new ProcessBuilder(command).inheritIO().start();
            if (!child.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) { child.destroyForcibly(); child.waitFor(); throw new AssertionError("bounded fresh reopen"); }
            check(child.exitValue() == 0, "source-free fresh first Apply exact selected PNG");
            Files.writeString(root.resolve("reopen-exit.txt"), Integer.toString(child.exitValue()));
        }
    }
    private static void preview(Raster raster, boolean allowed) throws Exception {
        var previousClient = MinecraftClientAccess.get();
        var previousCommon = com.tom.cpm.shared.MinecraftCommonAccess.get();
        CpmBehaviorProbe.initializeHost(output.resolve("lock-preview-config.json").toFile());
        var events = CpmBehaviorProbe.class.getDeclaredMethod("events"); events.setAccessible(true);
        UUID cache = new UUID(0, 904);
        String location = "fixture:exported-lock-" + raster.variant() + "-" + allowed;
        try (var registration = com.naocraftlab.skins.runtime.CapeProjection.installEvents(
                (com.naocraftlab.skins.runtime.CapeProjection.Events) events.invoke(null))) {
            var image = Image.loadFrom(new ByteArrayInputStream(png(raster)));
            com.naocraftlab.skins.runtime.CapeProjection.skinTextureReady(location, image.getData(), new Object());
            var source = com.naocraftlab.skins.runtime.CapeProjection.effectiveSkin(cache, CURRENT, location, raster.variant(), true);
            var loader = (com.tom.cpm.shared.definition.ModelDefinitionLoader<UUID>) MinecraftClientAccess.get().getDefinitionLoader();
            var player = loader.loadPlayer(cache, com.tom.cpm.shared.definition.ModelDefinitionLoader.PLAYER_UNIQUE);
            var definition = player.getDefinitionFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
            check(definition instanceof OwnedCpmDefinition, "exported lock carrier creates actual owned preview definition");
            check(player.getUUID().equals(CURRENT) && !player.isClientPlayer(), "preview retains real subject without global self identity");
            check(Arrays.equals(source.pixels(), raster.argb()) && Arrays.equals(player.getTextures().getTexture(
                    com.tom.cpm.shared.skin.TextureType.SKIN).get(5, java.util.concurrent.TimeUnit.SECONDS).getData(), raster.argb()),
                    "owned native decoder receives exact exported carrier pixels");
            definition.startResolve();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (definition.getResolveState() == com.tom.cpm.shared.definition.ModelDefinition.ModelLoadingState.RESOLVING
                    && System.nanoTime() < deadline) Thread.sleep(1);
            if (allowed) check(definition.getError() == null && definition.doRender(), "current UUID exported carrier resolves for owned preview");
            else check(definition.getResolveState() == com.tom.cpm.shared.definition.ModelDefinition.ModelLoadingState.SAFETY_BLOCKED
                    && definition.getError() instanceof com.tom.cpm.shared.definition.SafetyException failure
                    && failure.getBlockReason() == com.tom.cpm.shared.definition.SafetyException.BlockReason.UUID_LOCK && !definition.doRender(),
                    "foreign UUID exported carrier is rejected specifically by native UUID lock");
            Object body = new Object();
            var manager = new com.tom.cpm.shared.model.render.ModelRenderManager<Object, Object, Object, Object>() {{
                holders.put(body, new RedirectHolder<>(this, body) {
                    protected void swapIn0() {}
                    protected void swapOut0() {}
                });
            }};
            var appearance = new com.naocraftlab.skins.client.PreviewRenderer.PreviewAppearance(
                    new com.naocraftlab.skins.client.TextureRegistry.TextureHandle(location, 64, 64), raster.variant(),
                    Optional.empty(), com.naocraftlab.skins.client.PreviewRenderer.CapeMode.OFF, true);
            try (var frame = CpmFrame.open(appearance)) {
                check(CpmFrame.accepts((OwnedCpmDefinition) definition), "frame recognizes exact exported source identity");
                manager.bindModel(body, null, null, definition, player, new com.tom.cpm.shared.animation.AnimationState());
                check(manager.isBound(body) == allowed, "only current UUID exported carrier reaches native frame binding");
            }
            check(!manager.isBound(body), "exported carrier frame binding retired");
            System.out.println("PASS exported owned preview " + raster.variant() + " " + (allowed ? "current-lock" : "foreign-lock"));
        } finally {
            com.naocraftlab.skins.runtime.CapeProjection.retireSkin(cache);
            var drain = CpmBehaviorProbe.class.getDeclaredMethod("drain"); drain.setAccessible(true); drain.invoke(null);
            com.tom.cpm.shared.MinecraftObjectHolder.setClientObject(previousClient);
            com.tom.cpm.shared.MinecraftObjectHolder.setCommonObject(previousCommon);
        }
    }
    private static void uuidPreview(SkinType type) throws Exception {
        user(CURRENT);
        var adapter = new CpmModelFileAdapter();
        for (boolean allowed : new boolean[]{true, false}) {
            var editor = editor(type, "canonical");
            editor.description = new com.tom.cpm.shared.editor.util.ModelDescription();
            editor.description.copyProtection = CopyProtection.UUID_LOCK;
            editor.description.uuid = allowed ? null : FOREIGN;
            Path project = save(editor, "preview-lock-" + type + "-" + allowed);
            byte[] original = Files.readAllBytes(project);
            Raster carrier;
            if (allowed) {
                try (var admitted = adapter.admitFile(project)) {
                    var result = adapter.prepare(admitted, base(type), 17);
                    check(result instanceof Inline, "raw protected project exports an inline carrier for preview");
                    carrier = ((Inline) result).raster();
                }
                editor.description.uuid = CURRENT;
            } else carrier = adapter.prepareProject(editor, 17, Map.of(), base(type)).inline().orElseThrow();
            byte[] expected = adapter.prepareProject(editor, 17, Map.of(), base(type)).compiled();
            fidelity(carrier, expected, false, allowed ? CURRENT : FOREIGN);
            preview(carrier, allowed);
            check(Arrays.equals(original, Files.readAllBytes(project)), "preview does not rewrite protected project");
        }
    }
    private static void uuid(SkinType type) throws Exception {
        user(CURRENT); var adapter = new CpmModelFileAdapter(); var e = editor(type, "canonical");
        e.description = new com.tom.cpm.shared.editor.util.ModelDescription(); e.description.copyProtection = CopyProtection.UUID_LOCK;
        e.description.uuid = CURRENT;
        byte[] expected = adapter.prepareProject(e, 17, Map.of(), base(type)).compiled();
        e.description.uuid = null; Path path = save(e, "lock-" + type); byte[] source = Files.readAllBytes(path);
        for (boolean stream : new boolean[]{false, true}) {
            try (var admitted = stream ? adapter.admit(new ByteArrayInputStream(source)) : adapter.admitFile(path)) {
                var result = adapter.prepare(admitted, base(type), 17);
                check(result instanceof Inline, "null UUID automatically binds current user");
                fidelity(((Inline) result).raster(), expected, false, CURRENT);
            }
        }
        check(Arrays.equals(source, Files.readAllBytes(path)), "UUID source project immutable");
        runtime(e, type, CURRENT, "uuid");
        e.description.uuid = FOREIGN;
        var explicit = adapter.prepareProject(e, 17, Map.of(), base(type));
        fidelity(explicit.inline().orElseThrow(), explicit.compiled(), false, FOREIGN);
        check(e.description.uuid.equals(FOREIGN), "explicit lock is never rewritten");
        for (String change : List.of("missing", "stale", "aba")) {
            user(CURRENT);
            try (var admitted = adapter.admitFile(path)) {
                if (change.equals("missing")) user(null); else { user(FOREIGN); if (change.equals("aba")) user(CURRENT); }
                boolean rejected = false;
                try { adapter.prepare(admitted, base(type), 17); }
                catch (Failure failure) { rejected = failure.reason() == Reason.PORTABILITY_UNSUPPORTED; }
                check(rejected, "missing/stale/ABA current binding rejected");
            }
        }
        user(null);
        boolean missing = false;
        try { adapter.admitFile(path).close(); } catch (Failure failure) { missing = failure.reason() == Reason.PORTABILITY_UNSUPPORTED; }
        check(missing, "missing identity rejected at admission without player loader");
        var noLock = editor(type, "canonical");
        check(adapter.prepareProject(noLock, 17, Map.of(), base(type)).inline().isPresent(), "no-lock export needs no identity");
        check(adapter.prepareProject(e, 17, Map.of(), base(type)).inline().isPresent(), "explicit foreign lock needs no identity");
        for (boolean aba : new boolean[]{false, true}) {
            user(CURRENT); e.description.uuid = null;
            var beforeSkin = e.vanillaSkin;
            exportBoundary = () -> {
                try { user(FOREIGN); if (aba) user(CURRENT); }
                catch (Exception failure) { throw new AssertionError(failure); }
            };
            boolean rejected = false;
            try { adapter.prepareProject(e, 17, Map.of(), base(type)); }
            catch (CpmProjectImportScope.MissingData failure) { rejected = true; }
            finally { exportBoundary = null; }
            check(rejected && e.description.uuid == null && e.vanillaSkin == beforeSkin && !CpmProjectImportScope.owns(e),
                    "during-export identity change or ABA rejects and restores transient editor");
        }
        user(CURRENT);
    }
    private static int foreignTextureLoads;
    private static void borrowedAuxiliary(SkinType type) throws Exception {
        user(CURRENT);
        for (boolean authored : new boolean[]{false, true}) {
            var adapter = new CpmModelFileAdapter(); var editor = editor(type, "canonical");
            editor.elements.add(new com.tom.cpm.shared.editor.elements.ModelElement(editor,
                    com.tom.cpm.shared.editor.elements.ElementType.ROOT_PART, RootModelType.CAPE));
            editor.description = new com.tom.cpm.shared.editor.util.ModelDescription();
            editor.description.copyProtection = CopyProtection.UUID_LOCK;
            int[] pixels = new int[64 * 32]; Arrays.fill(pixels, 0xff123abc); pixels[pixels.length - 1] = 0x00123456;
            if (authored) {
                var texture = new com.tom.cpm.shared.editor.ETextures(editor, TextureSheetType.CAPE);
                texture.setImage(new Image(pixels, 64)); texture.provider.size = TextureSheetType.CAPE.getDefSize(); texture.setEdited(true);
                editor.textures.put(TextureSheetType.CAPE, texture);
            }
            Path source = save(editor, "borrowed-cape-" + type + "-" + authored);
            var project = new ProjectFile(); project.load(source.toFile()).join();
            var reference = editor(type, null);
            CpmProjectImportScope.run(reference, 17, Map.of(), () -> { CpmProjectImportScope.loadProject(reference, 17, project); return null; });
            reference.description.uuid = CURRENT;
            var expected = adapter.prepareProject(reference, 17, Map.of(), base(type));
            reference.description.uuid = null;
            try (var admitted = adapter.admitFile(source)) {
                var result = adapter.prepare(admitted, base(type), 17);
                check(result instanceof Inline, "null-lock project with auxiliary texture exports without fetching profile");
                var raster = ((Inline) result).raster();
                fidelity(raster, expected.compiled(), false, CURRENT, 2);
                var exported = parts(read(raster, expected.compiled().length), 2);
                boolean cape = false, texture = false;
                for (var part : exported) {
                    if (part instanceof ModelPartRoot && field(part, "type") == RootModelType.CAPE) cape = true;
                    if (part instanceof ModelPartTexture && field(part, "texType") == TextureSheetType.CAPE) {
                        texture = true;
                        var image = ((com.tom.cpm.shared.skin.TextureProvider) field(part, "image")).getImage();
                        int[] expectedPixels = pixels;
                        if (!authored) {
                            try (var resource = com.tom.cpm.shared.editor.Generators.class.getResourceAsStream("/assets/cpm/textures/template/cape.png")) {
                                expectedPixels = Image.loadFrom(resource).getData();
                            }
                        }
                        check(Arrays.equals(expectedPixels, image.getData()), "authored auxiliary pixels or native bundled fallback retained without personal texture substitution");
                    }
                }
                check(cape && texture, "native auxiliary root and texture retained");
            }
            runtime(reference, type, CURRENT, "auxiliary-" + authored);
        }
        var foreign = editor(type, "canonical");
        boolean delegated = false;
        try { com.tom.cpm.shared.editor.Generators.loadTextures(foreign, com.tom.cpm.shared.editor.elements.RootGroups.CAPE, foreign.textures::put); }
        catch (Error expected) { if (!expected.getClass().getSimpleName().equals("Effect")) throw expected; delegated = true; }
        check(delegated, "foreign editor retains native current-player loading rather than import cancellation");
        foreignTextureLoads++;
    }
    private static Runnable exportBoundary;
    public static void exported() { if (exportBoundary != null) exportBoundary.run(); }
    public static void forbiddenCredential() { throw new AssertionError("project export must not read a credential"); }
    private static void setup(Path root) throws Exception {
        CpmSessionPublicationProbe.main(new String[]{"setup", root.toString()});
        for (String name : List.of("com/tom/cpm/shared/editor/Exporter", "net/minecraft/client/User")) {
            var node = new org.objectweb.asm.tree.ClassNode();
            try (var in = ClassLoader.getSystemResourceAsStream(name + ".class")) { new org.objectweb.asm.ClassReader(in).accept(node, 0); }
            int count = 0;
            for (var method : node.methods) {
                if (name.endsWith("Exporter") && method.name.equals("prepareDefinition")) {
                    for (var instruction : method.instructions.toArray()) if (instruction.getOpcode() == org.objectweb.asm.Opcodes.ARETURN) {
                        method.instructions.insertBefore(instruction, new org.objectweb.asm.tree.MethodInsnNode(org.objectweb.asm.Opcodes.INVOKESTATIC,
                                "com/naocraftlab/skins/buildlogic/probe/CpmProjectDefaultsProbe", "exported", "()V", false)); count++;
                    }
                } else if (name.endsWith("User") && method.name.equals("getAccessToken") && method.desc.equals("()Ljava/lang/String;")) {
                    method.instructions.insert(new org.objectweb.asm.tree.MethodInsnNode(org.objectweb.asm.Opcodes.INVOKESTATIC,
                            "com/naocraftlab/skins/buildlogic/probe/CpmProjectDefaultsProbe", "forbiddenCredential", "()V", false)); count++;
                }
            }
            check(count == 1, "exact native test boundary");
            var writer = new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS); node.accept(writer);
            Path file = root.resolve(name + ".class"); Files.createDirectories(file.getParent()); Files.write(file, writer.toByteArray());
        }
    }
    public static void main(String[] args) throws Exception {
        if (args[0].equals("setup")) { setup(Path.of(args[1])); return; }
        fixtures = Path.of(args[0]); output = Path.of(args[1]); Files.createDirectories(output);
        CpmImportFeasibilityProbe.main(new String[]{args[0], args[1], "project-binding-setup"});
        var raw = CpmProjectBindingProbe.class.getDeclaredMethod("rawResources"); raw.setAccessible(true); raw.invoke(null);
        if (args.length > 2 && args[2].equals("missing-texture-binding")) {
            var editor = editor(SkinType.SLIM, "canonical");
            editor.elements.add(new com.tom.cpm.shared.editor.elements.ModelElement(editor,
                    com.tom.cpm.shared.editor.elements.ElementType.ROOT_PART, RootModelType.CAPE));
            boolean rejected = false;
            try { new CpmModelFileAdapter().admitFile(save(editor, "unbound")).close(); }
            catch (Failure failure) { rejected = failure.reason() == Reason.CPM_UNAVAILABLE; }
            check(rejected, "untransformed texture boundary fails closed before ProjectIO profile loading");
            System.out.println("PASS missing texture binding"); return;
        }
        int failed = 0;
        for (var type : SkinType.VANILLA_TYPES) for (String test : args.length > 2 ? List.of(args[2]) : List.of("authored", "missing", "impossible", "uuid", "borrowed")) {
            try { if (test.equals("uuid-preview")) uuidPreview(type); else if (test.equals("uuid")) uuid(type); else if (test.equals("impossible")) impossible(type); else if (test.equals("borrowed")) borrowedAuxiliary(type); else animation(type, test.equals("authored")); System.out.println("PASS " + test + " " + type); }
            catch (Throwable failure) { failed++; System.out.println("FAIL " + test + " " + type + " " + failure.getClass().getSimpleName()); failure.printStackTrace(); }
        }
        var effects = CpmImportFeasibilityProbe.class.getDeclaredField("effects"); effects.setAccessible(true);
        check(((Map<?, ?>) effects.get(null)).equals(foreignTextureLoads == 0 ? Map.of() : Map.of("MinecraftClientAccess.getClientPlayer", foreignTextureLoads)),
                "only explicit foreign-editor controls call current-player loader; owned imports have no profile/network/UI/log/assignment effects");
        if (failed != 0) throw new AssertionError("project defaults failures: " + failed);
    }
}

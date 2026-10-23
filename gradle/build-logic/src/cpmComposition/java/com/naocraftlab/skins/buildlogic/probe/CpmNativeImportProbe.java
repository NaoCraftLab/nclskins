package com.naocraftlab.skins.buildlogic.probe;

import com.naocraftlab.skins.client.CpmModelFileConversion;
import com.naocraftlab.skins.compat.cpm.CpmModelFileAdapter;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.tom.cpm.shared.io.IOHelper;
import com.tom.cpm.shared.io.ModelFile;
import com.tom.cpm.shared.model.SkinType;
import com.tom.cpm.shared.parts.ModelPartType;
import com.tom.cpm.shared.parts.ModelPartSkinType;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

public final class CpmNativeImportProbe {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void retainSavedState(Path dir) throws Exception {
        try (var files = Files.walk(dir.resolve("library"))) {
            for (Path file : files.toList()) {
                check(!Files.isSymbolicLink(file), "Retained source-free Save state has no synthetic aliases");
                Path copy = dir.resolve("reopen-library").resolve(dir.resolve("library").relativize(file));
                if (Files.isDirectory(file)) Files.createDirectories(copy); else Files.copy(file, copy);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        Path fixtures = Path.of(args[0]), output = Path.of(args[1]);
        if (args.length > 2 && args[2].endsWith("reopen")) {
            CpmModelConversionProbe.main(new String[]{fixtures.toString(), output.resolve("reopen-host").toString(), "environment-only"});
            byte[] bundled = Files.readAllBytes(output.resolve("bundled.png"));
            boolean project = args[2].startsWith("project");
            boolean pairs = args[2].contains("pairs");
            boolean publication = args[2].contains("publication");
            if (publication) CpmImportFeasibilityProbe.main(new String[]{fixtures.resolveSibling("import-feasibility").toString(), Files.createDirectories(output.resolve("reopen-project-host")).toString(), "project-binding-setup"});
            for (String name : project ? publication ? List.of("dense-atlas") : pairs ? List.of("missing-type") : List.of("canonical", "atlas", "animation", "borrowed") : List.of("unknown-type", "absent-type"))
            for (SkinVariant variant : project && !pairs ? new SkinVariant[]{SkinVariant.SLIM} : SkinVariant.values()) {
                Path dir = output.resolve((project ? "raw-project-" : "") + name + "-" + variant.name());
                check(!Files.exists(dir.resolve("source.any")) && !Files.exists(dir.resolve("project.cpmproject"))
                        && !Files.exists(dir.resolve("base.png")), "Separate process native sources and bases absent");
                String expectedHash = Files.readString(dir.resolve("expected.sha256")).trim();
                var storage = new com.naocraftlab.skins.core.storage.NclSkinsStorage(dir.resolve("reopen-library"), new com.naocraftlab.skins.core.png.PngValidator(), java.time.Clock.systemUTC());
                byte[] saved = storage.readAsset(expectedHash);
                byte[] uploaded = com.naocraftlab.skins.runtime.CpmCallerHarness.explicitTestApply(dir.resolve("reopen-library"), bundled, variant);
                check(Arrays.equals(saved, uploaded), "Separate process exact native carrier reaches actual upload boundary");
                var decoded = javax.imageio.ImageIO.read(new ByteArrayInputStream(uploaded));
                if (!project) check(decoded.getRGB(8, 8) == 0x00123456, "Separate process significant transparent RGB preserved");
                byte[] expectedPayload = Files.readAllBytes(dir.resolve("expected.payload"));
                var image = new com.tom.cpl.util.Image(decoded.getRGB(0, 0, 64, 64, null, 0, 64), 64);
                byte[] actual = new com.tom.cpm.shared.io.SkinDataInputStream(image,
                        com.tom.cpm.shared.MinecraftClientAccess.get().getDefinitionLoader().getTemplate(),
                        variant == SkinVariant.SLIM ? SkinType.SLIM.getChannel() : SkinType.DEFAULT.getChannel()).readNBytes(expectedPayload.length);
                check(Arrays.equals(expectedPayload, actual), "Separate process independent native payload/channel oracle");
                if (publication) CpmProjectCompositionProbe.verifyPublishedCarrier(uploaded,
                        Files.readAllBytes(dir.resolve("expected.wire")), fixtures.resolveSibling("import-feasibility"), SkinType.SLIM);
                System.out.println("PASS separate-process native source-free reopen/application " + name + " " + variant);
            }
            return;
        }
        CpmModelConversionProbe.main(new String[]{fixtures.toString(), output.resolve("setup").toString(), "frozen-input"});
        for (String name : List.of("classic-embedded", "slim-embedded", "unknown-type", "absent-type")) {
            ModelFile nativeModel = ModelFile.load("native-metadata", Files.newInputStream(fixtures.resolve(name + ".cpmmodel")));
            byte[] raw = nativeModel.getDataBlock().clone();
            var reader = new IOHelper(new ByteArrayInputStream(raw, 1, raw.length - 1));
            SkinType found = null;
            while (true) {
                ModelPartType type = reader.readObjectBlock(ModelPartType.VALUES, (kind, block) -> {
                    if (kind == ModelPartType.SKIN_TYPE) {
                        SkinType actual = ((ModelPartSkinType) kind.getFactory().create(block, null)).getSkinType();
                        check(actual == (name.startsWith("classic") ? SkinType.DEFAULT : name.startsWith("slim") ? SkinType.SLIM : SkinType.UNKNOWN), "Independent native metadata type " + name);
                    }
                    return kind;
                });
                if (type == ModelPartType.SKIN_TYPE) found = name.startsWith("classic") ? SkinType.DEFAULT : name.startsWith("slim") ? SkinType.SLIM : SkinType.UNKNOWN;
                if (type == ModelPartType.END) break;
            }
            check(found == (name.equals("absent-type") ? null : name.equals("unknown-type") ? SkinType.UNKNOWN : name.startsWith("slim") ? SkinType.SLIM : SkinType.DEFAULT), "Native metadata presence " + name);
            check(Arrays.equals(raw, nativeModel.getDataBlock()), "Metadata never rewrites raw payload " + name);
        }
        System.out.println("PASS native ModelFile + IOHelper typed metadata with no resolve/player/cache calls");
        if (args.length > 2 && args[2].equals("metadata")) return;
        byte[] bundled = Files.readAllBytes(fixtures.resolve("classic-base.png"));
        Files.write(output.resolve("bundled.png"), bundled);
        if (args.length > 2 && args[2].equals("lifecycle")) {
            for (String phase : List.of("acquire", "convert", "complete"))
            for (String event : List.of("close", "account", "reload", "world", "back")) {
                String mode = "cancel-" + phase + "-" + event;
                com.naocraftlab.skins.runtime.CpmCallerHarness.saveThroughRuntime(new CpmModelFileAdapter(), bundled,
                        fixtures.resolve("classic-embedded.cpmmodel"), fixtures.resolve("classic-base.png"),
                        output.resolve(mode), SkinVariant.CLASSIC, mode);
                System.out.println("PASS actual native lifecycle " + mode);
            }
            com.naocraftlab.skins.runtime.CpmCallerHarness.saveThroughRuntime(new CpmModelFileAdapter(), bundled,
                    fixtures.resolve("classic-embedded.cpmmodel"), fixtures.resolve("classic-base.png"),
                    output.resolve("blocked"), SkinVariant.CLASSIC, "blocked");
            System.out.println("PASS actual pinned conversion blocked inside handle monitor; client close remains responsive");
            return;
        }
        if (args.length > 2 && args[2].startsWith("project")) {
            CpmImportFeasibilityProbe.main(new String[]{fixtures.resolveSibling("import-feasibility").toString(), Files.createDirectories(output.resolve("host")).toString(), "project-binding-setup"});
            var original = new java.net.URLClassLoader(new java.net.URL[]{Path.of(System.getProperty("nclskins.cpm.original")).toUri().toURL()}, null);
            var parent = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(new ClassLoader(parent) {
                @Override public java.io.InputStream getResourceAsStream(String name) {
                    if (name.equals("com/tom/cpm/shared/editor/Exporter.class") || name.equals("com/tom/cpm/shared/editor/template/EditorTemplate.class")) return original.getResourceAsStream(name);
                    return super.getResourceAsStream(name);
                }
            });
            boolean pairs = args[2].contains("pairs"), url = args[2].contains("url");
            for (String name : args[2].contains("publication") ? List.of("dense-atlas") : pairs ? List.of("missing-type") : List.of("canonical", "atlas", "animation", "borrowed"))
            for (SkinVariant variant : pairs ? SkinVariant.values() : new SkinVariant[]{SkinVariant.SLIM}) {
            Path dir = output.resolve("raw-project-" + name + "-" + variant); Files.createDirectories(dir);
            Path source = dir.resolve("project.cpmproject");
            Files.copy(fixtures.resolveSibling("import-feasibility/" + name + ".cpmproject"), source);
            var nativeProject = new com.tom.cpm.shared.editor.project.ProjectFile(); nativeProject.load(source.toFile()).join();
            var nativeEditor = new com.tom.cpm.shared.editor.Editor(); nativeEditor.skinType = SkinType.UNKNOWN;
            nativeEditor.setUI((com.tom.cpl.gui.UI) java.lang.reflect.Proxy.newProxyInstance(com.tom.cpl.gui.UI.class.getClassLoader(), new Class<?>[]{com.tom.cpl.gui.UI.class},
                    (proxy, method, values) -> { if (method.getName().equals("i18nFormat")) return values[0]; throw new AssertionError("Unexpected project UI effect"); }));
            for (var part : com.tom.cpm.shared.model.PlayerModelParts.VALUES)
                if (part != com.tom.cpm.shared.model.PlayerModelParts.CUSTOM_PART)
                    nativeEditor.elements.add(new com.tom.cpm.shared.editor.elements.ModelElement(nativeEditor,
                            com.tom.cpm.shared.editor.elements.ElementType.ROOT_PART, part));
            com.tom.cpm.shared.editor.project.ProjectIO.loadProject(nativeEditor, nativeProject);
            check(nativeEditor.skinType == (pairs ? SkinType.UNKNOWN : SkinType.SLIM), "Actual raw ProjectIO metadata");
            String hash = com.naocraftlab.skins.runtime.CpmCallerHarness.saveThroughRuntime(new CpmModelFileAdapter(), bundled,
                    source, fixtures.resolve("slim-base.png"), dir.resolve("library"), variant,
                    args[2].contains("publication") ? args[2].substring("project-".length()) : args[2].startsWith("project-fallback-") ? args[2].substring("project-".length()) : url ? pairs ? "url-pairs" : "url" : pairs ? "pairs" : "same");
            if (hash == null) { System.out.println("PASS native publication control " + args[2]); continue; }
            var storage = new com.naocraftlab.skins.core.storage.NclSkinsStorage(dir.resolve("library"), new com.naocraftlab.skins.core.png.PngValidator(), java.time.Clock.systemUTC());
            byte[] saved = storage.readAsset(hash);
            var decoded = javax.imageio.ImageIO.read(new ByteArrayInputStream(saved));
            var image = new com.tom.cpl.util.Image(decoded.getRGB(0, 0, 64, 64, null, 0, 64), 64);
            var carrier = new com.tom.cpm.shared.io.SkinDataInputStream(image,
                    com.tom.cpm.shared.MinecraftClientAccess.get().getDefinitionLoader().getTemplate(),
                    (variant == SkinVariant.SLIM ? SkinType.SLIM : SkinType.DEFAULT).getChannel());
            var captured = new java.io.ByteArrayOutputStream();
            var tee = new java.io.FilterInputStream(carrier) {
                @Override public int read() throws java.io.IOException { int value = in.read(); if (value >= 0) captured.write(value); return value; }
                @Override public int read(byte[] bytes, int offset, int count) throws java.io.IOException { int n = in.read(bytes, offset, count); if (n > 0) captured.write(bytes, offset, n); return n; }
            };
            check(tee.read() == 83, "Native project carrier header");
            var checksum = new com.tom.cpm.shared.io.ChecksumInputStream(tee); var parts = new IOHelper(checksum);
            while (true) {
                var part = parts.readObjectBlock(ModelPartType.VALUES, (type, block) -> type.getFactory().create(block, null));
                if (part instanceof com.tom.cpm.shared.parts.ModelPartEnd) break;
            }
            checksum.checkSum();
            if (args[2].contains("publication")) {
                byte[] wire = Files.readAllBytes(dir.resolve("library/publication.wire"));
                check(!Arrays.equals(wire, Files.readAllBytes(source)), "Actual upload is native definition wire, never project container");
                CpmProjectCompositionProbe.verifyPublishedCarrier(saved, wire, fixtures.resolveSibling("import-feasibility"), SkinType.SLIM);
                Files.write(dir.resolve("expected.wire"), wire);
            } else CpmProjectCompositionProbe.verifyRawPayload(captured.toByteArray(), fixtures.resolveSibling("import-feasibility"), fixtures.resolveSibling("project-binding"), name);
            if (name.equals("canonical")) check(Arrays.equals(Files.readAllBytes(fixtures.resolveSibling("project-binding/default-SLIM.payload")), captured.toByteArray()), "Independent canonical project payload");
            Files.write(dir.resolve("expected.payload"), captured.toByteArray());
            Files.writeString(dir.resolve("expected.sha256"), hash + "\n");
            check(Arrays.equals(Files.readAllBytes(source), Files.readAllBytes(fixtures.resolveSibling("import-feasibility/" + name + ".cpmproject"))), "Raw native project source remains unchanged");
            Files.delete(source);
            retainSavedState(dir);
            byte[] applied = com.naocraftlab.skins.runtime.CpmCallerHarness.explicitTestApply(dir.resolve("library"), bundled, variant);
            check(Arrays.equals(saved, applied), "Source-free raw-project application bytes");
            if (args[2].contains("publication")) CpmProjectCompositionProbe.verifyPublishedCarrier(applied,
                    Files.readAllBytes(dir.resolve("expected.wire")), fixtures.resolveSibling("import-feasibility"), SkinType.SLIM);
            }
            return;
        }
        if (args.length > 2 && args[2].equals("url")) {
            Path controls = Files.createDirectories(output.resolve("url-controls"));
            com.naocraftlab.skins.runtime.CpmCallerHarness.saveThroughRuntime(new CpmModelFileAdapter(), bundled,
                    fixtures.resolve("slim-base.png"), fixtures.resolve("slim-base.png"), controls.resolve("png"), SkinVariant.SLIM, "url-png");
            Path malformed = Files.write(controls.resolve("malformed.png"), new byte[]{83, 0, 0});
            com.naocraftlab.skins.runtime.CpmCallerHarness.saveThroughRuntime(new CpmModelFileAdapter(), bundled,
                    malformed, fixtures.resolve("classic-base.png"), controls.resolve("malformed"), SkinVariant.CLASSIC, "url-error");
        }
        for (String name : List.of("unknown-type", "absent-type")) for (SkinVariant variant : SkinVariant.values()) {
            Path dir = output.resolve(name + "-" + variant.name()); Files.createDirectories(dir);
            Path source = dir.resolve("source.any"), base = dir.resolve("base.png");
            Files.copy(fixtures.resolve(name + ".cpmmodel"), source);
            Files.copy(fixtures.resolve(variant == SkinVariant.SLIM ? "slim-base.png" : "classic-base.png"), base);
            var markedBase = javax.imageio.ImageIO.read(base.toFile());
            markedBase.setRGB(8, 8, 0x00123456);
            check(javax.imageio.ImageIO.write(markedBase, "png", base.toFile()), "Independent marked base encoded");
            try {
                String hash = com.naocraftlab.skins.runtime.CpmCallerHarness.saveThroughRuntime(new CpmModelFileAdapter(), bundled, source, base, dir.resolve("library"), variant, args.length > 2 ? args[2] : "same");
                var storage = new com.naocraftlab.skins.core.storage.NclSkinsStorage(dir.resolve("library"), new com.naocraftlab.skins.core.png.PngValidator(), java.time.Clock.systemUTC());
                byte[] saved = storage.readAsset(hash);
                String duplicate = com.naocraftlab.skins.runtime.CpmCallerHarness.saveThroughRuntime(new CpmModelFileAdapter(), bundled, source, base, dir.resolve("library"), variant, args.length > 2 && args[2].startsWith("fallback-") ? args[2] : "same");
                check(hash.equals(duplicate), "Genuine native byte-identical duplicate reuses the selected carrier");
                var image = javax.imageio.ImageIO.read(new ByteArrayInputStream(saved));
                byte[] expected = Files.readAllBytes(fixtures.resolve(name + ".payload"));
                Files.write(dir.resolve("expected.payload"), expected);
                Files.writeString(dir.resolve("expected.sha256"), hash + "\n");
                var nativeImage = new com.tom.cpl.util.Image(image.getRGB(0, 0, 64, 64, null, 0, 64), 64);
                byte[] actual = new com.tom.cpm.shared.io.SkinDataInputStream(nativeImage,
                        com.tom.cpm.shared.MinecraftClientAccess.get().getDefinitionLoader().getTemplate(),
                        variant == SkinVariant.SLIM ? SkinType.SLIM.getChannel() : SkinType.DEFAULT.getChannel()).readNBytes(expected.length);
                check(Arrays.equals(expected, actual), "Independent selected native payload/channel after Save");
                check(Arrays.equals(Files.readAllBytes(source), Files.readAllBytes(fixtures.resolve(name + ".cpmmodel"))), "Raw native model source remains unchanged");
                Files.delete(source); Files.delete(base);
                check(Arrays.equals(saved, storage.readAsset(hash)), "Exact carrier after source deletion");
                retainSavedState(dir);
                byte[] uploaded = com.naocraftlab.skins.runtime.CpmCallerHarness.explicitTestApply(dir.resolve("library"), bundled, variant);
                check(Arrays.equals(saved, uploaded), "Actual source-free application captured native bytes");
            } catch (Exception | AssertionError failure) {
                throw new AssertionError("Native unknown/absent must reach real caller draft and Save: " + name + " " + variant, failure);
            }
        }
        System.out.println("PASS actual native unknown/absent → CatalogImportFlow → EditorFlow → Save both channels");
    }
}

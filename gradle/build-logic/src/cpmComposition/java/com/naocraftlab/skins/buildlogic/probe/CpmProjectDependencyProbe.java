package com.naocraftlab.skins.buildlogic.probe;

import com.naocraftlab.skins.compat.cpm.CpmProjectImportScope;
import com.tom.cpm.shared.editor.Editor;
import com.tom.cpm.shared.editor.project.ProjectFile;
import com.tom.cpm.shared.model.SkinType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class CpmProjectDependencyProbe {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static Editor editor() throws Exception {
        var method = CpmProjectBindingProbe.class.getDeclaredMethod("editor", SkinType.class, String.class);
        method.setAccessible(true);
        return (Editor) method.invoke(null, SkinType.SLIM, null);
    }

    private static long leases() throws Exception {
        try (var paths = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return paths.filter(path -> path.getFileName().toString().startsWith("nclskins-import-")).count();
        }
    }

    public static void verifyCarrier(byte[] selected, byte[] base, Path fixtures,
            com.naocraftlab.skins.core.model.SkinVariant variant) throws Exception {
        var type = variant == com.naocraftlab.skins.core.model.SkinVariant.SLIM ? SkinType.SLIM : SkinType.DEFAULT;
        byte[] retained = Files.readAllBytes(fixtures.resolve("template.payload"));
        CpmProjectCompositionProbe.verifyRawPayload(retained, fixtures.resolveSibling("import-feasibility"), fixtures.resolveSibling("project-binding"), "template");
        var bytes = new java.io.ByteArrayOutputStream(); bytes.write(83);
        var input = new com.tom.cpm.shared.io.ChecksumInputStream(new java.io.ByteArrayInputStream(retained, 1, retained.length - 1));
        var reader = new com.tom.cpm.shared.io.IOHelper(input);
        try (var checksum = new com.tom.cpm.shared.io.ChecksumOutputStream(bytes)) {
            var writer = new com.tom.cpm.shared.io.IOHelper(checksum);
            while (true) {
                var part = reader.readObjectBlock(com.tom.cpm.shared.parts.ModelPartType.VALUES, (kind, data) -> kind.getFactory().create(data, null));
                if (part == null) continue;
                writer.writeObjectBlock(part instanceof com.tom.cpm.shared.parts.ModelPartSkinType
                        ? new com.tom.cpm.shared.parts.ModelPartSkinType(type) : part);
                if (part instanceof com.tom.cpm.shared.parts.ModelPartEnd) break;
            }
        }
        input.checkSum();
        byte[] expected = bytes.toByteArray();
        var image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(selected));
        var nativeImage = new com.tom.cpl.util.Image(image.getRGB(0, 0, 64, 64, null, 0, 64), 64);
        var template = com.tom.cpm.shared.MinecraftClientAccess.get().getDefinitionLoader().getTemplate();
        var stream = new com.tom.cpm.shared.io.SkinDataInputStream(nativeImage, template, type.getChannel());
        check(java.util.Arrays.equals(expected, stream.readNBytes(expected.length)),
                "Selected carrier must retain independent exact template args/UV/texture payload and channel");
        var baseImage = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(base));
        var expectedImage = new com.tom.cpl.util.Image(baseImage.getRGB(0, 0, 64, 64, null, 0, 64), 64);
        try (var writer = new com.tom.cpm.shared.io.SkinDataOutputStream(expectedImage, template, type.getChannel())) { writer.write(expected); }
        check(java.util.Arrays.equals(expectedImage.getData(), nativeImage.getData()), "Every selected carrier pixel matches independent native payload writer and chosen base");
    }

    public static void main(String[] args) throws Exception {
        if (args[0].equals("reopen")) {
            Path root = Path.of(args[1]);
            check(!Files.exists(root.resolve("source.cpmproject")) && !Files.exists(root.resolve("base.png")), "Fresh process has no original source/base");
            byte[] selected = Files.readAllBytes(root.resolve("selected.png"));
            var image = new java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            var bundled = new java.io.ByteArrayOutputStream(); javax.imageio.ImageIO.write(image, "png", bundled);
            byte[] applied = com.naocraftlab.skins.runtime.CpmCallerHarness.explicitTestApply(root.resolve("saved"), bundled.toByteArray(),
                    com.naocraftlab.skins.core.model.SkinVariant.valueOf(args[2]), false);
            check(java.util.Arrays.equals(selected, applied), "Fresh first explicit Apply captures exact independently verified selected carrier");
            check(com.naocraftlab.skins.core.api.CpmUrlIngressFixture.requests == 0, "Fresh process never reacquires public source");
            return;
        }
        long leasesBefore = leases();
        CpmImportFeasibilityProbe.main(new String[] {args[0], args[1], "project-binding-setup"});
        Path fixtures = Path.of(args[2]);
        var project = new ProjectFile();
        project.load(fixtures.resolve("template.cpmproject").toFile()).join();
        var ready = editor();
        CpmProjectImportScope.run(ready, 17,
                Map.of("p:synthetic", Files.readAllBytes(fixtures.resolve("template.json"))), () -> {
                    CpmProjectImportScope.loadProject(ready, 17, project);
                    return null;
                });
        check(ready.templates.size() == 1, "prepared dependency must produce native template");
        var template = ready.templates.get(0);
        template.applyToModel();
        var cube = template.getCubes().get(0).getCube();
        check(cube.rgb == 0xff51a2e3 && cube.u == 18 && cube.v == 24 && cube.texSize == 2,
                "native template args and UV survive load");
        check(template.getTemplateTexture().getImage().getRGB(63, 62) == 0xff1234ab,
                "native authored default texture survives load");
        check(!CpmProjectImportScope.owns(ready), "prepared scope is retired");

        var pending = editor();
        IOException discovery = null;
        try {
            CpmProjectImportScope.run(pending, 18, Map.of(), () -> {
                CpmProjectImportScope.loadProject(pending, 18, project);
                return null;
            });
        } catch (IOException failure) {
            discovery = failure;
        }
        check(!CpmProjectImportScope.owns(pending), "discovery scope is retired");
        check(pending.templates.isEmpty(), "missing template is not a ready partial editor");
        var effects = CpmImportFeasibilityProbe.class.getDeclaredField("effects");
        effects.setAccessible(true);
        check(((Map<?, ?>) effects.get(null)).isEmpty(), "discovery has no global loader or personal effects");
        check(discovery != null && discovery.getClass().getSimpleName().equals("DependencyRequired"),
                "native missing template must return resumable DependencyRequired, not terminal MissingData");
        check("p:synthetic".equals(discovery.getClass().getMethod("link").invoke(discovery)),
                "discovery returns exact native-parsed link for explicit consent");
        var rawResources = CpmProjectCompositionProbe.class.getDeclaredMethod("rawResources");
        rawResources.setAccessible(true);
        rawResources.invoke(null);
        com.naocraftlab.skins.runtime.CpmDependencyCallerProbe.fidelity(fixtures, Path.of(args[1]).resolve("fidelity"));
        com.naocraftlab.skins.runtime.CpmDependencyCallerProbe.run(fixtures, Path.of(args[1]).resolve("caller"));
        check(((Map<?, ?>) effects.get(null)).isEmpty(), "Acquisition and replay have no global loader/profile/log/personal effects");
        check(leases() == leasesBefore, "Success/error/cancel/stale release every owned frozen source lease");
        System.out.println("PASS native dependency discovery and prepared native fidelity control");
    }
}

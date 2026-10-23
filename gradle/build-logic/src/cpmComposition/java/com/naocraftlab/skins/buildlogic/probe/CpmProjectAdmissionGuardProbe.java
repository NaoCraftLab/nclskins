package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.buildlogic.probe.CpmImportFeasibilityProbe;
import com.naocraftlab.skins.client.CpmModelFileConversion;
import com.naocraftlab.skins.compat.cpm.CpmMixinPlugin;
import com.naocraftlab.skins.compat.cpm.CpmModelFileAdapter;
import com.naocraftlab.skins.compat.cpm.CpmModelFileBindings;
import com.naocraftlab.skins.compat.cpm.CpmProjectBindings;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

public final class CpmProjectAdmissionGuardProbe {
    private static final String EXPORTER = "com/tom/cpm/shared/editor/Exporter.class";
    private static final String TEMPLATE = "com/tom/cpm/shared/editor/template/EditorTemplate.class";

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static Object field(Object target, String name) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static long leases() throws Exception {
        try (var paths = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return paths.filter(path -> path.getFileName().toString().startsWith("nclskins-import-")).count();
        }
    }

    public static void main(String[] args) throws Exception {
        if (args[0].equals("mutant")) {
            try (var original = new java.util.jar.JarFile(System.getProperty("nclskins.cpm.original"));
                    var input = original.getInputStream(original.getJarEntry(EXPORTER))) {
                var node = new ClassNode();
                new ClassReader(input).accept(node, 0);
                check(node.methods.removeIf(method -> method.name.equals("prepareDefinition")), "Actual compiler removed");
                var writer = new ClassWriter(0);
                node.accept(writer);
                Path output = Path.of(args[1]).resolve(EXPORTER);
                Files.createDirectories(output.getParent());
                Files.write(output, writer.toByteArray());
            }
            return;
        }
        boolean healthy = args[0].equals("healthy");
        Path fixtures = Path.of(args[1]), output = Path.of(args[2]);
        Files.createDirectories(output);
        CpmImportFeasibilityProbe.main(new String[]{fixtures.resolveSibling("import-feasibility").toString(), output.toString(), "project-binding-setup"});
        var effectsField = CpmImportFeasibilityProbe.class.getDeclaredField("effects");
        effectsField.setAccessible(true);
        Map<?, ?> effects = (Map<?, ?>) effectsField.get(null);
        var parent = Thread.currentThread().getContextClassLoader();
        try (var original = new java.net.URLClassLoader(new java.net.URL[]{Path.of(System.getProperty("nclskins.cpm.original")).toUri().toURL()}, null)) {
            var resources = new ClassLoader(parent) {
                @Override public InputStream getResourceAsStream(String name) {
                    if (name.equals(TEMPLATE) || healthy && name.equals(EXPORTER)) return original.getResourceAsStream(name);
                    return super.getResourceAsStream(name);
                }
            };
            Thread.currentThread().setContextClassLoader(resources);
            try {
                check(CpmProjectBindings.supported(resources) == healthy, "Independent compiler binding challenge");
                var plugin = new CpmMixinPlugin();
                for (String mixin : new String[]{"CpmProjectTemplateMixin", "CpmProjectExporterMixin", "CpmProjectExporterInvoker"}) {
                    check(plugin.shouldApplyMixin("unused", "com.naocraftlab.skins.compat.cpm.mixin." + mixin) == healthy,
                            "Plugin isolates project mixin " + mixin);
                }
                check(plugin.shouldApplyMixin("unused", "com.naocraftlab.skins.compat.cpm.mixin.AnimationEngineMixin"), "Unrelated CPM remains enabled");
                check(CpmModelFileBindings.supported(resources), "ModelFile bindings independent of project challenge");
                check(new CpmModelFileAdapter().availability() == CpmModelFileConversion.Availability.READY, "Unrelated adapter availability remains ready");
                if (!healthy) {
                    check(java.util.Arrays.stream(com.tom.cpm.shared.editor.Exporter.class.getDeclaredMethods()).noneMatch(m -> m.getName().equals("prepareDefinition")), "Loadable actual exporter mutant used");
                    check(java.util.Arrays.stream(com.tom.cpm.shared.editor.template.EditorTemplate.class.getDeclaredMethods()).noneMatch(m -> m.getName().contains("nclskins")), "Actual template is untransformed when plugin skips project hooks");
                    var editor = new com.tom.cpm.shared.editor.Editor();
                    try {
                        com.naocraftlab.skins.compat.cpm.CpmProjectImportScope.run(editor, 23, Map.of(),
                                () -> com.naocraftlab.skins.compat.cpm.CpmProjectImportScope.compile(editor, 23));
                        throw new AssertionError("Compile boundary must independently reject unsupported bindings");
                    } catch (com.naocraftlab.skins.compat.cpm.CpmProjectImportScope.UnsupportedData expected) {
                        check(!com.naocraftlab.skins.compat.cpm.CpmProjectImportScope.owns(editor), "Compile guard releases its scope");
                    }
                }
                long before = leases();
                var constructor = CpmDependencyCallerProbe.class.getDeclaredConstructor(Path.class, Path.class);
                constructor.setAccessible(true);
                var caller = constructor.newInstance(fixtures, output.resolve("caller"));
                var flow = (CatalogImportFlow) field(caller, "flow");
                var drain = CpmDependencyCallerProbe.class.getDeclaredMethod("drain");
                drain.setAccessible(true);
                flow.chooseAddSourcePng();
                drain.invoke(caller);
                check(effects.isEmpty(), "Project admission must not reach original global resolver/cache/log/UI/profile: " + effects);
                check((int) field(caller, "requests") == 0 && field(caller, "result") == null, "No network or partial draft before consent");
                if (healthy) {
                    check(field(caller, "failure") == null && flow.addSource().importContext().dependency().isPresent(), "Healthy transformed native template still requests dependency consent");
                    long revision = flow.addSource().importContext().dependency().orElseThrow().revision();
                    var view = new AddSourcePresenter().present(flow.addSource(), false, java.util.Optional.empty(), 240, 240);
                    check(view.widgets().stream().anyMatch(w -> w.id().equals("add.cpm.dependency.confirm." + revision) && w.enabled()), "Healthy visible revision-bound consent control");
                    flow.dispatchCatalogImportAction("add.cpm.dependency.cancel." + revision, false, InteractionOrigin.PROGRAMMATIC);
                    drain.invoke(caller);
                } else {
                    Object failure = field(caller, "failure");
                    check(failure instanceof CpmModelFileConversion.Failure rejected && rejected.reason() == CpmModelFileConversion.Reason.CPM_UNAVAILABLE, "Unsupported project yields typed CPM_UNAVAILABLE");
                    check(flow.addSource().importContext().dependency().isEmpty() && !(boolean) field(caller, "busy"), "Unsupported project has no prompt or stuck busy owner");
                }
                check(leases() == before, "Admission failure or consent cancel releases all frozen input leases");
                try (var model = new CpmModelFileAdapter().admitFile(fixtures.resolveSibling("model-conversion/slim-embedded.cpmmodel"))) {
                    check(model.requiredVariant().orElseThrow() == com.naocraftlab.skins.client.SkinModel.SLIM, "Healthy ModelFile admission survives unsupported project bindings");
                }
                var png = com.naocraftlab.skins.core.api.PublicSkinImageAdapter.acquireLocal(fixtures.resolveSibling("model-conversion/slim-base.png"));
                check(png instanceof com.naocraftlab.skins.core.importing.PublicSkinImageSource.PngInput,
                        "Ordinary PNG acquisition remains healthy");
                check(leases() == before && effects.isEmpty(), "Controls release owned inputs with zero forbidden effects");
                System.out.println("PASS " + args[0] + " actual admission, consent boundary, frozen leases, ModelFile and PNG controls");
            } finally {
                Thread.currentThread().setContextClassLoader(parent);
            }
        }
    }
}

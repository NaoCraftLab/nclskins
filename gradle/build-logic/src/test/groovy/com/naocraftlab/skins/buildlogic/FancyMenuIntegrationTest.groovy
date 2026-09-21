package com.naocraftlab.skins.buildlogic

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import javax.tools.ToolProvider
import java.nio.file.Path
import java.util.function.Consumer

import static org.junit.jupiter.api.Assertions.*

final class FancyMenuIntegrationTest {
    @TempDir Path temporary
    private final File repository = new File('../..').canonicalFile

    @Test
    void metadataInspectionAcceptsGradleClasspathSetsAndChecksLoaderMinima() {
        File jar = temporary.resolve('fancymenu.jar').toFile()
        new java.util.zip.ZipOutputStream(new FileOutputStream(jar)).withCloseable { zip ->
            zip.putNextEntry(new java.util.zip.ZipEntry(FancyMenuAbiVerifier.ACTION.replace('.', '/') + '.class'))
            zip.closeEntry()
            zip.putNextEntry(new java.util.zip.ZipEntry('fabric.mod.json'))
            zip.write('{"depends":{"fabricloader":">=0.19.5","fabric-api":">=0.161.0"}}'.getBytes('UTF-8'))
            zip.closeEntry()
        }
        Map target = [loader: [id: 'fabric', version: '0.19.5', apiVersion: '0.161.0+26.3']]
        FancyMenuAbiVerifier.verifyMetadata([jar] as Set, target, true)
        target.loader.apiVersion = '0.160.5+26.3'
        assertThrows(IllegalStateException) { FancyMenuAbiVerifier.verifyMetadata([jar] as Set, target, true) }
        FancyMenuAbiVerifier.verifyMetadata([] as Set, target, false)
    }

    @Test
    void dependencyMinimaRejectOldCatalogPins() {
        FancyMenuAbiVerifier.requireMinimum('0.161.0+26.3', '>=0.161.0')
        FancyMenuAbiVerifier.requireMinimum('26.3.0.6-beta', '[26.3.0.6-beta,)')
        assertThrows(IllegalStateException) { FancyMenuAbiVerifier.requireMinimum('0.160.5+26.3', '>=0.161.0') }
        assertThrows(IllegalStateException) { FancyMenuAbiVerifier.requireMinimum('26.3.0.0-beta', '[26.3.0.6-beta,)') }
    }

    @Test
    void newTargetWithoutFancyMenuDecisionFailsCatalogCoverage() {
        Map catalog = CatalogTools.loadCatalog(repository)
        catalog.optionalDependencies.fancymenu.versions.remove('fabric-26.1')
        def failure = assertThrows(IllegalArgumentException) { CatalogTools.validate(repository, catalog) }
        assertTrue(failure.message.contains('FancyMenu'))
    }

    @Test
    void buildConventionsKeepFancyMenuOffDevelopmentRuntimeClasspaths() {
        String shared = new File(repository, 'gradle/fancymenu-conventions.gradle').text
        assertTrue(shared.contains('nclskinsFancyCompileConfiguration'))
        assertTrue(shared.contains('compileDependency.transitive = false'))
        assertFalse(shared.contains('nclskinsFancyRuntimeConfiguration'))
        assertFalse(shared.contains('runtimeArtifacts'))

        ['fabric', 'forge', 'neoforge'].each { loader ->
            String convention = new File(repository,
                    "gradle/loader-conventions/${loader}.gradle").text
            assertFalse(convention.contains('nclskinsFancyRuntimeConfiguration'))
        }
    }

    @Test
    void allFiveActionsRunSynchronouslyWithoutValuesOrVersionAllowlist() {
        File classes = compileFixture('compatible', '')
        FancyMenuAbiVerifier.verify(classes.path, new File(System.getProperty('java.home'), 'bin/javap'))
        new URLClassLoader([classes.toURI().toURL()] as URL[], ClassLoader.platformClassLoader).withCloseable { loader ->
            List opened = []
            install(loader, true, { opened.add(it.toString()) } as Consumer)
            install(loader, true, { fail('duplicate registration') } as Consumer)
            Class registry = loader.loadClass(FancyMenuAbiVerifier.REGISTRY)
            Map actions = registry.getField('ACTIONS').get(null) as Map
            assertEquals(['nclskins_open_gallery', 'nclskins_edit_active_preset', 'nclskins_open_providers',
                    'nclskins_open_skin_catalog', 'nclskins_open_skin_import'] as Set, actions.keySet())
            actions.values().each { action ->
                Class api = loader.loadClass(FancyMenuAbiVerifier.ACTION)
                assertEquals(false, api.getMethod('hasValue').invoke(action))
                assertEquals(false, api.getMethod('canRunAsync').invoke(action))
                api.getMethod('execute', String).invoke(action, 'ignored')
            }
            assertEquals(['GALLERY', 'ACTIVE_EDITOR', 'PROVIDERS', 'SKIN_CATALOG', 'SKIN_IMPORT'], opened)
        }
    }

    @Test
    void absentModDoesNotLoadAnyFancyMenuTypeOrAdapter() {
        File classes = compileFixture('absent', '')
        List<String> forbiddenLoads = []
        new URLClassLoader([classes.toURI().toURL()] as URL[], ClassLoader.platformClassLoader) {
            @Override Class<?> loadClass(String name) throws ClassNotFoundException {
                if (name.startsWith('de.keksuccino.') || name.endsWith('.FancyMenuActions')) {
                    forbiddenLoads.add(name)
                    throw new ClassNotFoundException(name)
                }
                super.loadClass(name)
            }
        }.withCloseable { loader -> install(loader, false, { fail('must not open') } as Consumer) }
        assertEquals([], forbiddenLoads)
    }

    @Test
    void newAbstractMethodDisablesRegistrationBeforeAnyActionIsPublished() {
        File classes = compileFixture('broken', '')
        compileApi(classes, 'public abstract void incompatibleFutureMethod();')
        assertThrows(IllegalStateException) {
            FancyMenuAbiVerifier.verify(classes.path, new File(System.getProperty('java.home'), 'bin/javap'))
        }
        new URLClassLoader([classes.toURI().toURL()] as URL[], ClassLoader.platformClassLoader).withCloseable { loader ->
            install(loader, true, { fail('must not open') } as Consumer)
            assertTrue((loader.loadClass(FancyMenuAbiVerifier.REGISTRY).getField('ACTIONS').get(null) as Map).isEmpty())
            assertEquals(1, loader.loadClass('fixture.Sink').getField('reports').get(null))
        }
    }

    private static void install(ClassLoader loader, boolean present, Consumer open) {
        Class sink = loader.loadClass('com.naocraftlab.skins.diagnostics.DiagnosticSink')
        loader.loadClass('com.naocraftlab.skins.compat.fancymenu.FancyMenuIntegration')
                .getMethod('install', Boolean.TYPE, Consumer, sink)
                .invoke(null, present, open, loader.loadClass('fixture.Sink').getConstructor().newInstance())
    }

    private File compileFixture(String name, String extra) {
        File root = temporary.resolve(name).toFile()
        root.mkdirs()
        compileApi(root, extra)
        Map<String, String> fixtures = [
            'com/naocraftlab/skins/diagnostics/DiagnosticEvent.java': 'package com.naocraftlab.skins.diagnostics; public enum DiagnosticEvent { CLIENT_FANCYMENU_INCOMPATIBLE }',
            'com/naocraftlab/skins/diagnostics/DiagnosticDetails.java': 'package com.naocraftlab.skins.diagnostics; public class DiagnosticDetails { public static DiagnosticDetails none() { return new DiagnosticDetails(); } }',
            'com/naocraftlab/skins/diagnostics/DiagnosticSink.java': 'package com.naocraftlab.skins.diagnostics; public interface DiagnosticSink { void report(DiagnosticEvent event, java.util.function.Supplier<DiagnosticDetails> details); }',
            'fixture/Sink.java': 'package fixture; public class Sink implements com.naocraftlab.skins.diagnostics.DiagnosticSink { public static int reports; public void report(com.naocraftlab.skins.diagnostics.DiagnosticEvent event, java.util.function.Supplier<com.naocraftlab.skins.diagnostics.DiagnosticDetails> details) { reports++; } }'
        ]
        List<File> sources = writeSources(root, fixtures)
        sources.add(new File(repository, 'client-contract/src/main/java/com/naocraftlab/skins/client/ScreenDestination.java'))
        sources.addAll(new File(repository, 'compat/fancymenu/src/main/java/com/naocraftlab/skins/compat/fancymenu').listFiles().findAll { it.name.endsWith('.java') })
        compile(root, sources)
        root
    }

    private void compileApi(File root, String extra) {
        compile(root, writeSources(root, [
            'net/minecraft/network/chat/Component.java': 'package net.minecraft.network.chat; public class Component { public static Component translatable(String key) { return new Component(); } }',
            'de/keksuccino/fancymenu/customization/action/Action.java': '''package de.keksuccino.fancymenu.customization.action;
                public abstract class Action {
                    private final String id;
                    public Action(String id) { this.id = id; }
                    public String getIdentifier() { return id; }
                    public abstract boolean hasValue();
                    public abstract void execute(String value);
                    public abstract net.minecraft.network.chat.Component getDisplayName();
                    public abstract net.minecraft.network.chat.Component getDescription();
                    public abstract net.minecraft.network.chat.Component getValueDisplayName();
                    public abstract String getValuePreset();
                    public boolean canRunAsync() { return true; }
                ''' + extra + '}',
            'de/keksuccino/fancymenu/customization/action/ActionRegistry.java': '''package de.keksuccino.fancymenu.customization.action;
                public class ActionRegistry {
                    public static final java.util.Map<String, Action> ACTIONS = new java.util.LinkedHashMap<>();
                    public static void register(Action action) { ACTIONS.put(action.getIdentifier(), action); }
                    public static Action getAction(String id) { return ACTIONS.get(id); }
                }'''
        ]))
    }

    private static List<File> writeSources(File root, Map<String, String> sources) {
        sources.collect { path, content ->
            File file = new File(root, path)
            file.parentFile.mkdirs()
            file.text = content
            file
        }
    }

    private static void compile(File root, List<File> sources) {
        List<String> args = ['-classpath', root.path, '-d', root.path] + sources.collect { it.path }
        assertEquals(0, ToolProvider.systemJavaCompiler.run(null, null, null, args as String[]))
    }
}

package com.naocraftlab.skins.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

import javax.tools.ToolProvider
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

abstract class VerifyCpmIntegrationTask extends DefaultTask {
    @Classpath abstract ConfigurableFileCollection getResolutionClasspath()
    @Classpath abstract ConfigurableFileCollection getPackagedClasspath()
    @Classpath abstract ConfigurableFileCollection getProbeClasspath()
    @InputDirectory abstract DirectoryProperty getProbeDirectory()
    @InputDirectory abstract DirectoryProperty getCarrierFixtures()
    @OutputDirectory abstract DirectoryProperty getWorkDirectory()

    @TaskAction void verify() {
        File work = workDirectory.get().asFile
        project.delete(work)
        File compiled = new File(work, 'classes')
        compiled.mkdirs()
        File probe = probeDirectory.get().asFile
        List<File> sources = project.fileTree(new File(probe, 'java')).matching { include '**/*.java' }.files.sort { it.path }
        String libraries = probeClasspath.asPath + File.pathSeparator + resolutionClasspath.asPath
        int result = ToolProvider.systemJavaCompiler.run(null, System.out, System.err,
                (['--release', '17', '-classpath', libraries, '-d', compiled.path] + sources*.path) as String[])
        if (result != 0) throw new IllegalStateException('CPM Mixin probe compilation failed')
        List<File> inputs = resolutionClasspath.files.findAll { it.exists() }.toList()
        List<File> cpm = inputs.findAll { File file ->
            file.isFile() && file.name.endsWith('.jar') && new ZipFile(file).withCloseable { zip ->
                zip.getEntry('com/tom/cpm/client/PlayerProfile.class') != null
            }
        }
        if (cpm.size() != 1) throw new IllegalStateException('Expected one pinned CPM compile API')
        List<File> runtimeMixins = inputs.findAll { it.name.contains('sponge-mixin') || it.name.startsWith('mixinextras-') }
        List<File> hostClasspath = [compiled, work, new File(probe, 'resources')] + runtimeMixins + probeClasspath.files + inputs
        File modelTransforms = new File(work, 'model-transformed')
        new File(work, 'metadata-binding.json').text = groovy.json.JsonOutput.toJson([
                required: true, package: 'com.naocraftlab.skins.compat.cpm.mixin',
                plugin: 'com.naocraftlab.skins.compat.cpm.CpmMixinPlugin', compatibilityLevel: 'JAVA_17',
                client: ['CpmModelDefinitionPartsAccessor', 'CpmModelSkinTextureAccessor', 'CpmModelTextureAccessor', 'CpmModelFileBackupAccessor']])
        runProbe(work, 'model-metadata-transform', hostClasspath, 'CpmModelMetadataTransformProbe', [modelTransforms.absolutePath])
        runProbe(work, 'model-metadata-shapes', hostClasspath, 'CpmModelMetadataTransformProbe', [modelTransforms.absolutePath, 'shapes'])
        runProbe(work, 'model-metadata-server', hostClasspath, 'CpmModelMetadataTransformProbe', [modelTransforms.absolutePath, 'server'])
        hostClasspath = [modelTransforms] + hostClasspath
        runProbe(work, 'model-metadata-native', hostClasspath, 'CpmModelMetadataProbe',
                [new File(probe, 'fixtures/model-conversion').absolutePath, new File(work, 'model-metadata-output').absolutePath])
        File sessionHost = new File(work, 'session-host'); sessionHost.mkdirs()
        runProbe(work, 'session-host-setup', hostClasspath, 'CpmSessionPublicationProbe', ['setup', sessionHost.absolutePath])
        runProbe(work, 'session-native', [sessionHost] + hostClasspath, 'CpmSessionPublicationProbe', ['run'])
        File projectTransforms = new File(work, 'project-transformed')
        new File(work, 'project-binding.json').text = groovy.json.JsonOutput.toJson([
                required: true, package: 'com.naocraftlab.skins.compat.cpm.mixin',
                plugin: 'com.naocraftlab.skins.compat.cpm.CpmMixinPlugin', compatibilityLevel: 'JAVA_17',
                client: ['CpmProjectExporterInvoker', 'CpmProjectExporterMixin', 'CpmProjectTemplateMixin', 'CpmProjectTextureMixin'],
                injectors: [defaultRequire: 1]])
        runProbe(work, 'project-binding-transform', hostClasspath, 'CpmProjectTransformProbe', [projectTransforms.absolutePath])
        File defaultsHost = new File(work, 'project-defaults-host'); defaultsHost.mkdirs()
        runProbe(work, 'project-defaults-setup', [projectTransforms] + hostClasspath, 'CpmProjectDefaultsProbe',
                ['setup', defaultsHost.absolutePath])
        runProbe(work, 'project-defaults', [defaultsHost, projectTransforms] + hostClasspath, 'CpmProjectDefaultsProbe',
                [new File(probe, 'fixtures/import-feasibility').absolutePath, new File(work, 'project-defaults-output').absolutePath],
                ['-Dnclskins.cpm.original=' + cpm.first().absolutePath])
        runProbe(work, 'project-defaults-missing-texture-binding', [defaultsHost] + hostClasspath, 'CpmProjectDefaultsProbe',
                [new File(probe, 'fixtures/import-feasibility').absolutePath,
                 new File(work, 'project-defaults-unbound-output').absolutePath, 'missing-texture-binding'],
                ['-Dnclskins.cpm.original=' + cpm.first().absolutePath])
        File projectFixtures = new File(probe, 'fixtures/project-binding')
        for (String mode : ['green', 'green-debug']) {
            runProbe(work, 'project-binding-' + mode, [sessionHost, projectTransforms] + hostClasspath, 'CpmProjectBindingProbe',
                    [new File(probe, 'fixtures/import-feasibility').absolutePath,
                     new File(work, 'project-output-' + mode).absolutePath, mode, projectFixtures.absolutePath],
                    ['-Dnclskins.cpm.original=' + cpm.first().absolutePath,
                     mode == 'green-debug' ? '-Dcpm.debug=true' : '-Dcpm.debug=false'])
        }
        for (String mode : ['green', 'green-debug']) {
            runProbe(work, 'project-composition-' + mode, [sessionHost, projectTransforms] + hostClasspath, 'CpmProjectCompositionProbe',
                    [new File(probe, 'fixtures/import-feasibility').absolutePath,
                     new File(work, 'project-composition-output-' + mode).absolutePath, 'green', projectFixtures.absolutePath],
                    ['-Dnclskins.cpm.original=' + cpm.first().absolutePath,
                     '-Dnclskins.cpm.projectFixtures=' + new File(probe, 'fixtures/project-composition').absolutePath,
                     mode == 'green-debug' ? '-Dcpm.debug=true' : '-Dcpm.debug=false'])
        }
        runProbe(work, 'project-binding-optional', hostClasspath, 'CpmProjectOptionalProbe', [])
        File projectAdmissionMutant = new File(work, 'project-admission-mutant')
        List<String> projectAdmissionProperties = ['-Dnclskins.cpm.original=' + cpm.first().absolutePath]
        runProbe(work, 'project-admission-mutant', hostClasspath, 'CpmProjectOptionalProbe',
                ['mutant', projectAdmissionMutant.absolutePath], projectAdmissionProperties)
        runProbe(work, 'project-admission-unsupported', [projectAdmissionMutant] + hostClasspath, 'CpmProjectOptionalProbe',
                ['unsupported', new File(probe, 'fixtures/project-composition').absolutePath,
                 new File(work, 'project-admission-unsupported-output').absolutePath], projectAdmissionProperties)
        runProbe(work, 'project-admission-healthy', [projectTransforms] + hostClasspath, 'CpmProjectOptionalProbe',
                ['healthy', new File(probe, 'fixtures/project-composition').absolutePath,
                 new File(work, 'project-admission-healthy-output').absolutePath], projectAdmissionProperties)
        File dependencyOutput = new File(work, 'project-dependency-output'); dependencyOutput.mkdirs()
        runProbe(work, 'project-dependency', [projectTransforms] + hostClasspath, 'CpmProjectDependencyProbe',
                [new File(probe, 'fixtures/import-feasibility').absolutePath, dependencyOutput.absolutePath,
                 new File(probe, 'fixtures/project-composition').absolutePath],
                ['-Dnclskins.cpm.original=' + cpm.first().absolutePath])
        File modelGuard = new File(work, 'model-guard'); modelGuard.mkdirs()
        runProbe(work, 'model-guard-setup', hostClasspath, 'CpmModelGuardSetup',
                [cpm.first().absolutePath, modelGuard.absolutePath, 'observe'])
        File modelFixtures = new File(probe, 'fixtures/model-conversion')
        for (String mode : ['local', 'local-package', 'local-small', 'local-package-small', 'overflow', 'legacy', 'local-nested', 'topology']) {
            runProbe(work, 'model-file-publication-' + mode, hostClasspath, 'CpmModelFilePublicationProbe',
                    [modelFixtures.absolutePath, new File(work, 'model-file-publication-' + mode + '-output').absolutePath, mode])
        }
        runProbe(work, 'owned-preview-dispatch', [modelGuard] + hostClasspath, 'CpmOwnedPreviewDispatchProbe',
                [modelFixtures.absolutePath, new File(work, 'owned-preview-dispatch-output').absolutePath])
        runProbe(work, 'native-retired-fallback', [modelGuard] + hostClasspath, 'CpmNativeImportProbe',
                [modelFixtures.absolutePath, new File(work, 'native-retired-fallback-output').absolutePath, 'retired-fallback'])
        runProbe(work, 'native-project-retired-fallback', [projectTransforms] + hostClasspath, 'CpmNativeImportProbe',
                [modelFixtures.absolutePath, new File(work, 'native-project-retired-fallback-output').absolutePath, 'project-retired-fallback'],
                ['-Dnclskins.cpm.original=' + cpm.first().absolutePath])
        runProbe(work, 'native-input-lossless', [modelGuard] + hostClasspath, 'CpmNativeImportProbe',
                [modelFixtures.absolutePath, new File(work, 'native-input-lossless-output').absolutePath, 'collision'])
        runProbe(work, 'native-input-lossless-reopen', [modelGuard] + hostClasspath, 'CpmNativeImportProbe',
                [modelFixtures.absolutePath, new File(work, 'native-input-lossless-output').absolutePath, 'reopen'])
        for (String mode : ['publication', 'publication-url', 'publication-cancel', 'publication-missing',
                            'publication-uncertain', 'publication-long-id']) {
            File output = new File(work, 'native-project-' + mode + '-output')
            runProbe(work, 'native-project-' + mode, [projectTransforms] + hostClasspath, 'CpmNativeImportProbe',
                    [modelFixtures.absolutePath, output.absolutePath, 'project-' + mode],
                    ['-Dnclskins.cpm.original=' + cpm.first().absolutePath])
            if (mode in ['publication', 'publication-url'])
                runProbe(work, 'native-project-' + mode + '-reopen', [projectTransforms] + hostClasspath, 'CpmNativeImportProbe',
                        [modelFixtures.absolutePath, output.absolutePath, 'project-publication-reopen'])
        }
        runProbe(work, 'native-project-input', [projectTransforms] + hostClasspath, 'CpmNativeImportProbe',
                [modelFixtures.absolutePath, new File(work, 'native-project-input-output').absolutePath, 'project'],
                ['-Dnclskins.cpm.original=' + cpm.first().absolutePath])
        runProbe(work, 'native-project-input-reopen', [projectTransforms] + hostClasspath, 'CpmNativeImportProbe',
                [modelFixtures.absolutePath, new File(work, 'native-project-input-output').absolutePath, 'project-reopen'])
        runProbe(work, 'native-worker-lifecycle', [modelGuard] + hostClasspath, 'CpmNativeImportProbe',
                [modelFixtures.absolutePath, new File(work, 'native-worker-lifecycle-output').absolutePath, 'lifecycle'])
        runProbe(work, 'native-input-url', [modelGuard] + hostClasspath, 'CpmNativeImportProbe',
                [modelFixtures.absolutePath, new File(work, 'native-input-url-output').absolutePath, 'url'])
        runProbe(work, 'native-project-url-pairs', [projectTransforms] + hostClasspath, 'CpmNativeImportProbe',
                [modelFixtures.absolutePath, new File(work, 'native-project-url-pairs-output').absolutePath, 'project-url-pairs'],
                ['-Dnclskins.cpm.original=' + cpm.first().absolutePath])
        runProbe(work, 'native-project-pairs-reopen', [projectTransforms] + hostClasspath, 'CpmNativeImportProbe',
                [modelFixtures.absolutePath, new File(work, 'native-project-url-pairs-output').absolutePath, 'project-pairs-reopen'])
        runProbe(work, 'native-project-url', [projectTransforms] + hostClasspath, 'CpmNativeImportProbe',
                [modelFixtures.absolutePath, new File(work, 'native-project-url-output').absolutePath, 'project-url'],
                ['-Dnclskins.cpm.original=' + cpm.first().absolutePath])
        runProbe(work, 'model-conversion', [modelGuard] + hostClasspath, 'CpmModelConversionProbe',
                [modelFixtures.absolutePath, new File(work, 'model-output').absolutePath, 'all'])
        runProbe(work, 'model-caller', [modelGuard] + hostClasspath, 'CpmModelConversionProbe',
                [modelFixtures.absolutePath, new File(work, 'caller-output').absolutePath, 'caller-gates'])
        runProbe(work, 'model-caller-reopen', [modelGuard] + hostClasspath, 'CpmModelConversionProbe',
                [modelFixtures.absolutePath, new File(work, 'caller-output').absolutePath, 'fresh-reopen'])
        runProbe(work, 'model-save-scopes', [modelGuard] + hostClasspath, 'CpmModelConversionProbe',
                [modelFixtures.absolutePath, new File(work, 'save-scope-output').absolutePath, 'save-scopes'])
        runProbe(work, 'model-picker', [modelGuard] + hostClasspath, 'CpmModelConversionProbe',
                [modelFixtures.absolutePath, new File(work, 'picker-output').absolutePath, 'picker'])
        File optionalHost = new File(work, 'optional-model-host'); optionalHost.mkdirs()
        runProbe(work, 'optional-model-setup', hostClasspath, 'CpmOptionalEnvironmentProbe', ['setup', optionalHost.absolutePath])
        runProbe(work, 'optional-model-present', [optionalHost, modelGuard] + hostClasspath, 'CpmModelConversionProbe',
                [modelFixtures.absolutePath, new File(work, 'optional-model-output').absolutePath, 'composition'],
                ['-Dnclskins.cpm.optionalPresent=true'])
        runProbe(work, 'optional-model-absent', [optionalHost] + hostClasspath.findAll { !cpm.contains(it) },
                'CpmOptionalEnvironmentProbe', ['absent'])
        for (String mode : ['missing', 'error', 'duplicate', 'success-error', 'invalid', 'throw', 'linkage']) {
            File terminal = new File(work, 'model-terminal-' + mode); terminal.mkdirs()
            runProbe(work, 'model-terminal-setup-' + mode, hostClasspath, 'CpmModelGuardSetup',
                    [cpm.first().absolutePath, terminal.absolutePath, mode])
            runProbe(work, 'model-terminal-' + mode, [terminal] + hostClasspath, 'CpmModelConversionProbe',
                    [modelFixtures.absolutePath, new File(work, 'model-terminal-output-' + mode).absolutePath, 'exporter-failure'])
            runProbe(work, 'caller-terminal-' + mode, [terminal] + hostClasspath, 'CpmModelConversionProbe',
                    [modelFixtures.absolutePath, new File(work, 'caller-terminal-output-' + mode).absolutePath, 'caller-failure'])
        }
        String appearanceFamily = ['playerinfo', 'skinlookup'].find { family ->
            inputs.any { file -> file.isDirectory() && new File(file, "com/naocraftlab/skins/compat/client/resourcelocation/${family}/ResourceLocationTextureRegistry.class").isFile() }
        } ?: 'identifier'
        runProbe(work, 'minecraft-cape-fallback', hostClasspath, 'MinecraftCapeFallbackProbe', [appearanceFamily])
        boolean modernAccessories = new ZipFile(cpm.first()).withCloseable { zip -> zip.getEntry('com/tom/cpm/client/PlayerRenderStateAccess.class') != null }
        new File(work, 'accessory-upstream.json').text = groovy.json.JsonOutput.toJson([
                required: true, package: modernAccessories ? 'com.tom.cpm.mixin.render' : 'com.tom.cpm.mixin',
                compatibilityLevel: 'JAVA_17', client: ['CapeLayerMixin'], injectors: [defaultRequire: 1]])
        runProbe(work, 'accessory-selector', hostClasspath, 'CpmMergedSelectorProbe', [])
        for (String topology : ['legacy', 'loader-compat']) {
            runProbe(work, 'accessory-transform-' + topology, hostClasspath, 'CpmAccessoryCompositionProbe',
                    [new File(work, 'accessory-' + topology).absolutePath, topology])
        }
        for (String scenario : ['present', 'absent']) {
            List<File> classpath = [compiled, new File(probe, 'resources')] + runtimeMixins
            classpath.addAll(probeClasspath.files)
            classpath.addAll(inputs.findAll { scenario == 'present' || !cpm.contains(it) })
            runProbe(work, scenario, classpath, 'CpmCompositionProbe', [scenario],
                    ['-Dnclskins.cpm.transformed=' + new File(work, 'transformed').absolutePath])
        }
        File previewResourceTransforms = new File(work, 'preview-resource-transformed')
        new File(work, 'preview-resource-binding.json').text = groovy.json.JsonOutput.toJson([
                required: true, package: 'com.naocraftlab.skins.compat.cpm.mixin',
                plugin: 'com.naocraftlab.skins.compat.cpm.CpmMixinPlugin', compatibilityLevel: 'JAVA_17',
                client: ['CpmAnimatedTextureMixin', 'CpmDefinitionResourceMixin'], injectors: [defaultRequire: 1]])
        runProbe(work, 'preview-resource-transform', hostClasspath, 'CpmPreviewResourceTransformProbe',
                [previewResourceTransforms.absolutePath])
        runProbe(work, 'preview-resource-server', hostClasspath, 'CpmPreviewResourceTransformProbe',
                [previewResourceTransforms.absolutePath, 'server'])
        String rawFamily = appearanceFamily == 'identifier' ? null : appearanceFamily
        if (rawFamily != null) {
            runProbe(work, 'preview-scope', hostClasspath, 'CpmPreviewScopeProbe', [rawFamily]);
            File seams = new File(work, 'raw-host'); seams.mkdirs()
            File fixtureClasses = new File(work, 'raw-fixture-classes'); fixtureClasses.mkdirs()
            List<File> rawSources = project.fileTree(new File(probe, 'fixtures/raw/java')).matching { include '**/*.java' }.files.sort { it.path }
            int rawResult = ToolProvider.systemJavaCompiler.run(null, System.out, System.err,
                    (['--release', '17', '-classpath', libraries, '-d', fixtureClasses.path] + rawSources*.path) as String[])
            if (rawResult != 0) throw new IllegalStateException('Raw-source NCL fixture compilation failed')
            runProbe(work, 'raw-link', [compiled] + probeClasspath.files, 'CpmFixtureLinker', [fixtureClasses.path, seams.path])
            new File(seams, 'raw-capture.json').text = groovy.json.JsonOutput.toJson([
                    required: true, package: "com.naocraftlab.skins.compat.client.resourcelocation.${rawFamily}.mixin",
                    compatibilityLevel: 'JAVA_17', client: ['HttpTextureSneakyMixin'], injectors: [defaultRequire: 1]])
            List<File> rawClasspath = [seams, compiled, new File(probe, 'resources')] + probeClasspath.files + inputs
            runProbe(work, 'raw-transform', rawClasspath, 'CpmCompositionProbe', ['raw-' + rawFamily],
                    ['-Dnclskins.cpm.transformed=' + new File(work, 'raw-transformed').absolutePath])
            runProbe(work, 'raw-behavior', [previewResourceTransforms, new File(work, 'raw-transformed'), new File(work, 'transformed')] + rawClasspath,
                    'CpmRawSourceProbe', [rawFamily, carrierFixtures.get().asFile.absolutePath, new File(work, 'raw-config.json').path])
        }
        if (rawFamily == null) {
            File seams = new File(work, 'material-host'); seams.mkdirs()
            File fixture = new File(work, 'material-fixture'); fixture.mkdirs()
            File fixtureClasses = new File(work, 'material-fixture-classes'); fixtureClasses.mkdirs()
            List<File> materialSources = project.fileTree(new File(probe, 'fixtures/material/java')).matching { include '**/*.java' }.files.sort { it.path }
            byte[] equipmentMixin = new ZipFile(cpm.first()).withCloseable { zip ->
                zip.getInputStream(zip.getEntry('com/tom/cpm/mixin/render/EquipmentLayerRendererMixin.class')).bytes
            }
            boolean[] glintBinding = [false] as boolean[]
            new org.objectweb.asm.ClassReader(equipmentMixin).accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                @Override org.objectweb.asm.MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    if (name == 'grabTextureGlint') glintBinding[0] = true
                    return null
                }
            }, org.objectweb.asm.ClassReader.SKIP_CODE)
            boolean glint = glintBinding[0]
            File originalFixture = materialSources.find { it.name == 'EquipmentLayerRenderer.java' }
            File generatedFixture = new File(work, 'material-source/EquipmentLayerRenderer.java')
            generatedFixture.parentFile.mkdirs()
            generatedFixture.text = originalFixture.getText('UTF-8').replace('RenderTypes.armorCutoutNoCull(selected);',
                    glint ? 'if (order == 1) RenderTypes.armorCutoutNoCullGlint(selected); else RenderTypes.armorCutoutNoCull(selected);' : 'RenderTypes.armorCutoutNoCull(selected);')
            materialSources = materialSources.findAll { it != originalFixture } + generatedFixture
            int materialResult = ToolProvider.systemJavaCompiler.run(null, System.out, System.err,
                    (['--release', '17', '-classpath', libraries, '-d', fixtureClasses.path] + materialSources*.path) as String[])
            if (materialResult != 0) throw new IllegalStateException('Material NCL fixture compilation failed')
            runProbe(work, 'material-link', [compiled] + probeClasspath.files, 'CpmFixtureLinker', [fixtureClasses.path, seams.path])
            String equipment = 'net/minecraft/client/renderer/entity/layers/EquipmentLayerRenderer.class'
            File fixtureClass = new File(fixture, equipment); fixtureClass.parentFile.mkdirs()
            java.nio.file.Files.move(new File(seams, equipment).toPath(), fixtureClass.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            List<File> materialClasspath = [seams, compiled, new File(probe, 'resources')] + probeClasspath.files + inputs
            runProbe(work, 'material-host', [compiled] + probeClasspath.files + inputs, 'CpmMaterialSetup', [seams.absolutePath])
            new File(seams, 'material-capture.json').text = groovy.json.JsonOutput.toJson([
                    required: true, package: 'com.tom.cpm.mixin.render', compatibilityLevel: 'JAVA_17',
                    client: ['EquipmentLayerRendererMixin'], injectors: [defaultRequire: 1]])
            runProbe(work, 'material-native', materialClasspath, 'CpmCompositionProbe', ['material-native'],
                    ['-Dnclskins.cpm.transformed=' + new File(work, 'material-native').absolutePath])
            runProbe(work, 'material-transform', [fixture] + materialClasspath, 'CpmCompositionProbe', ['material-fixture'],
                    ['-Dnclskins.cpm.transformed=' + new File(work, 'material-transformed').absolutePath])
            runProbe(work, 'material-behavior', [previewResourceTransforms, new File(work, 'material-transformed'), new File(work, 'transformed')] + materialClasspath,
                    'CpmBehaviorProbe', [new File(work, 'material-config.json').path, carrierFixtures.get().asFile.absolutePath],
                    ['-Dnclskins.cpm.materialProbe=true', '-Dnclskins.cpm.materialGlint=' + glint])
        }
        for (String topology : ['legacy', 'loader-compat']) {
            List<File> artifactClasspath = packagedClasspath.files.toList() + hostClasspath
            runProbe(work, 'accessory-artifact-transform-' + topology, artifactClasspath, 'CpmAccessoryCompositionProbe',
                    [new File(work, 'accessory-artifact-' + topology).absolutePath, topology],
                    ['-Dnclskins.cpm.artifact=' + packagedClasspath.singleFile.absolutePath])
            runProbe(work, 'accessory-artifact-behavior-' + topology,
                    [previewResourceTransforms, new File(work, 'accessory-artifact-' + topology), new File(work, 'transformed')] + artifactClasspath,
                    'CpmBehaviorProbe', [new File(work, 'accessory-config-' + topology + '.json').path,
                    carrierFixtures.get().asFile.absolutePath], ['-Dnclskins.cpm.capeProbe=true'])
        }
        [
                wings: ['com/tom/cpm/shared/model/RenderManager', 'actual transformed wings binding'],
                cloak: [modernAccessories ? 'com/tom/cpm/client/PlayerRenderManager$RedirectModelRenderer'
                        : 'com/tom/cpm/client/PlayerRenderManager$RedirectModelRendererBase',
                        'redirected cloak fall-through must emit ordinary geometry when CPM loses']
        ].each { String boundary, List<String> expected ->
            File mutation = new File(work, 'accessory-mutation-' + boundary)
            File originalOwner = new File(mutation, expected[0] + '.class')
            originalOwner.parentFile.mkdirs()
            originalOwner.bytes = new ZipFile(cpm.first()).withCloseable { zip ->
                zip.getInputStream(zip.getEntry(expected[0] + '.class')).bytes
            }
            List<File> mutationClasspath = [mutation, previewResourceTransforms, new File(work, 'transformed')] + hostClasspath
            File mutationLog = new File(work, 'accessory-mutation-' + boundary + '.log')
            File mutationTemp = new File(work, 'tmp-accessory-mutation-' + boundary); mutationTemp.mkdirs()
            Process mutated = new ProcessBuilder([new File(System.getProperty('java.home'), 'bin/java').path,
                    '-Djava.awt.headless=true', '-Dmax.bg.threads=1', '-Djava.io.tmpdir=' + mutationTemp.absolutePath,
                    '-cp', mutationClasspath*.absolutePath.join(File.pathSeparator),
                    'com.naocraftlab.skins.buildlogic.probe.CpmBehaviorProbe', new File(work, 'mutation-' + boundary + '-config.json').path,
                    carrierFixtures.get().asFile.absolutePath]).redirectErrorStream(true).redirectOutput(mutationLog).start()
            boolean mutationFinished = mutated.waitFor(60, TimeUnit.SECONDS)
            if (!mutationFinished) mutated.destroyForcibly()
            if (!mutationFinished || mutated.exitValue() == 0 || !mutationLog.text.contains(expected[1]))
                throw new IllegalStateException('Accessory behavior probe did not reject missing production ' + boundary + ' gate')
            logger.lifecycle('Accessory mutation rejected: bypassing the production ' + boundary + ' gate fails behavior')
        }
        runProbe(work, 'owned-preview-resource-dispatch',
                [previewResourceTransforms, new File(work, 'transformed'), modelGuard] + hostClasspath,
                'CpmOwnedPreviewDispatchProbe',
                [modelFixtures.absolutePath, new File(work, 'owned-preview-resource-dispatch-output').absolutePath, 'resources'])
        runProbe(work, 'project-defaults-uuid-preview',
                [defaultsHost, projectTransforms, previewResourceTransforms, new File(work, 'transformed')] + hostClasspath,
                'CpmProjectDefaultsProbe',
                [new File(probe, 'fixtures/import-feasibility').absolutePath,
                 new File(work, 'project-defaults-uuid-preview-output').absolutePath, 'uuid-preview'],
                ['-Dnclskins.cpm.original=' + cpm.first().absolutePath])
        List<File> behaviorClasspath = [previewResourceTransforms, new File(work, 'transformed'), compiled] + runtimeMixins
        behaviorClasspath.addAll(probeClasspath.files)
        behaviorClasspath.addAll(inputs)
        runProbe(work, 'behavior', behaviorClasspath, 'CpmBehaviorProbe',
                [new File(work, 'fixture-config.json').path, carrierFixtures.get().asFile.absolutePath])
    }

    private void runProbe(File work, String label, List<File> classpath, String main, List<String> args,
            List<String> properties = []) {
        File output = new File(work, label + '.log')
        File temp = new File(work, 'tmp-' + label)
        if (!temp.mkdirs()) throw new IllegalStateException('CPM probe temporary directory is not fresh: ' + label)
        List<String> command = [new File(System.getProperty('java.home'), 'bin/java').path,
                '-Djava.awt.headless=true', '-Dmax.bg.threads=1', '-Djava.io.tmpdir=' + temp.absolutePath] + properties +
                ['-cp', classpath*.absolutePath.join(File.pathSeparator), 'com.naocraftlab.skins.buildlogic.probe.' + main] + args
        new File(work, label + '-command.json').text = groovy.json.JsonOutput.toJson(command)
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true).redirectOutput(output).start()
        boolean finished = process.waitFor(60, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        logger.lifecycle(output.getText('UTF-8').trim())
        if (!finished || process.exitValue() != 0) throw new IllegalStateException('Native CPM probe failed: ' + label)
    }
}

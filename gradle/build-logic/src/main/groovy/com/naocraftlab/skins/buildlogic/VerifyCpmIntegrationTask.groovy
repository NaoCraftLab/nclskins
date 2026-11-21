package com.naocraftlab.skins.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction

import javax.tools.ToolProvider
import java.util.zip.ZipFile

abstract class VerifyCpmIntegrationTask extends DefaultTask {
    @Classpath abstract ConfigurableFileCollection getResolutionClasspath()
    @Classpath abstract ConfigurableFileCollection getPackagedClasspath()
    @Classpath abstract ConfigurableFileCollection getProbeClasspath()
    @InputDirectory abstract DirectoryProperty getProbeDirectory()
    @InputDirectory abstract DirectoryProperty getCarrierFixtures()
    @OutputDirectory abstract DirectoryProperty getWorkDirectory()
    @Optional @InputFile abstract RegularFileProperty getNativeBodyProof()
    @Optional @Input abstract Property<String> getNativeBodyProfile()
    @Optional @Input abstract Property<String> getNativeBodyLoader()
    @Optional @Input abstract Property<Boolean> getStringUserProfileId()

    static List<File> selectProbeSources(List<File> sources, boolean stringProfileId) {
        Set<String> stringOnly = ['NativeCurrentAppearanceProbe.java', 'NativeRenderIdentityProbe.java',
                                  'NativeUserProfileReadProbe.java'] as Set
        sources.findAll { stringProfileId || !stringOnly.contains(it.name) }
    }

    static boolean declaresExtendableClass(String accessRules, String owner) {
        accessRules.readLines().any { line ->
            List<String> tokens = line.tokenize()
            tokens == ['extendable', 'class', owner] || tokens == ['public-f', owner.replace('/', '.')]
        }
    }

    static void validateNativeCompletion(Map proof,String profile,String loader) {
        def immediate=['body','owner-live','owner-decline','capture-omission','image-readiness']
        def staged=['measurement-cases','baked-menu-cold','optional-availability','warm-restart','capture-omission','body','owner-live','owner-decline']
        def profiles=['immediate-26.1':'fabric','immediate-26.1-neoforge':'neoforge','staged-26.3-fabric':'fabric','staged-26.3-neoforge':'neoforge']
        if(!(loader in ['fabric','neoforge']) || !(profile=='staged' || profiles[profile]==loader)
                || proof.status!='PASS' || proof.profile!=profile || proof.loader!=loader || proof.producer!='genuine-native-mesh' || proof.consumer!='canonical-body-material-ack')
            throw new IllegalStateException('Canonical native BODY proof is missing')
        def expected=profile.startsWith('immediate-')?immediate:profile=='staged'?staged+['image-readiness']:staged[0..5]+['body-capture-omission']+staged[6..7]+['image-readiness']
        if(proof.roster!=expected)throw new IllegalStateException('Canonical native BODY roster is incomplete')
    }

    static String selectSubmissionLoader(boolean renderer, boolean aw, boolean at, boolean neoRuntime) {
        if (!renderer) return null
        if (aw && !neoRuntime) return 'fabric'
        if (at && neoRuntime) return 'neoforge'
        throw new IllegalStateException('Submission renderer has no matched native loader access contract')
    }

    static void validateSubmissionCompletion(Map proof,String loader) {
        if (!(loader in ['fabric','neoforge']) || proof.status!='PASS' || proof.profile!='submission-1.21.11'
                || proof.loader!=loader || proof.producer!='genuine-native-mesh' || proof.consumer!='own-cpm-hidden-visible-common-ack'
                || proof.roster!=['capture-omission','exceptional-cleanup','reference-body-numeric','hidden-visible-common-ack'])
            throw new IllegalStateException('Canonical submission completion is missing or incomplete')
    }

    private boolean submissionCapture
    private boolean submissionCaptureComplete
    private String submissionLoader

    @TaskAction void verify() {
        if (nativeBodyProof.isPresent() != nativeBodyProfile.isPresent() || nativeBodyProof.isPresent() != nativeBodyLoader.isPresent())
            throw new IllegalStateException('Native BODY proof/profile/loader task binding is incomplete')
        if (nativeBodyProof.isPresent()) {
            def proof = new groovy.json.JsonSlurper().parse(nativeBodyProof.get().asFile)
            validateNativeCompletion(proof,nativeBodyProfile.get(),nativeBodyLoader.get())
        }
        File work = workDirectory.get().asFile
        project.delete(work)
        File compiled = new File(work, 'classes')
        compiled.mkdirs()
        File probe = probeDirectory.get().asFile
        List<File> sources = selectProbeSources(project.fileTree(new File(probe, 'java'))
                .matching { include '**/*.java' }.files.sort { it.path }, stringUserProfileId.getOrElse(false))
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
        submissionCapture = inputs.any { File input ->
            if (input.isDirectory()) return new File(input, 'com/naocraftlab/skins/compat/client/identifier/submission/SubmissionPreviewRenderer.class').isFile()
            input.name.endsWith('.jar') && new ZipFile(input).withCloseable { zip ->
                zip.getEntry('com/naocraftlab/skins/compat/client/identifier/submission/SubmissionPreviewRenderer.class') != null
            }
        }
        boolean aw=new ZipFile(cpm.first()).withCloseable { it.getEntry('cpm.accesswidener')!=null }
        boolean at=new ZipFile(cpm.first()).withCloseable { it.getEntry('META-INF/accesstransformer.cfg')!=null }
        boolean neoRuntime=inputs.any { file -> file.isFile() && file.name.endsWith('.jar') && new ZipFile(file).withCloseable { it.getEntry('net/neoforged/neoforge/client/event/RenderPlayerEvent.class')!=null } }
        submissionLoader=selectSubmissionLoader(submissionCapture,aw,at,neoRuntime)
        submissionCapture=submissionLoader!=null
        submissionCaptureComplete=false
        String modelPart='net/minecraft/client/model/geom/ModelPart'
        boolean extendable=new ZipFile(cpm.first()).withCloseable { zip ->
            ['cpm.accesswidener', 'META-INF/accesstransformer.cfg'].any { resource ->
                def entry = zip.getEntry(resource)
                entry != null && declaresExtendableClass(zip.getInputStream(entry).getText('UTF-8'), modelPart)
            }
        }
        if (extendable) {
            byte[] original=inputs.findResult { File input ->
                if(input.isDirectory()) { File file=new File(input,modelPart+'.class');return file.isFile()?file.bytes:null }
                if(!input.name.endsWith('.jar')) return null
                new ZipFile(input).withCloseable { zip -> def entry=zip.getEntry(modelPart+'.class');entry==null?null:zip.getInputStream(entry).bytes }
            }
            if(original==null) throw new IllegalStateException('Pinned CPM extendable ModelPart prerequisite missing')
            def node=new org.objectweb.asm.tree.ClassNode();new org.objectweb.asm.ClassReader(original).accept(node,0)
            node.access &= ~org.objectweb.asm.Opcodes.ACC_FINAL
            def writer=new org.objectweb.asm.ClassWriter(0);node.accept(writer)
            File access=new File(work,'loader-access');File file=new File(access,modelPart+'.class');file.parentFile.mkdirs();file.bytes=writer.toByteArray()
            inputs.add(0,access)
        }
        List<File> runtimeMixins = inputs.findAll { it.name.contains('sponge-mixin') || it.name.startsWith('mixinextras-') }
        List<File> hostClasspath = [compiled, work, new File(probe, 'resources')] + runtimeMixins + probeClasspath.files + inputs
        File modelTransforms = new File(work, 'model-transformed')
        new File(work, 'metadata-binding.json').text = groovy.json.JsonOutput.toJson([
                required: true, package: 'com.naocraftlab.skins.compat.cpm.mixin',
                plugin: 'com.naocraftlab.skins.compat.cpm.CpmMixinPlugin', compatibilityLevel: 'JAVA_17',
                client: ['CpmModelDefinitionPartsAccessor', 'CpmModelSkinTextureAccessor', 'CpmModelTextureAccessor', 'CpmModelFileBackupAccessor',
                         'CpmResolvedPartsAccessor', 'CpmResolvedOtherPartsAccessor', 'CpmCopyTransformsAccessor', 'CpmCopyVisibilityAccessor',
                         'CpmAnimationStateAccessor', 'CpmVisibilityTargetAccessor', 'CpmLegacyAnimationsAccessor', 'CpmLegacyVisibilityAccessor']])
        runProbe(work, 'model-metadata-transform', hostClasspath, 'CpmModelMetadataTransformProbe', [modelTransforms.absolutePath])
        runProbe(work, 'model-metadata-shapes', hostClasspath, 'CpmModelMetadataTransformProbe', [modelTransforms.absolutePath, 'shapes'])
        runProbe(work, 'model-metadata-server', hostClasspath, 'CpmModelMetadataTransformProbe', [modelTransforms.absolutePath, 'server'])
        hostClasspath = [modelTransforms] + hostClasspath

        runProbe(work, 'model-metadata-native', hostClasspath, 'CpmModelMetadataProbe',
                [new File(probe, 'fixtures/model-conversion').absolutePath, new File(work, 'model-metadata-output').absolutePath])
        File sessionHost = new File(work, 'session-host'); sessionHost.mkdirs()
        runProbe(work, 'session-host-setup', hostClasspath, 'CpmSessionPublicationProbe', ['setup', sessionHost.absolutePath])
        runProbe(work, 'session-native', [sessionHost] + hostClasspath, 'CpmSessionPublicationProbe', ['run'])
        boolean stringProfileId = stringUserProfileId.getOrElse(false)
        if (stringProfileId) {
            File userObserver = new File(work, 'native-user-observer'); userObserver.mkdirs()
            runProbe(work, 'native-user-observer-setup', hostClasspath, 'NativeUserProfileReadProbe', ['instrument', userObserver.absolutePath])
            runProbe(work, 'native-render-identity', [userObserver] + hostClasspath, 'NativeRenderIdentityProbe', [])
            runProbe(work, 'native-user-session', [sessionHost, userObserver] + hostClasspath, 'NativeUserProfileReadProbe', ['session'])
            File appearanceHost = new File(work, 'native-appearance-host'); appearanceHost.mkdirs()
            runProbe(work, 'native-appearance-host-setup', hostClasspath, 'NativeCurrentAppearanceProbe', ['setup', appearanceHost.absolutePath])
            runProbe(work, 'native-current-appearance', [appearanceHost, userObserver] + hostClasspath, 'NativeCurrentAppearanceProbe', ['run'])
        }
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
        for (String mode : ['hidden', 'copy', 'copy-position', 'mapped-null', 'animated', 'legacy-null', 'legacy-animated',
                            'visible', 'visible-anchor', 'cape-hidden', 'cape-visible', 'default', 'retexture', 'empty-root', 'unused-sheet', 'body-only', 'unknown',
                            'rootless-sheet', 'cape-rootless-sheet', 'left-sheet', 'right-sheet', 'pack', 'null-part', 'memo-owned', 'memo-world']) {
            runProbe(work, 'accessory-content-' + mode,
                    [previewResourceTransforms, new File(work, 'transformed')] + hostClasspath, 'CpmAccessoryContentProbe',
                    [new File(work, 'content-' + mode + '-config.json').absolutePath, carrierFixtures.get().asFile.absolutePath, mode])
        }
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
            List<File> rawClasspath = [modelTransforms, seams, compiled, new File(probe, 'resources')] + probeClasspath.files + inputs
            runProbe(work, 'raw-transform', rawClasspath, 'CpmCompositionProbe', ['raw-' + rawFamily],
                    ['-Dnclskins.cpm.transformed=' + new File(work, 'raw-transformed').absolutePath])
            runProbe(work, 'raw-behavior', [previewResourceTransforms, new File(work, 'raw-transformed'), new File(work, 'transformed')] + rawClasspath,
                    'CpmRawSourceProbe', [rawFamily, carrierFixtures.get().asFile.absolutePath, new File(work, 'raw-config.json').path])
        }
        if (submissionCapture) {
            File nativeClasses = new File(work, 'submission-capture-classes'); nativeClasses.mkdirs()
            File nativeSdk = inputs.find { File input ->
                input.isFile() && input.name.endsWith('.jar') && new ZipFile(input).withCloseable { zip ->
                    zip.getEntry('net/minecraft/client/renderer/entity/LivingEntityRenderer.class') != null
                }
            }
            if (nativeSdk == null) throw new IllegalStateException('Selected submission SDK is missing')
            File lwjgl = inputs.find { it.name ==~ /lwjgl-[0-9]+\.[0-9]+\.[0-9]+\.jar/ }
            if (lwjgl == null) throw new IllegalStateException('Selected submission LWJGL dependency is missing')
            String lwjglVersion = lwjgl.name.substring('lwjgl-'.length(), lwjgl.name.length() - '.jar'.length())
            String os = System.getProperty('os.name').toLowerCase(Locale.ROOT)
            boolean arm = System.getProperty('os.arch') in ['aarch64', 'arm64']
            String classifier = os.contains('mac') ? (arm ? 'natives-macos-arm64' : 'natives-macos') :
                    os.contains('win') ? (arm ? 'natives-windows-arm64' : 'natives-windows') :
                    (arm ? 'natives-linux-arm64' : 'natives-linux')
            def platform = project.configurations.detachedConfiguration(*['lwjgl', 'lwjgl-stb', 'lwjgl-jemalloc'].collect {
                project.dependencies.create("org.lwjgl:${it}:${lwjglVersion}:${classifier}")
            })
            platform.transitive = false
            List<File> nativePlatform = platform.files.toList()
            List<File> nativeClasspath = [nativeSdk, compiled, new File(probe, 'resources')] + runtimeMixins + probeClasspath.files + inputs + nativePlatform
            List<File> nativeSources = project.fileTree(new File(probe, 'nativeMeasurement/submission/java')).matching { include '**/*.java' }.files.sort { it.path }
            if (nativeSources.size() != 3) throw new IllegalStateException('Submission CPU fixture source closure is incomplete')
            nativeSources += new File(probe,'nativeMeasurement/java/com/naocraftlab/skins/buildlogic/probe/FixtureCleanup.java')
            if (submissionLoader=='neoforge') nativeSources += new File(probe,'nativeMeasurement/immediate/neoforge/java/com/naocraftlab/skins/buildlogic/probe/NeoImmediateEvents.java')
            int nativeResult = ToolProvider.systemJavaCompiler.run(null, System.out, System.err,
                    (['-proc:none', '--release', '17', '-classpath', nativeClasspath*.path.join(File.pathSeparator), '-d', nativeClasses.path] + nativeSources*.path) as String[])
            if (nativeResult != 0) throw new IllegalStateException('Submission CPU fixture compilation failed')
            File nativeInputs = new File(work, 'submission-capture-inputs')
            runProbe(work, 'submission-capture-setup', [nativeClasses] + nativeClasspath,
                    'SubmissionCaptureSetup', [nativeInputs.path, nativeClasses.path])
            runProbe(work,'submission-cleanup',[nativeInputs,nativeClasses]+hostClasspath+nativePlatform,
                    'CpmBehaviorProbe',[new File(work,'submission-cleanup-config.json').path,carrierFixtures.get().asFile.path,'submission-capture'],
                    ['--enable-native-access=ALL-UNNAMED','-Dnclskins.cpm.submissionCapture=true','-Dnclskins.cpm.submissionCleanup=true'])
            if (!new File(work,'submission-cleanup.log').text.contains('PASS SUBMISSION_CLEANUP exact unregister failure restores borrowed state and closes owned resources'))
                throw new IllegalStateException('Submission exceptional cleanup was not executed')
            File omitted=new File(work,'submission-omitted-inputs')
            runProbe(work,'submission-omission-setup',[nativeClasses]+nativeClasspath,'SubmissionCaptureSetup',[omitted.path,nativeClasses.path,'omit-vertices'])
            runProbe(work,'submission-omission',[omitted,nativeClasses]+hostClasspath+nativePlatform,'CpmBehaviorProbe',
                    [new File(work,'submission-omission-config.json').path,carrierFixtures.get().asFile.path,'submission-capture'],
                    ['--enable-native-access=ALL-UNNAMED','-Dnclskins.cpm.submissionCapture=true'],
                    'java.lang.AssertionError: actual native reference and BODY captures emit vertices')
            String omission=new File(work,'submission-omission.log').text
            if (!(omission =~ /SUBMISSION_CAPTURE_WITNESS roots=[1-9][0-9]* draws=[1-9][0-9]* captures=0 capturedVertices=0 creates=0 persisted=ABSENT/).find())
                throw new IllegalStateException('Submission omission did not reach genuine geometry with absent numeric fit')
            runProbe(work, 'submission-capture-behavior', [nativeInputs, nativeClasses] + hostClasspath + nativePlatform,
                    'CpmBehaviorProbe', [new File(work, 'submission-capture-config.json').path,
                    carrierFixtures.get().asFile.absolutePath, 'submission-capture'],
                    ['--enable-native-access=ALL-UNNAMED', '-Dnclskins.cpm.submissionCapture=true'])
            String positive=new File(work,'submission-capture-behavior.log').text
            if (!positive.contains('PASS SUBMISSION_CPM actual native reference/BODY/numeric -> own hidden image/material/fence -> visible image/fence/common ACK'))
                throw new IllegalStateException('Submission own CPM image flow is incomplete')
            def completion=[status:'PASS',profile:'submission-1.21.11',loader:submissionLoader,producer:'genuine-native-mesh',consumer:'own-cpm-hidden-visible-common-ack',
                    roster:['capture-omission','exceptional-cleanup','reference-body-numeric','hidden-visible-common-ack']]
            validateSubmissionCompletion(completion,submissionLoader)
            new File(work,'submission-completion.json').text=groovy.json.JsonOutput.toJson(completion)
            submissionCaptureComplete=true
        }
        if (rawFamily == null) {
            File seams = new File(work, 'material-host'); seams.mkdirs()
            File fixture = new File(work, 'material-fixture'); fixture.mkdirs()
            File fixtureClasses = new File(work, 'material-fixture-classes'); fixtureClasses.mkdirs()
            List<File> materialSources = project.fileTree(new File(probe, 'fixtures/material/java')).matching { include '**/*.java' }.files.sort { it.path }
            byte[] equipmentMixin = new ZipFile(cpm.first()).withCloseable { zip ->
                zip.getInputStream(zip.getEntry('com/tom/cpm/mixin/render/EquipmentLayerRendererMixin.class')).bytes
            }
            def materialMixin = new org.objectweb.asm.tree.ClassNode()
            new org.objectweb.asm.ClassReader(equipmentMixin).accept(materialMixin, org.objectweb.asm.ClassReader.SKIP_CODE)
            boolean glint = materialMixin.methods.any { it.name == 'grabTextureGlint' }
            boolean returnsInt = materialMixin.methods.any { method ->
                method.name == 'grabTexture' && method.visibleAnnotations?.any { annotation ->
                    annotation.desc == 'Lorg/spongepowered/asm/mixin/injection/Inject;' && annotation.values.collate(2).any { pair ->
                        pair[0] == 'method' && pair[1].any { selector -> selector.startsWith('renderLayers(') && selector.endsWith(')I') }
                    }
                }
            }
            File originalFixture = materialSources.find { it.name == 'EquipmentLayerRenderer.java' }
            File generatedFixture = new File(work, 'material-source/EquipmentLayerRenderer.java')
            generatedFixture.parentFile.mkdirs()
            String baseMaterial = 'RenderTypes.armorCutoutNoCull(selected);'
            String material = glint ? 'if (order == 1) RenderTypes.armorCutoutNoCullGlint(selected); else ' + baseMaterial : baseMaterial
            String fixtureSource = originalFixture.getText('UTF-8')
            if (returnsInt) fixtureSource = fixtureSource.replace('public void renderLayers(', 'public int renderLayers(')
            generatedFixture.text = fixtureSource.replace(baseMaterial, material + (returnsInt ? '\n  return 0;' : ''))
            materialSources = materialSources.findAll { it != originalFixture } + generatedFixture
            int materialResult = ToolProvider.systemJavaCompiler.run(null, System.out, System.err,
                    (['--release', '17', '-classpath', libraries, '-d', fixtureClasses.path] + materialSources*.path) as String[])
            if (materialResult != 0) throw new IllegalStateException('Material NCL fixture compilation failed')
            runProbe(work, 'material-link', [compiled] + probeClasspath.files, 'CpmFixtureLinker', [fixtureClasses.path, seams.path])
            String equipment = 'net/minecraft/client/renderer/entity/layers/EquipmentLayerRenderer.class'
            File fixtureClass = new File(fixture, equipment); fixtureClass.parentFile.mkdirs()
            java.nio.file.Files.move(new File(seams, equipment).toPath(), fixtureClass.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            List<File> materialClasspath = [modelTransforms, seams, compiled, new File(probe, 'resources')] + probeClasspath.files + inputs
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
            List<String> mutationCommand = [new File(System.getProperty('java.home'), 'bin/java').path,
                    '-Djava.awt.headless=true', '-Dmax.bg.threads=1', '-Djava.io.tmpdir=' + mutationTemp.absolutePath,
                    '-cp', mutationClasspath*.absolutePath.join(File.pathSeparator),
                    'com.naocraftlab.skins.buildlogic.probe.CpmBehaviorProbe', new File(work, 'mutation-' + boundary + '-config.json').path,
                    carrierFixtures.get().asFile.absolutePath]
            runMutation(mutationCommand, mutationLog, boundary, expected[1])
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
        List<File> behaviorClasspath = [previewResourceTransforms, new File(work, 'transformed'), modelTransforms, compiled] + runtimeMixins
        behaviorClasspath.addAll(probeClasspath.files)
        behaviorClasspath.addAll(inputs)
        runProbe(work, 'behavior', behaviorClasspath, 'CpmBehaviorProbe',
                [new File(work, 'fixture-config.json').path, carrierFixtures.get().asFile.absolutePath])
        runProbe(work, 'preview-loading-geometry', behaviorClasspath, 'CpmOwnedPreviewDispatchProbe', ['loading-geometry'])
        if (rawFamily == null) {
            runProbe(work, 'preview-static-surfaces', behaviorClasspath, 'CpmBakedDisplayProbe', ['card-image'])
            runProbe(work, 'preview-gallery-mode', behaviorClasspath, 'CpmBakedDisplayProbe', ['gallery-mode'])
            runProbe(work, 'preview-gallery-hover', behaviorClasspath, 'CpmBakedDisplayProbe', ['hidden-image', 'gallery-hover'])
            runProbe(work, 'preview-menu-panel', behaviorClasspath, 'CpmBakedDisplayProbe', ['menu-panel'])
            runProbe(work, 'preview-retention-host', behaviorClasspath, 'CpmBakedDisplayProbe', ['retention-host'])
            runProbe(work, 'preview-retention-image', behaviorClasspath, 'CpmBakedDisplayProbe', ['retention-image'])
        } else {
            runProbe(work, 'preview-static-surfaces', behaviorClasspath, 'CpmBakedDisplayProbe', ['immediate-static', rawFamily])
            runProbe(work, 'preview-menu-panel', behaviorClasspath, 'CpmBakedDisplayProbe', ['immediate-panel', rawFamily])
            runProbe(work, 'preview-retention-host', behaviorClasspath, 'CpmPreviewScopeProbe', ['provider-retention'])
            runProbe(work, 'preview-retention-delayed-root', behaviorClasspath, 'CpmBehaviorProbe',
                    [new File(work, 'loading-config.json').path, carrierFixtures.get().asFile.absolutePath, 'loading-canonical'])
        }

    }

    private void runProbe(File work, String label, List<File> classpath, String main, List<String> args,
            List<String> properties = [], String expectedFailure = null) {
        if (main == 'CpmBehaviorProbe' && nativeBodyProof.isPresent()
                && nativeBodyProfile.get() in ['staged','immediate-26.1','immediate-26.1-neoforge','staged-26.3-fabric','staged-26.3-neoforge'])
            properties = properties + ['-Dnclskins.cpm.extractionImageProof=true']
        if (main == 'CpmBehaviorProbe' && args.size() == 2 && submissionCaptureComplete) properties = properties + ['-Dnclskins.cpm.submissionCaptureDeferred=true']
        if (main == 'CpmBehaviorProbe' && args.size() == 2 && nativeBodyProof.isPresent()) args = args + ['protocol']
        File output = new File(work, label + '.log')
        File temp = new File(work, 'tmp-' + label)
        if (!temp.mkdirs()) throw new IllegalStateException('CPM probe temporary directory is not fresh: ' + label)
        List<String> command = [new File(System.getProperty('java.home'), 'bin/java').path,
                '-Djava.awt.headless=true', '-Dmax.bg.threads=1', '-Djava.io.tmpdir=' + temp.absolutePath] + properties +
                ['-cp', classpath*.absolutePath.join(File.pathSeparator), 'com.naocraftlab.skins.buildlogic.probe.' + main] + args
        new File(work, label + '-command.json').text = groovy.json.JsonOutput.toJson(command)
        Process process = startCpmProcess(command, output)
        int exit = CompositionProcess.await(process, 60_000L, 5_000L)
        IllegalStateException failure = exit != 0 ? new IllegalStateException('Native CPM probe failed: ' + label) : null
        try {
            logger.lifecycle(output.getText('UTF-8').trim())
        } catch (Throwable reporting) {
            if (failure == null) throw reporting
            failure.addSuppressed(reporting)
        }
        if (expectedFailure != null) {
            String text=output.text
            if (exit==0 || !text.contains(expectedFailure) || text.contains('LinkageError') || text.contains('Caused by:') || text.contains('Suppressed:'))
                throw new IllegalStateException('Expected exact clean native sensitivity failure: '+label)
            return
        }
        if (failure != null) throw failure
    }

    protected Process startCpmProcess(List<String> command, File output) {
        new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output).start()
    }

    protected void runMutation(List<String> command, File output, String boundary, String expected) {
        Process mutated = startCpmProcess(command, output)
        int exit = CompositionProcess.await(mutated, 60_000L, 5_000L)
        if (exit == 0 || !output.text.contains(expected))
            throw new IllegalStateException('Accessory behavior probe did not reject missing production ' + boundary + ' gate')
        logger.lifecycle('Accessory mutation rejected: bypassing the production ' + boundary + ' gate fails behavior')
    }
}

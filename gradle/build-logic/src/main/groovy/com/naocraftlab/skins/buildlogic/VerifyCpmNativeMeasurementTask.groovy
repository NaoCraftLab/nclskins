package com.naocraftlab.skins.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import javax.tools.ToolProvider
import java.util.zip.ZipFile
import groovy.json.JsonOutput

abstract class VerifyCpmNativeMeasurementTask extends DefaultTask {
    @Classpath abstract ConfigurableFileCollection getResolutionClasspath()
    @Classpath abstract ConfigurableFileCollection getProbeClasspath()
    @InputDirectory abstract DirectoryProperty getProbeDirectory()
    @InputDirectory abstract DirectoryProperty getCarrierFixtures()
    @Input abstract Property<String> getLoaderFamily()
    @Input abstract Property<String> getNativeProfile()
    @OutputDirectory abstract DirectoryProperty getWorkDirectory()

    VerifyCpmNativeMeasurementTask() { nativeProfile.convention('staged') }

    @TaskAction void verify() {
        if (nativeProfile.get() in ['immediate-26.1','immediate-26.1-neoforge']) { verifyImmediate(); return }
        if (!(nativeProfile.get() in ['staged','staged-26.3-fabric','staged-26.3-neoforge'])) throw new IllegalStateException('Unknown native measurement profile')
        if(nativeProfile.get().startsWith('staged-26.3-') && nativeProfile.get()!='staged-26.3-'+loaderFamily.get()) throw new IllegalStateException('Selected native profile/loader mismatch')
        File work=resetOwnedOutput()
        File probe=probeDirectory.get().asFile
        File classes=new File(work,'classes');classes.mkdirs()
        List<File> libs=resolutionClasspath.files.toList()+probeClasspath.files.toList()
        List<File> nativeMixins=resolutionClasspath.files.findAll { it.name.contains('sponge-mixin') || it.name.startsWith('mixinextras-') }.toList()
        libs=nativeMixins+libs.findAll { !nativeMixins.contains(it) }
        File cpm=libs.find { hasEntry(it,'com/tom/cpm/client/PlayerRenderStateAccess.class') }
        File ears=libs.find { hasEntry(it,'diy/y2k/five/thermite/EarsLayerRenderer.class') }
        if(cpm==null||ears==null)throw new IllegalStateException('Pinned native CPM/Ears dependency closure missing')
        File resources=new File(probe,'resources')
        File selectedSdk=null
        if(nativeProfile.get().startsWith('staged-26.3-')) {
            File sdk=resolutionClasspath.files.find { hasEntry(it,'net/minecraft/client/renderer/StagedVertexBuffer.class') }
            selectedSdk=sdk
            if(sdk==null)throw new IllegalStateException('Selected 26.3 SDK missing')
            List<File> sources=project.fileTree(new File(probe,'java')).matching { include '**/*.java' }.files.toList().sort { it.path }+
                    project.fileTree(new File(probe,'nativeMeasurement/java')).matching { include '**/*.java'; exclude '**/PipCpuBackend.java' }.files.toList().sort { it.path }+
                    project.fileTree(new File(probe,'nativeMeasurement/staged263/java')).matching { include '**/*.java' }.files.toList().sort { it.path }+
                    (loaderFamily.get()=='neoforge' ? project.fileTree(new File(probe,'nativeMeasurement/neoforge/java')).matching { include '**/*.java' }.files.toList().sort { it.path }+['NeoImmediateEvents.java','NeoEventCleanupProbe.java'].collect { new File(probe,'nativeMeasurement/immediate/neoforge/java/com/naocraftlab/skins/buildlogic/probe/'+it) } : [])
            List<File> exact=[sdk]+libs.findAll { it!=sdk }
            new File(work,'actual-compile-inputs.json').text=JsonOutput.toJson([loader:loaderFamily.get(),profile:nativeProfile.get(),sdk:sdk.path,release:17,javaHome:System.getProperty('java.home'),resolutionClasspath:resolutionClasspath.files*.path,probeClasspath:probeClasspath.files*.path,compileClasspath:exact*.path,sources:sources*.path])
            compile(sources,classes,exact)
        }
        List<File> base=[classes,work,resources,ears]+libs.findAll { it!=ears }
        File access=new File(work,'loader-access')
        compile([new File(probe,'nativeMeasurement/java/com/naocraftlab/skins/buildlogic/probe/PrepareAccess.java')],classes,base)
        run(work,'access',base,'PrepareAccess',[cpm.path,access.path])
        base=[access]+base
        if(!nativeProfile.get().startsWith('staged-26.3-')) compile(project.fileTree(new File(probe,'java')).matching { include '**/*.java' }.files.toList()
                +project.fileTree(new File(probe,'nativeMeasurement/java')).matching { include '**/*.java' }.files.toList()
                +project.fileTree(new File(probe,'nativeMeasurement/staged262/java')).matching { include '**/*.java' }.files.toList()
                +(loaderFamily.get()=='neoforge' ? project.fileTree(new File(probe,'nativeMeasurement/neoforge/java')).matching { include '**/*.java' }.files.toList()+['NeoImmediateEvents.java','NeoEventCleanupProbe.java'].collect { new File(probe,'nativeMeasurement/immediate/neoforge/java/com/naocraftlab/skins/buildlogic/probe/'+it) } : []),classes,base)
        config(work,'lifetime-binding.json','com.naocraftlab.skins.compat.client.identifier.mixin',
                ['SkinTextureDownloaderProviderMixin','NativeImageSneakyPixelsMixin','TextureManagerRegistrationMixin','DynamicTextureRegistrationMixin'])
        config(work,'ears-binding.json','diy.y2k.five.thermite.mixin',['MixinSkinTextureDownloader'])
        config(work,'metadata-binding.json','com.naocraftlab.skins.compat.cpm.mixin',
                ['CpmModelDefinitionPartsAccessor','CpmModelSkinTextureAccessor','CpmModelTextureAccessor','CpmModelFileBackupAccessor',
                 'CpmResolvedPartsAccessor','CpmResolvedOtherPartsAccessor','CpmCopyTransformsAccessor','CpmCopyVisibilityAccessor',
                 'CpmAnimationStateAccessor','CpmVisibilityTargetAccessor','CpmLegacyAnimationsAccessor','CpmLegacyVisibilityAccessor'])
        File metadata=new File(work,'metadata')
        run(work,'metadata',base,'CpmModelMetadataTransformProbe',[metadata.path]);base=[metadata]+base
        File transformed=new File(work,'transformed')
        run(work,'composition',base,'CpmCompositionProbe',['present'],['-Dnclskins.cpm.transformed='+transformed.path]);base=[transformed]+base
        File textures=new File(work,'texture-woven')
        run(work,'texture-weave',base,'com.naocraftlab.skins.probe.Weave',[textures.path])
        File textureInputs=new File(work,'texture-inputs')
        run(work,'texture-inputs',[textures]+base,'com.naocraftlab.skins.probe.Prepare',[textureInputs.path],['-Dprobe.nofml='+ (loaderFamily.get()=='neoforge')])
        File renderer=new File(work,'renderer-woven')
        run(work,'renderer-weave',base,'FullRendererWeave',[renderer.path,loaderFamily.get()])
        File cpu=new File(work,'cpu-inputs')
        run(work,'cpu-inputs',[textureInputs]+base,'FullRendererPrepare',[cpu.path,'ears'])
        base=[cpu,access,renderer,textureInputs,textures]+base
        File upstream=new File(work,'upstream-woven')
        File upstreamRaw=new File(work,'upstream-raw')
        ['net/minecraft/client/renderer/entity/player/AvatarRenderer.class','net/minecraft/client/renderer/entity/state/AvatarRenderState.class',
         'net/minecraft/client/renderer/entity/LivingEntityRenderer.class','net/minecraft/client/model/player/PlayerModel.class',
         'net/minecraft/client/renderer/entity/layers/CapeLayer.class','net/minecraft/client/renderer/entity/layers/WingsLayer.class'].each { String entry ->
            File sdk=libs.find { hasEntry(it,entry) }
            if(sdk==null)throw new IllegalStateException('Raw upstream SDK class missing: '+entry)
            File file=new File(upstreamRaw,entry);file.parentFile.mkdirs()
            new ZipFile(sdk).withCloseable { zip -> file.bytes=zip.getInputStream(zip.getEntry(entry)).bytes }
        }
        run(work,'upstream-weave',[upstreamRaw]+base,'UpstreamRendererWeave',[upstream.path,loaderFamily.get()])
        File accessed=new File(work,'upstream-access')
        run(work,'upstream-access',[upstream]+base,'PrepareAccess',[cpm.path,accessed.path])
        File raw=new File(work,'raw')
        ['net/minecraft/client/renderer/rendertype/RenderTypes.class','net/minecraft/client/renderer/texture/DynamicTexture.class'].each { String entry ->
            File sdk=libs.find { hasEntry(it,entry) }
            if(sdk==null)throw new IllegalStateException('Raw pinned SDK class missing: '+entry)
            File file=new File(raw,entry);file.parentFile.mkdirs()
            new ZipFile(sdk).withCloseable { zip -> file.bytes=zip.getInputStream(zip.getEntry(entry)).bytes }
        }
        base=[raw,accessed,upstream]+base
        File equipment=new File(work,'equipment-woven')
        run(work,'equipment-weave',base,'ReferenceEquipmentWeave',[equipment.path])
        File nativeOutput=new File(work,'native')
        run(work,'native-setup',[equipment]+base,'ReferenceBodySetup',[nativeOutput.path])
        config(work,'nclskins.cpm-neutral.probe.mixins.json','com.naocraftlab.skins.compat.cpm.mixin',['AnimationEngineMixin','ModelRenderManagerMixin'])
        config(work,'ears-model-binding.json','diy.y2k.five.thermite.mixin',['AccessorModelPart'])
        File fitting=new File(work,'fit-woven')
        base=[nativeOutput,equipment]+base
        run(work,'fit-weave',base,'ProductionFitWeave',[fitting.path])
        File bindings=new File(work,'bindings')
        base=[fitting]+base
        if(loaderFamily.get()=='neoforge') {
            File neoInputs=new File(work,'neo-inputs')
            for (String main : ['ReferencePhaseData','ReferenceNeoExtensions','ReferenceNeoTexture','ReferenceNeoProfile']) {
                run(work,main,base,main,[neoInputs.path])
                base=[neoInputs]+base.findAll { it!=neoInputs }
            }
        }
        if(nativeProfile.get().startsWith('staged-26.3-')) {
            File strict=new File(work,'strict-ide')
            run(work,'strict-ide',[selectedSdk]+base,'StrictIdeInput',[strict.path])
            base=[strict]+base
        }
        run(work,'bindings',base,'CpmNativeMeasurementBackend',[bindings.path])
        List<String> props=['--enable-native-access=ALL-UNNAMED','-Dnclskins.upstream.family='+loaderFamily.get(),
                '-Dnclskins.cpm.nativeMeasurement=true','-Dnclskins.fullLayers.ears=true','-Dprobe.dir='+work.path,
                '-Dorg.lwjgl.system.SharedLibraryExtractPath='+new File(work,'native-libs').path]
        for(String mode:['close','body','scope'])run(work,'cleanup-'+mode,base,'NativeCleanupProbe',[mode],props)
        if(nativeProfile.get().startsWith('staged-26.3-'))
            for(String mode:['auxiliary','render-data','samplers','borrowed'])run(work,'cleanup-abi-'+mode,base,'NativeAbiCleanupProbe',[mode],props)
        if(loaderFamily.get()=='neoforge')
            for(String mode:['second-add','start','unregister','positive'])run(work,'cleanup-events-'+mode,base,'NeoEventCleanupProbe',[mode],props)
        File generatedFixtures=new File(work,'measurement-fixtures')
        run(work,'measurement-fixtures',base,'NativeMeasurementFixtureGenerator',[generatedFixtures.path],props)
        File fixtures=new File(probe,'nativeMeasurement/fixtures')
        List<String> measurementProps=props.findAll { !it.startsWith('-Dnclskins.cpm.nativeMeasurement=') }+
                ['-Dnclskins.reference.mode=cpm','-Dnclskins.reference.combined='+new File(fixtures,'combined.png').path,'-Dnclskins.reference.ears='+new File(fixtures,'ears.png').path,
                 '-Dnclskins.pip.changedCarrier='+new File(fixtures,'authored-jump-decor-equipment.png').path]
        run(work,'measurement-cases',base,'ReferenceBodyProbe',[new File(work,'measurement-cases').path],measurementProps)
        run(work,'baked-menu-cold',base,'BakedMenuColdProbe',[new File(work,'measurement-cases/reference-body.json').path,
                new File(fixtures,'authored-jump-decor-equipment.png').path],measurementProps+
                ['-Xlog:class+load=info:file='+new File(work,'baked-menu-class-load.log').path])
        verifyBakedConsumers(work)
        def cases=new groovy.json.JsonSlurper().parse(new File(work,'measurement-cases/reference-body.json'))
        if(cases.caseCount!=9||cases.cases.size()!=9||cases.warmCaseCount!=9||cases.warmCases.size()!=9)throw new IllegalStateException('Native measurement case ledger incomplete')
        def semantic=new groovy.json.JsonSlurper().parse(new File(work,'semantic-demand.json'))
        def early=new groovy.json.JsonSlurper().parse(new File(work,'early-opening.json'))
        def lifecycle=new groovy.json.JsonSlurper().parse(new File(work,'lifecycle-first-frame.json'))
        def providers=new groovy.json.JsonSlurper().parse(new File(work,'providers-fallback-comparison.json'))
        if(providers.size()!=3 || providers*.mode.toSet()!=['declined','pending','failed'].toSet()
                || providers.any { !it.sameOwnerBaseline || it.nativePasses!=3 || it.vertices<=0
                    || it.nativeContributions.CPM_BODY<=0 || it.liveGeometry.size()!=3
                    || it.firstCreates!=0 || it.captures!=0 || it.displayKind!='ORDINARY' }
                || providers.find { it.mode=='declined' }.availabilityBoundary!='uninstalled-public-CPM-preparation')
            throw new IllegalStateException('Native Providers original-policy/required-CPM availability comparison incomplete')
        verifyFallbackPolicy(work)
        def retained=new groovy.json.JsonSlurper().parse(new File(work,'retained-fit.json'))
        def coarse=new groovy.json.JsonSlurper().parse(new File(work,'coarse-resource.json'))
        if(coarse.size()!=2 || coarse*.order.toSet()!=['desired','retained'].toSet()
                || coarse.any { it.staleVertices!=0 || it.staleMaterials!=0 || !it.retiredFit || !it.retiredTarget
                    || it.physicalCloses!=1 || it.freshDisplay!='CPM' || it.durable!='PRESENT'
                    || it.ambientFrames!=100 || it.ambientFull!=0 || it.ambientFactorOne!=0
                    || it.ambientNumericLoads!=0 || it.ambientCaptures!=0 || it.ambientCreates!=0
                    || it.ambientNative!='ORDINARY' || it.ambientCommon!='ORDINARY'
                    || it.refinedFrames!=10 || it.ordinaryControls!=true || it.sameDemandRenderRefinement!=true
                    || it.sameDemand!=true || it.ambientBeforeReopen!=true
                    || !(it.ambientVertices instanceof Number) || !(it.ambientMaterials instanceof Number)
                    || !(it.ambientDefinitions instanceof Number) || it.ambientVertices<=0
                    || it.ambientMaterials<=0 && it.ambientDefinitions<=0 })
            throw new IllegalStateException('Native coarse resource revocation/material/fence ledger incomplete')
        if(retained.size()!=2 || retained*.storedBeforeRetirement.toSet()!=[false,true].toSet()
                || retained.any { it.displayKind!='CPM' || it.zoom<=1 || it.aGeometry==it.cGeometry
                    || it.aNormalization==it.bNormalization || it.firstCreates!=(it.storedBeforeRetirement?3:2)
                    || it.staleFrames!=(it.storedBeforeRetirement?1:0) })
            throw new IllegalStateException('Native retained Gallery fit/source/material/ACK ledger incomplete')
        if(lifecycle.size()!=7 || lifecycle.collect { it.surface+':'+it.phase }.toSet().size()!=7
                || lifecycle*.surface.toSet()!=['gallery','editor','providers'].toSet()
                || lifecycle.any { it.phase=='timeout-reopened'
                    ? it.sameRuntime!=true || it.firstCreates!=1 || it.displayKind!='CPM' || it.nativePasses<3 || it.captures!=2
                    : it.surface=='providers'
                    ? it.phase!='providers-no-new-timeout' || it.firstCreates!=1 || it.displayKind!='CPM' || it.nativePasses<3 || it.captures!=2
                    : !(it.phase in ['deadline-queued-create','deadline-before-publication']) || it.firstCreates!=0 || it.captures!=2 || it.nativePasses!=2
                        || it.createAttempts!=(it.phase=='deadline-before-publication'?1:0) || it.publicationChecks!=(it.phase=='deadline-before-publication'?2:0)
                        || it.status!='RETIRED' || it.bodyStatus!='TIMED_OUT' || it.postTimeoutDemandCalls!=100 })
            throw new IllegalStateException('Native original-deadline/scoped Providers ledger incomplete')
        if(early.size()!=6 || early.collect { it.surface+':'+it.phase }.toSet().size()!=6
                || early*.surface.toSet()!=['gallery','editor','providers'].toSet()
                || early*.phase.toSet()!=['early-skip','same-runtime-reopen'].toSet()
                || early.any { it.phase=='early-skip'
                    ? it.hostFrames!=100 || it.diskLoads!=0 || it.captures!=0 || it.firstCreates!=0 || it.loadedHandles!=0 || it.nativeFitAdmissions!=0
                    : it.sameRuntime!=true || it.newNativeOwner!=true || it.readyFrames!=100 || it.captures!=2 || it.firstCreates!=1
                        || it.nativePasses<3 || it.diskLoads<1 || it.diskLoads>2 || it.displayKind!='CPM' })
            throw new IllegalStateException('Native early opening/same-runtime reopen ledger incomplete')
        if(semantic.size()!=6 || semantic.collect { it.surface+':'+it.mode }.toSet().size()!=6
                || semantic*.surface.toSet()!=['gallery','editor','providers'].toSet()
                || semantic*.mode.toSet()!=['worldless','missing-required'].toSet()
                || semantic.any { it.hostFrames!=100 || it.captures!=0 || it.firstCreates!=0
                    || (it.mode=='worldless' ? it.diskLoads!=0 : it.actualRequiredMissingChecks<1) })
            throw new IllegalStateException('Native semantic demand/lifecycle ledger incomplete')
        verifyScreenDemand(work)
        File optionalHost=new File(work,'optional-availability-host');optionalHost.mkdirs()
        run(work,'optional-availability-setup',base,'CpmOptionalEnvironmentProbe',['setup',optionalHost.path])
        run(work,'optional-availability-absent',[optionalHost]+base.findAll { !hasEntry(it,'com/tom/cpm/shared/MinecraftClientAccess.class') },
                'CpmOptionalEnvironmentProbe',['absent'])
        run(work,'warm-restart',base,'ReferenceBodyProbe',[new File(work,'warm-restart').path],measurementProps+
                ['-Dnclskins.numeric.restartManifest='+new File(work,'measurement-cases/reference-body.json').path])
        def restarted=new groovy.json.JsonSlurper().parse(new File(work,'warm-restart/reference-body.json'))
        if(restarted.caseCount!=0||restarted.warmCaseCount!=9||restarted.warmCases.size()!=9
                ||restarted.warmCases.any { it.newCaptures!=0||it.newNeutralDecodes!=0 })throw new IllegalStateException('Fresh-process numeric reuse incomplete')
        for (def row : restarted.warmCases) {
            def cold=cases.cases.find { it.'case'==row.'case' }
            if(cold==null||row.contentKey!=cold.contentKey||row.numericFit!=cold.numericFit||row.layoutTransforms!=cold.layoutTransforms)
                throw new IllegalStateException('Fresh-process numeric placement differs from actual cold producer')
        }
        run(work,'body',[bindings]+base,'CpmBehaviorProbe',[new File(work,'config.json').path,carrierFixtures.get().asFile.path,'body'],props+
                ['-Dnclskins.aba.carrier='+new File(fixtures,'authored-jump-decor-equipment.png').path])
        verifySameDemandAba(work)
        String bodyLog=new File(work,'body.log').text
        if (!(bodyLog =~ /CPM_WHITE_NATIVE_CONSUMER selections=[1-9][0-9]* vertices=[1-9][0-9]* bound=true/).find()
                || bodyLog.contains('Failed to load texture cpm:textures/white.png'))
            throw new IllegalStateException('The owned native BODY consumer did not sample the actual CPM white material')
        for(String mutation : ['missing','foreign']) {
            String label='body-white-'+mutation
            run(work,label,[bindings]+base,'CpmBehaviorProbe',[new File(work,label+'-config.json').path,carrierFixtures.get().asFile.path,'body'],props+
                    ['-Dnclskins.aba.carrier='+new File(fixtures,'authored-jump-decor-equipment.png').path,'-Dnclskins.measurement.whiteMutation='+mutation],
                    'java.lang.AssertionError: actual selected CPM white material must reach native target sampler')
            String negative=new File(work,label+'.log').text
            if (!(negative =~ /CPM_WHITE_NATIVE_CONSUMER selections=[1-9][0-9]* vertices=[1-9][0-9]* bound=false/).find()
                    || negative.contains('LinkageError') || negative.contains('Suppressed:') || negative.contains('Caused by:'))
                throw new IllegalStateException('White material sensitivity must reach the actual selected native draw')
        }
        File dropped=new File(work,'dropped-capture')
        run(work,'drop-capture-mutation',base,'NativeMeasurementCaptureMutation',[dropped.path])
        run(work,'drop-capture',[dropped]+base,'ReferenceBodyProbe',[new File(work,'dropped-capture-cases').path],measurementProps,
                'java.lang.AssertionError: actual production geometry yields numeric fit')
        if(nativeProfile.get().startsWith('staged-26.3-'))
            run(work,'body-drop-capture',[dropped,bindings]+base,'CpmBehaviorProbe',[new File(work,'omission-config.json').path,carrierFixtures.get().asFile.path,'body'],props+
                    ['-Dnclskins.aba.carrier='+new File(fixtures,'authored-jump-decor-equipment.png').path],
                    'java.lang.AssertionError: own current native numeric preparation before hidden image consumer')
        for (String mode : ['live','decline']) {
            run(work,'owner-lifecycle-'+mode,[bindings]+base,'CpmBehaviorProbe',[new File(work,'config.json').path,carrierFixtures.get().asFile.path,'body'],props+
                    ['-Dnclskins.aba.carrier='+new File(fixtures,'authored-jump-decor-equipment.png').path,'-Dnclskins.ownerLifecycle='+mode])
        }
        verifyOwnerLifecycle(work)
        run(work,'image-readiness',[bindings]+base,'CpmBehaviorProbe',
                [new File(work,'image-config.json').path,carrierFixtures.get().asFile.path,'image'],props)
        def completion=[status:'PASS',loader:loaderFamily.get(),profile:nativeProfile.get(),producer:'genuine-native-mesh',consumer:'canonical-body-material-ack']
        completion.roster=['measurement-cases','baked-menu-cold','optional-availability','warm-restart','capture-omission','body']+
                (nativeProfile.get().startsWith('staged-26.3-') ? ['body-capture-omission'] : [])+['owner-live','owner-decline']
        completion.roster += ['image-readiness']
        new File(work,'result.json').text=JsonOutput.toJson(completion)
    }

    private File resetOwnedOutput() {
        def expected=project.layout.buildDirectory.dir('verification/cpm-native-measurement').get().asFile.toPath().toAbsolutePath().normalize()
        def configured=workDirectory.get().asFile.toPath().toAbsolutePath().normalize()
        if(configured!=expected)throw new IllegalStateException('Unsafe native measurement output: outside registered layout')
        def parent=expected.parent.toFile().canonicalFile.toPath()
        def work=parent.resolve(expected.fileName)
        if(java.nio.file.Files.exists(work,java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            java.nio.file.Files.walk(work).withCloseable { paths ->
                if(paths.anyMatch { java.nio.file.Files.isSymbolicLink(it) })
                    throw new IllegalStateException('Unsafe native measurement output: symbolic link below verification parent')
            }
            if(!java.nio.file.Files.isDirectory(work,java.nio.file.LinkOption.NOFOLLOW_LINKS))
                throw new IllegalStateException('Unsafe native measurement output: not an owned directory')
        }
        project.delete(work.toFile())
        java.nio.file.Files.createDirectories(work)
        work.toFile()
    }

    private void verifyImmediate() {
        String expectedLoader=nativeProfile.get()=='immediate-26.1-neoforge'?'neoforge':'fabric'
        if (loaderFamily.get() != expectedLoader) throw new IllegalStateException('Immediate profile loader does not match selected ABI')
        File work=resetOwnedOutput()
        File probe=probeDirectory.get().asFile
        File classes=new File(work,'classes');classes.mkdirs()
        List<File> libs=resolutionClasspath.files.toList()+probeClasspath.files.toList()
        List<File> mixins=libs.findAll { it.name.contains('sponge-mixin') || it.name.startsWith('mixinextras-') }
        File sdk=libs.find { hasEntry(it,'net/minecraft/client/renderer/MultiBufferSource$BufferSource.class') }
        File cpm=libs.find { hasEntry(it,'com/tom/cpm/client/PlayerRenderStateAccess.class') }
        File ears=libs.find { hasEntry(it,'diy/y2k/five/thermite/EarsLayerRenderer.class') }
        if(sdk==null||cpm==null||ears==null)throw new IllegalStateException('Immediate selected SDK/CPM/Ears closure missing')
        File resources=new File(probe,'resources')
        List<File> host=[classes,work,resources]+mixins+probeClasspath.files+resolutionClasspath.files
        List<File> common=project.fileTree(new File(probe,'java')).matching { include '**/*.java' }.files.toList().sort { it.path }
        compile(common,classes,probeClasspath.files.toList()+resolutionClasspath.files.toList())
        List<File> typed=project.fileTree(new File(probe,'nativeMeasurement/immediate/java')).matching { include '**/*.java' }.files.toList().sort { it.path }
        typed += ['PrepareAccess.java','FitCountingObservation.java','UpstreamRendererWeave.java','CpmNativeMeasurementCore.java','FixtureCleanup.java'].collect { new File(probe,'nativeMeasurement/java/com/naocraftlab/skins/buildlogic/probe/'+it) }
        if(loaderFamily.get()=='neoforge')typed += project.fileTree(new File(probe,'nativeMeasurement/immediate/neoforge/java')).matching { include '**/*.java' }.files.toList().sort { it.path }
        compile(typed,classes,[sdk,cpm,ears,classes]+libs)
        File access=new File(work,'loader-access')
        run(work,'access',[classes,work,resources,cpm,sdk]+libs,'PrepareAccess',[cpm.path,access.path])
        config(work,'metadata-binding.json','com.naocraftlab.skins.compat.cpm.mixin',
                ['CpmModelDefinitionPartsAccessor','CpmModelSkinTextureAccessor','CpmModelTextureAccessor','CpmModelFileBackupAccessor',
                 'CpmResolvedPartsAccessor','CpmResolvedOtherPartsAccessor','CpmCopyTransformsAccessor','CpmCopyVisibilityAccessor',
                 'CpmAnimationStateAccessor','CpmVisibilityTargetAccessor','CpmLegacyAnimationsAccessor','CpmLegacyVisibilityAccessor'])
        File metadata=new File(work,'metadata')
        run(work,'metadata',host,'CpmModelMetadataTransformProbe',[metadata.path])
        List<File> composition=[metadata]+host
        File transformed=new File(work,'transformed')
        run(work,'composition',host,'CpmCompositionProbe',['present'],['-Dnclskins.cpm.transformed='+transformed.path])
        File preview=new File(work,'preview-resource')
        config(work,'preview-resource-binding.json','com.naocraftlab.skins.compat.cpm.mixin',['CpmAnimatedTextureMixin','CpmDefinitionResourceMixin'])
        run(work,'preview-resource',composition,'CpmPreviewResourceTransformProbe',[preview.path])
        File seams=new File(work,'material-host');seams.mkdirs()
        File materialClasses=new File(work,'material-classes');materialClasses.mkdirs()
        List<File> material=project.fileTree(new File(probe,'fixtures/material/java')).matching { include '**/*.java' }.files.toList().sort { it.path }
        compile(material,materialClasses,probeClasspath.files.toList()+resolutionClasspath.files.toList())
        run(work,'material-link',[classes]+probeClasspath.files,'CpmFixtureLinker',[materialClasses.path,seams.path])
        run(work,'material-host',[classes]+probeClasspath.files+resolutionClasspath.files,'CpmMaterialSetup',[seams.path])
        File raw=new File(work,'raw')
        ['net/minecraft/client/renderer/rendertype/RenderTypes.class','net/minecraft/client/renderer/rendertype/RenderType.class',
         'net/minecraft/client/resources/model/EquipmentClientInfo.class','net/minecraft/client/resources/model/EquipmentClientInfo$LayerType.class'].each { VerifyCpmNativeMeasurementTask.copyEntry(sdk,it,raw) }
        ['com/tom/cpm/client/ModelTexture.class','com/tom/cpm/client/PlayerRenderManager.class'].each { VerifyCpmNativeMeasurementTask.copyEntry(cpm,it,raw) }
        List<File> selected=[classes,work,resources,ears,cpm,sdk]+mixins+libs
        File nativeInputs=new File(work,'native-inputs')
        run(work,'native-inputs',selected,'ImmediateNativeInputs',[nativeInputs.path])
        File upstream=new File(work,'upstream')
        run(work,'upstream',selected,'ImmediateUpstreamWeave',[upstream.path,work.path],['-Dnclskins.upstream.family='+loaderFamily.get()])
        config(work,'nclskins.cpm-neutral.probe.mixins.json','com.naocraftlab.skins.compat.cpm.mixin',['AnimationEngineMixin','ModelRenderManagerMixin'])
        config(work,'ears-model-binding.json','diy.y2k.five.thermite.mixin',['AccessorModelPart'])
        List<File> prior=[upstream,nativeInputs,access,preview,transformed,metadata,seams,classes,work,resources,ears]+mixins+libs
        File fit=new File(work,'fit')
        run(work,'fit',prior,'ImmediateFitWeave',[fit.path])
        File bindings=new File(work,'bindings')
        run(work,'state-binding',[fit]+prior,'CpmNativeMeasurementBackend',[bindings.path])
        List<File> body=[fit,upstream,access,raw,bindings,nativeInputs,preview,transformed,metadata,seams,classes,work,resources,ears]+mixins+libs
        File counting=new File(work,'counting')
        run(work,'counting',body,'ImmediateFitCountingWeave',[counting.path])
        body=[counting]+body
        File mesh=new File(work,'mesh')
        run(work,'mesh',body,'ImmediateMeshObservation',[mesh.path])
        body=[mesh]+body
        if(loaderFamily.get()=='neoforge') {
            File lighting=new File(work,'neo-lighting')
            run(work,'neo-lighting',body,'NeoLightingInput',[lighting.path])
            run(work,'neo-debug-input',body,'NeoDebugInput',[lighting.path])
            body=[lighting]+body
        }
        auditImmediateBindings(work,body,cpm)
        List<String> props=['--enable-native-access=ALL-UNNAMED','-Dnclskins.upstream.family='+loaderFamily.get(),
                '-Dnclskins.cpm.nativeMeasurement=true','-Dnclskins.fullLayers.ears=true','-Dprobe.dir='+work.path,
                '-Dorg.lwjgl.system.SharedLibraryExtractPath='+new File(work,'native-libs').path,
                '-Dnclskins.aba.carrier='+new File(probe,'nativeMeasurement/fixtures/authored-jump-decor-equipment.png').path]
        for(String mode:['close','body','same','interrupt'])run(work,'cleanup-'+mode,body,'NativeCleanupProbe',[mode],props)
        if(loaderFamily.get()=='neoforge')
            for(String mode:['second-add','start','unregister','positive'])run(work,'cleanup-events-'+mode,body,'NeoEventCleanupProbe',[mode],props)
        File dropped=new File(work,'dropped-capture')
        run(work,'drop-capture-mutation',body,'ImmediateCaptureMutation',[dropped.path])
        run(work,'drop-capture',[dropped]+body,'CpmBehaviorProbe',[new File(work,'negative-config.json').path,carrierFixtures.get().asFile.path,'body'],props,
                'java.lang.AssertionError: own current native numeric preparation before hidden image consumer')
        verifyCaptureOmission(new File(work,'drop-capture.log').text)
        run(work,'body',body,'CpmBehaviorProbe',[new File(work,'config.json').path,carrierFixtures.get().asFile.path,'body'],props)
        String positive=new File(work,'body.log').text
        for (String marker : ['OWN_FIT_DURABLE firstCreates=1 persisted=PRESENT',
                'PASS same-demand S0-S1-ownACK-S0 own-fit/material/normalization/ACK settled10',
                'PASS actual extraction renderer enqueue/context/current/session/body query, source replacement/resource/world/close',
                'PASS actual extraction producer:', 'PASS focused actual primary binding and displayed-body producers',
                'PASS actual baked A/B queued material -> native GUI consumer -> fence signal -> exact-once retirement'])
            if (!positive.contains(marker)) throw new IllegalStateException('Native BODY guard roster incomplete: '+marker)
        if (!(positive =~ /NATIVE_CAPTURE_WITNESS collecting=true meshVertices=[1-9][0-9]*/).find())
            throw new IllegalStateException('Native BODY has no actual hidden mesh capture witness')
        verifySameDemandAba(work)
        for(String mode:['live','decline'])
            run(work,'owner-lifecycle-'+mode,body,'CpmBehaviorProbe',[new File(work,'config.json').path,carrierFixtures.get().asFile.path,'body'],props+['-Dnclskins.ownerLifecycle='+mode])
        verifyOwnerLifecycle(work)
        run(work,'image-readiness',body,'CpmBehaviorProbe',
                [new File(work,'image-config.json').path,carrierFixtures.get().asFile.path,'image'],props)
        new File(work,'result.json').text=JsonOutput.toJson([status:'PASS',loader:loaderFamily.get(),profile:nativeProfile.get(),producer:'genuine-native-mesh',consumer:'canonical-body-material-ack',
                roster:['body','owner-live','owner-decline','capture-omission','image-readiness']])
    }

    static void verifyCaptureOmission(String negative) {
        if (!negative.contains('java.lang.AssertionError: own current native numeric preparation before hidden image consumer')
                || !(negative =~ /NATIVE_CAPTURE_WITNESS collecting=true meshVertices=[1-9][0-9]*/).find()
                || !(negative =~ /OWN_PREPARATION_DIAGNOSTIC loads=[1-9][0-9]* creates=0 captures=[1-9][0-9]* persisted=ABSENT settled=UNAVAILABLE fit=false/).find()
                || negative.contains('LinkageError') || negative.contains('Caused by:') || negative.contains('Suppressed:'))
            throw new IllegalStateException('Omission must reach genuine mesh and an empty captured fit, not setup or storage failure')
    }

    private void auditImmediateBindings(File work,List<File> classpath,File cpm) {
        def expected=[
                'net/minecraft/client/renderer/entity/player/AvatarRenderer.class':new File(work,'upstream'),
                'net/minecraft/client/renderer/entity/LivingEntityRenderer.class':new File(work,'fit'),
                'net/minecraft/client/renderer/entity/state/AvatarRenderState.class':new File(work,'upstream'),
                'net/minecraft/client/renderer/rendertype/RenderType.class':new File(work,'mesh'),
                'com/naocraftlab/skins/compat/client/identifier/measurement/AvatarBodyCapture.class':new File(work,'counting'),
                'com/naocraftlab/skins/buildlogic/probe/CpmNativeMeasurementBackend.class':new File(work,'classes'),
                'com/tom/cpm/client/CustomPlayerModelsClient.class':cpm]
        if(loaderFamily.get()=='neoforge') {
            expected['net/neoforged/neoforge/client/model/ao/EnhancedBlockModelLighter.class']=new File(work,'neo-lighting')
            expected['net/minecraft/client/renderer/feature/ModelFeatureRenderer.class']=new File(work,'neo-lighting')
            expected['com/naocraftlab/skins/buildlogic/probe/NeoImmediateEvents.class']=new File(work,'classes')
        }
        if(classpath.any { it.path.contains('/.agents/') || it.path.contains('/immediate-slice/') || it.path.contains('/abi-extraction-remaining/') })
            throw new IllegalStateException('Foreign prepared input in public native classpath')
        def rows=[:]
        for (def entry:expected.entrySet()) {
            File found=null;byte[] contents=null
            for(File candidate:classpath) {
                if(candidate.isDirectory() && new File(candidate,entry.key).isFile()) {found=candidate;contents=new File(candidate,entry.key).bytes;break}
                if(hasEntry(candidate,entry.key)) {found=candidate;contents=new ZipFile(candidate).withCloseable { zip -> zip.getInputStream(zip.getEntry(entry.key)).bytes };break}
            }
            if(found==null || found.canonicalFile!=entry.value.canonicalFile)throw new IllegalStateException('Unexpected first native definition '+entry.key+' from '+found)
            rows[entry.key]=[root:found.canonicalPath,sha256:java.security.MessageDigest.getInstance('SHA-256').digest(contents).encodeHex().toString()]
        }
        new File(work,'first-effective-preflight.json').text=JsonOutput.toJson([loader:loaderFamily.get(),profile:nativeProfile.get(),classes:rows])
    }

    static void copyEntry(File jar,String entry,File root) {
        File target=new File(root,entry);target.parentFile.mkdirs()
        new ZipFile(jar).withCloseable { zip -> def item=zip.getEntry(entry);if(item==null)throw new IllegalStateException('Missing exact native binding '+entry);target.bytes=zip.getInputStream(item).bytes }
    }

    static void verifyScreenDemand(File work) {
        def screens=new groovy.json.JsonSlurper().parse(new File(work,'screen-demand.json'))
        if(screens.size()!=3 || screens*.surface.toSet()!=['gallery','editor','providers'].toSet()
                || screens.any { it.captures!=2 || it.firstCreates!=1
                    || it.diskLoads!=1 && (it.surface!='providers' || it.diskLoads!=2) || it.readyFrames!=100
                    || it.nativePasses<3 || it.displayKind!='CPM' })
            throw new IllegalStateException('Native screen demand/current BODY ledger incomplete')
    }

    static void verifyFallbackPolicy(File work) {
        def fallback=new groovy.json.JsonSlurper().parse(new File(work,'fallback-policy.json'))
        if(fallback.size()!=9 || fallback.collect { it.surface+':'+it.mode }.toSet().size()!=9
                || fallback.any { it.mode=='worldless-draw'
                    ? it.drawTerminals!=2 || it.materials!=2 || it.completions!=2 || it.displayKind!='ORDINARY' || it.nativeScale<=0 || it.storedFullFactor>=0.5
                    : it.mode=='missing-ears-live'
                    ? it.vertices<=0 || it.definitionBindings<=0 || it.firstCreates!=0 || it.displayKind!='ORDINARY'
                    : it.completions!=0 || it.materials!=0 || it.drawTerminals!=(it.mode=='worldless-failed'?1:0) || it.displayKind!='UNCONFIRMED' })
            throw new IllegalStateException('Native selected fallback/material/completion ledger incomplete')
    }

    static void verifyBakedConsumers(File work) {
        def read={ String name -> new groovy.json.JsonSlurper().parse(new File(work,name+'.json')) }
        def cards=read('baked-card-source-cache')
        def roles=['skin-Catalog','Import-Review','personal','resource','read-only','right-Gallery'].toSet()
        if(cards.size()!=15 || cards*.role.toSet()!=roles || cards*.source.toSet().size()<3
                || cards.any { it.materials!=1 || it.drawTerminals!=1 || it.completions!=1 || it.layoutScale!=1
                    || !(it.nativeScale instanceof Number) || it.nativeScale<=0 || it.numericLoads!=0 || it.captures!=0 || it.fullClassifiers!=0
                    || !(it.storedFullFactor instanceof Number) || it.storedFullFactor<=0 || it.storedFullFactor>=0.5
                    || it.requestScale!=(it.role=='right-Gallery'?0.88:1) || !(it.producer instanceof String) || it.producer.isEmpty() })
            throw new IllegalStateException('Baked card actual source/cache/native consumer ledger incomplete')
        def transition=read('baked-card-source-transition')
        def sameId=read('baked-card-same-id')
        if(transition.heldBeforeRegistration!=true || transition.staleARejected!=true || transition.staleBRejected!=true
                || transition.materials!=1 || transition.completions!=1 || sameId.heldBeforeMainEnqueue!=true
                || sameId.fifoPreserved!=true || sameId.exactReplacementRetained!=true || sameId.materials!=1 || sameId.completions!=1)
            throw new IllegalStateException('Baked card held producer/queued source and same-ID lifetime ledger incomplete')
        def layouts=read('baked-card-layout-continuity')
        if(layouts.size()!=2 || layouts*.owner.toSet()!=['editor','providers'].toSet()
                || layouts.any { it.height!=720 || !(it.layoutScale instanceof Number) || it.layoutScale==1 || it.userZoom<=1 })
            throw new IllegalStateException('Baked card actual Editor/Providers layout continuity incomplete')
        def menus=read('baked-menu-cold')
        if(menus.size()!=2 || menus*.role.toSet()!=['title','pause'].toSet()
                || menus.any { it.runtime!='NEW' || it.galleryEver!=false || it.drawTerminals!=1 || it.materials!=1
                    || !(it.scale instanceof Number) || Math.abs(it.scale-46.194824)>0.001 || it.fullFactor>=0.5 })
            throw new IllegalStateException('Fresh-process actual native title/pause ledger incomplete')
        String loaded=new File(work,'baked-menu-class-load.log').text
        if(loaded.contains('com.naocraftlab.skins.buildlogic.probe.ReferenceBodyProbe')
                || loaded.contains('com.naocraftlab.skins.runtime.GalleryNativePassFixture'))
            throw new IllegalStateException('Fresh menu child loaded a Gallery/native measurement producer')
        def roster=read('baked-surface-consumers')
        if(roster.size()!=8 || roster*.role.toSet()!=roles+['title','pause'].toSet())
            throw new IllegalStateException('Literal eight-owner baked roster incomplete')
    }

    static void verifyOwnerLifecycle(File work) {
        for (String mode : ['live','decline']) {
            def row=new groovy.json.JsonSlurper().parse(new File(work,'owner-lifecycle-'+mode+'.json'))
            if(row.sameOperation!=true || row.sameDemand!=true || row.numericLoads!=0 || row.numericCreates!=0
                    || row.captures!=0 || row.neutralDecodes!=0 || !(row.liveVertices instanceof Number) || row.liveVertices<=0)
                throw new IllegalStateException('Native owner lifecycle common ledger incomplete: '+mode)
            if(mode=='live' ? row.coherentSession!=true || row.reusedLiveSlot!=true || row.lateSuccessorRejected!=true
                    || row.compositionStable!=true || row.stableFrames!=10 || row.displayKind!='CPM'
                    || !(row.normalization instanceof Number) || row.normalization<=0 || row.normalization>=0.5
                    || !(row.zoom instanceof Number) || row.zoom<=0
                    : row.sameOwner!=true || row.stableFrames!=100 || row.nativePrepares!=0 || row.nativeAllocations!=0
                    || row.nativeCompletes!=0 || row.terminal!='decline' || row.bodyStatus!='READY'
                    || row.fallbackPasses!=3 || row.displayKind!='ORDINARY')
                throw new IllegalStateException('Native owner lifecycle disposition ledger incomplete: '+mode)
        }
    }

    static void verifySameDemandAba(File work) {
        def aba=new groovy.json.JsonSlurper().parse(new File(work,'same-demand-aba.json'))
        if(aba.sameOperation!=true || aba.originalSourceReturned!=true || aba.oldSlotRetired!=true || aba.freshSlot!=true
                || aba.ownCurrentFit!=true || aba.settledFrames!=10 || aba.displayKind!='CPM'
                || !(aba.normalization instanceof Number) || aba.normalization<=0 || aba.normalization>=0.5
                || !(aba.liveVertices instanceof Number) || aba.liveVertices<=0
                || aba.numericLoads!=0 || aba.numericCreates!=0 || aba.captures!=0 || aba.neutralDecodes!=0)
            throw new IllegalStateException('Native same-demand ABA own-fit/material/normalization/ACK ledger incomplete')
    }
    static boolean hasEntry(File file,String entry) {
        file.isFile()&&file.name.endsWith('.jar')&&new ZipFile(file).withCloseable { it.getEntry(entry)!=null }
    }
    private static void config(File work,String name,String namespace,List<String> mixins) {
        new File(work,name).text=JsonOutput.toJson([required:true,minVersion:'0.8',package:namespace,compatibilityLevel:'JAVA_17',client:mixins,injectors:[defaultRequire:1]])
    }
    private static void compile(List<File> sources,File output,List<File> cp) {
        int code=ToolProvider.systemJavaCompiler.run(null,System.out,System.err,
                (['-proc:none','--release','17','-cp',cp*.path.join(File.pathSeparator),'-d',output.path]+sources*.path) as String[])
        if(code!=0)throw new IllegalStateException('Native measurement fixture compilation failed')
    }
    private void run(File work,String label,List<File> cp,String main,List<String> args,List<String> props=[],String expectedFailure=null) {
        File temp=new File(work,'tmp-'+label);if(!temp.mkdirs())throw new IllegalStateException('Native measurement temp is not fresh')
        List<String> argv=[new File(System.getProperty('java.home'),'bin/java').path,'-Djava.awt.headless=true','-Dmax.bg.threads=1','-Djava.io.tmpdir='+temp.path]+props+
                ['-cp',cp*.path.join(File.pathSeparator),main.contains('.')?main:'com.naocraftlab.skins.buildlogic.probe.'+main]+args
        new File(work,label+'-command.json').text=JsonOutput.toJson(argv)
        File log=new File(work,label+'.log');Process child=startCpmProcess(argv,log)
        int exit=CompositionProcess.await(child,120_000L,5_000L)
        IllegalStateException failure
        if(expectedFailure!=null) {
            if(exit!=1||!log.text.contains(expectedFailure)||log.text.contains('Suppressed:'))
                failure=new IllegalStateException('Native measurement sensitivity failed: '+label)
        } else if(exit!=0 || log.text.contains('Caught an error while rendering')) {
            failure=new IllegalStateException('Native measurement stage failed: '+label)
        }
        try {
            new File(work,label+'-exit.json').text=JsonOutput.toJson([exit:exit,completed:true])
            if(failure!=null)logger.lifecycle(log.text)
        } catch(Throwable reporting) {
            if(failure==null)throw reporting
            failure.addSuppressed(reporting)
        }
        if(failure!=null)throw failure
        logger.lifecycle('Native measurement '+label+(expectedFailure!=null?' expected assertion passed':' passed'))
    }
    protected Process startCpmProcess(List<String> argv,File log) {
        new ProcessBuilder(argv).redirectErrorStream(true).redirectOutput(log).start()
    }
}

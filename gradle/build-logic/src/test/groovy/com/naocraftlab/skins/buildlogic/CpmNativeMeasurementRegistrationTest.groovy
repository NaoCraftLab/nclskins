package com.naocraftlab.skins.buildlogic

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import static org.junit.jupiter.api.Assertions.*

final class CpmNativeMeasurementRegistrationTest {
    @TempDir Path directory
    @Test void submissionAdmissionAndCompletionRequireMatchedExecutedProfile() {
        assertEquals('fabric',VerifyCpmIntegrationTask.selectSubmissionLoader(true,true,false,false))
        assertEquals('neoforge',VerifyCpmIntegrationTask.selectSubmissionLoader(true,false,true,true))
        assertNull(VerifyCpmIntegrationTask.selectSubmissionLoader(false,false,false,false))
        for(def flags:[[true,true,false,true],[true,false,true,false],[true,false,false,true]])
            assertThrows(IllegalStateException) { VerifyCpmIntegrationTask.selectSubmissionLoader(*flags) }
        for(String loader:['fabric','neoforge']) {
            def valid=[status:'PASS',profile:'submission-1.21.11',loader:loader,producer:'genuine-native-mesh',consumer:'own-cpm-hidden-visible-common-ack',
                roster:['capture-omission','exceptional-cleanup','reference-body-numeric','hidden-visible-common-ack']]
            VerifyCpmIntegrationTask.validateSubmissionCompletion(valid,loader)
            for(def invalid:[valid+[loader:loader=='fabric'?'neoforge':'fabric'],valid+[profile:'old'],valid+[status:'FAIL'],
                    valid+[producer:'synthetic'],valid+[consumer:'full-retained-successor'],valid+[roster:valid.roster+['retained-ordinary-successor']],
                    valid+[roster:valid.roster.dropRight(1)],valid+[roster:valid.roster.reverse()]])
                assertThrows(IllegalStateException) { VerifyCpmIntegrationTask.validateSubmissionCompletion(invalid,loader) }
            for(String key:valid.keySet()) {
                def absent=new LinkedHashMap(valid);absent.remove(key)
                assertThrows(IllegalStateException) { VerifyCpmIntegrationTask.validateSubmissionCompletion(absent,loader) }
            }
        }
    }
    @Test void captureOmissionRequiresNumericBoundaryAndGenuineAbsentFit() {
        String assertion='java.lang.AssertionError: own current native numeric preparation before hidden image consumer'
        String witness='NATIVE_CAPTURE_WITNESS collecting=true meshVertices=288'
        String diagnostic='OWN_PREPARATION_DIAGNOSTIC loads=1 creates=0 captures=1 persisted=ABSENT settled=UNAVAILABLE fit=false'
        String valid=[witness,diagnostic,assertion].join('\n')
        VerifyCpmNativeMeasurementTask.verifyCaptureOmission(valid)
        for(String mutation:[valid.replace(assertion,'java.lang.AssertionError: unrelated'),
                valid.replace(witness,''),valid.replace('meshVertices=288','meshVertices=0'),
                valid.replace(diagnostic,''),valid.replace('captures=1','captures=0'),
                valid.replace('creates=0','creates=1'),valid.replace('persisted=ABSENT','persisted=PRESENT'),
                valid.replace('settled=UNAVAILABLE','settled=pending'),valid.replace('fit=false','fit=true'),
                valid+'\nCaused by: failure',valid+'\nSuppressed: teardown',valid+'\njava.lang.LinkageError'])
            assertThrows(IllegalStateException) { VerifyCpmNativeMeasurementTask.verifyCaptureOmission(mutation) }
    }

    @Test void actualScreenLedgerAcceptsBoundedProvidersOwnerRefinement() {
        def rows=['gallery','editor','providers'].collect { surface ->
            [surface:surface,captures:2,firstCreates:1,diskLoads:surface=='providers'?2:1,
             readyFrames:100,nativePasses:5,displayKind:'CPM']
        }
        File ledger=directory.resolve('screen-demand.json').toFile()
        ledger.text=groovy.json.JsonOutput.toJson(rows)
        VerifyCpmNativeMeasurementTask.verifyScreenDemand(directory.toFile())
        rows.find { it.surface=='providers' }.diskLoads=1
        ledger.text=groovy.json.JsonOutput.toJson(rows)
        VerifyCpmNativeMeasurementTask.verifyScreenDemand(directory.toFile())
    }

    @Test void actualScreenLedgerRejectsAmbientAndForeignScreenLoads() {
        [0,3].each { loads ->
            def rows=['gallery','editor','providers'].collect { surface ->
                [surface:surface,captures:2,firstCreates:1,diskLoads:surface=='providers'?loads:1,
                 readyFrames:100,nativePasses:5,displayKind:'CPM']
            }
            directory.resolve('screen-demand.json').toFile().text=groovy.json.JsonOutput.toJson(rows)
            assertThrows(IllegalStateException) { VerifyCpmNativeMeasurementTask.verifyScreenDemand(directory.toFile()) }
        }
        ['gallery','editor'].each { changed ->
            def rows=['gallery','editor','providers'].collect { surface ->
                [surface:surface,captures:2,firstCreates:1,diskLoads:surface==changed?2:1,
                 readyFrames:100,nativePasses:5,displayKind:'CPM']
            }
            directory.resolve('screen-demand.json').toFile().text=groovy.json.JsonOutput.toJson(rows)
            assertThrows(IllegalStateException) { VerifyCpmNativeMeasurementTask.verifyScreenDemand(directory.toFile()) }
        }
    }

    @Test void actualOwnerLifecycleValidatorRequiresBothNonvacuousDispositions() {
        File work=directory.resolve('owners').toFile();work.mkdirs()
        assertThrows(Exception) { VerifyCpmNativeMeasurementTask.verifyOwnerLifecycle(work) }
        def shared=[sameOperation:true,sameDemand:true,numericLoads:0,numericCreates:0,captures:0,neutralDecodes:0,liveVertices:2160]
        def rows=[live:shared+[coherentSession:true,reusedLiveSlot:true,lateSuccessorRejected:true,compositionStable:true,
                             stableFrames:10,displayKind:'CPM',normalization:0.45486112396412953,zoom:0.7344],
                  decline:shared+[sameOwner:true,stableFrames:100,nativePrepares:0,nativeAllocations:0,nativeCompletes:0,
                                  terminal:'decline',bodyStatus:'READY',fallbackPasses:3,displayKind:'ORDINARY']]
        rows.each { mode,row -> new File(work,'owner-lifecycle-'+mode+'.json').text=groovy.json.JsonOutput.toJson(row) }
        VerifyCpmNativeMeasurementTask.verifyOwnerLifecycle(work)
        rows.each { mode,row ->
            File ledger=new File(work,'owner-lifecycle-'+mode+'.json')
            ledger.delete()
            assertThrows(Exception) { VerifyCpmNativeMeasurementTask.verifyOwnerLifecycle(work) }
            row.keySet().each { key ->
                def missing=new LinkedHashMap(row);missing.remove(key)
                ledger.text=groovy.json.JsonOutput.toJson(missing)
                assertThrows(IllegalStateException) { VerifyCpmNativeMeasurementTask.verifyOwnerLifecycle(work) }
                def wrong=new LinkedHashMap(row)
                wrong[key]=row[key] instanceof Boolean ? false : row[key] instanceof Number ? (row[key]==0?1:0) : 'invalid'
                ledger.text=groovy.json.JsonOutput.toJson(wrong)
                assertThrows(IllegalStateException) { VerifyCpmNativeMeasurementTask.verifyOwnerLifecycle(work) }
            }
            ledger.text=groovy.json.JsonOutput.toJson(row)
        }
        VerifyCpmNativeMeasurementTask.verifyOwnerLifecycle(work)
    }

    @Test void actualAbaLedgerValidatorRejectsMissingAndVacuousProof() {
        File work=directory.resolve('aba').toFile();work.mkdirs()
        assertThrows(Exception) { VerifyCpmNativeMeasurementTask.verifySameDemandAba(work) }
        def valid=[sameOperation:true,originalSourceReturned:true,oldSlotRetired:true,freshSlot:true,ownCurrentFit:true,
                   settledFrames:10,displayKind:'CPM',normalization:0.45486112396412953,
                   numericLoads:0,numericCreates:0,captures:0,neutralDecodes:0,liveVertices:1920]
        File ledger=new File(work,'same-demand-aba.json')
        ledger.text=groovy.json.JsonOutput.toJson(valid)
        VerifyCpmNativeMeasurementTask.verifySameDemandAba(work)
        valid.keySet().each { key ->
            def missing=new LinkedHashMap(valid);missing.remove(key)
            ledger.text=groovy.json.JsonOutput.toJson(missing)
            assertThrows(IllegalStateException) { VerifyCpmNativeMeasurementTask.verifySameDemandAba(work) }
            def wrong=new LinkedHashMap(valid)
            wrong[key]=key=='liveVertices' ? 0 : valid[key] instanceof Boolean ? false : valid[key] instanceof Number ? 1 : 'ORDINARY'
            ledger.text=groovy.json.JsonOutput.toJson(wrong)
            assertThrows(IllegalStateException) { VerifyCpmNativeMeasurementTask.verifySameDemandAba(work) }
        }
    }

    @Test void actualCatalogRootsAndCompileDependenciesKeepBothPlayerModelFamiliesOnEars() {
        File repository = new File('../..').canonicalFile
        String conventions = new File(repository, 'gradle/target-conventions.gradle').text
        String selection = conventions.substring(conventions.indexOf('def rootBundleIds ='), conventions.indexOf('nclskinsNativeModelTests.configure('))
        String registration = conventions.substring(conventions.indexOf('def nativeMeasurementTask ='), conventions.indexOf('def isolationTask ='))
        for (String family : ['fabric', 'neoforge']) for (String version : ['26.1', '26.2']) {
            File root = directory.resolve('roots-' + family + '-' + version).toFile(); root.mkdirs()
            def project = ProjectBuilder.builder().withProjectDir(root).build()
            project.pluginManager.apply('java')
            project.pluginManager.apply(NclSkinsBuildToolsPlugin)
            project.sourceSets.create('client')
            project.ext.repositoryRoot = project.layout.projectDirectory.dir(repository.absolutePath)
            project.ext.catalog = CatalogTools.loadCatalog(repository)
            project.ext.spec = project.ext.catalog.targets.find { it.id == family + '-' + version }
            File script = new File(root, 'selection.gradle')
            script.text = selection + registration
            project.apply(from: script)
            def selected = project.sourceSets.getByName(project.ext.spec.sourceLayout == 'fabricSplit' ? 'client' : 'main')
            def roots = selected.java.srcDirs
            File concrete = new File(repository, 'compat/capabilities/preview/avatar-pip-ears-measurement/src/main/java')
            File fallback = new File(repository, 'compat/capabilities/preview/avatar-pip-extraction-player-model/src/main/java')
            assertTrue(roots.contains(concrete), family + '-' + version + ' concrete Ears selection')
            assertEquals(version == '26.1', roots.contains(fallback), family + '-' + version + ' player-model capture selection')
            String facade = 'com/naocraftlab/skins/compat/client/identifier/measurement/AvatarEarsFitMode.java'
            assertEquals(1, roots.count { new File(it, facade).isFile() }, 'exactly one erased Ears binding')
            def dependencies = project.configurations.getByName(selected.compileOnlyConfigurationName).dependencies
            assertEquals(1, dependencies.count { it.group == 'maven.modrinth' && it.name == 'mfzaZK3Z' })
        }
    }

    @Test void allCatalogExtractionTargetsRequireNativeBodyCompletion() {
        File repository = new File('../..').canonicalFile
        def catalog = CatalogTools.loadCatalog(repository)
        String conventions = new File(repository, 'gradle/target-conventions.gradle').text
        String registration = conventions.substring(conventions.indexOf('def nativeMeasurementTask ='), conventions.indexOf('def isolationTask ='))
        def targets = catalog.targets.findAll { it.minecraft.version in ['26.1', '26.2', '26.3'] && it.loader.id in ['fabric', 'neoforge'] }
        assertEquals(6, targets.size())
        def missing = []
        targets.each { target ->
            File root = directory.resolve('required-' + target.id).toFile(); root.mkdirs()
            def project = ProjectBuilder.builder().withProjectDir(root).build()
            project.pluginManager.apply('java')
            project.pluginManager.apply(NclSkinsBuildToolsPlugin)
            project.ext.repositoryRoot = project.layout.projectDirectory.dir(repository.absolutePath)
            project.ext.abiSourceSet = project.sourceSets.main
            project.ext.resolvedBundleIds = ['avatar-pip-ears-measurement']
            project.ext.spec = target
            new File(root, 'registration.gradle').with { text = registration; project.apply(from: it) }
            def integration = project.tasks.getByName('verifyCpmIntegration')
            def nativeBody = project.tasks.findByName('verifyCpmNativeMeasurement')
            if (nativeBody == null) {
                missing.add(target.id)
            } else {
                assertInstanceOf(VerifyCpmNativeMeasurementTask, nativeBody)
                assertTrue(integration.taskDependencies.getDependencies(integration).contains(nativeBody), target.id)
                assertEquals(new File(nativeBody.workDirectory.get().asFile, 'result.json'), integration.nativeBodyProof.get().asFile)
                assertEquals(target.loader.id, nativeBody.loaderFamily.get())
                assertEquals(target.loader.id, integration.nativeBodyLoader.get())
                assertEquals(nativeBody.nativeProfile.get(),integration.nativeBodyProfile.get())
                def profile=nativeBody.nativeProfile.get()
                assertEquals(target.minecraft.version=='26.1' ? (target.loader.id=='fabric'?'immediate-26.1':'immediate-26.1-neoforge') : target.minecraft.version=='26.2'?'staged':'staged-26.3-'+target.loader.id,profile)
                File fixture=nativeBody.probeDirectory.get().asFile
                assertTrue(new File(fixture,'java/com/naocraftlab/skins/buildlogic/probe/CpmExtractionBodyProbe.java').isFile())
                assertTrue(new File(fixture,'nativeMeasurement/java/com/naocraftlab/skins/buildlogic/probe/CpmNativeMeasurementCore.java').isFile())
                if(target.minecraft.version=='26.3') {
                    assertTrue(new File(fixture,'nativeMeasurement/staged263/java/com/naocraftlab/skins/buildlogic/probe/NativeFixtureAbi.java').isFile())
                    assertTrue(new File(fixture,'nativeMeasurement/staged263/java/com/naocraftlab/skins/buildlogic/probe/PipCpuBackend.java').isFile())
                }
                def lwjgl = project.configurations.getByName('cpmNativeMeasurementRuntime').dependencies.findAll { it.group == 'org.lwjgl' }
                assertEquals(3, lwjgl.size(), target.id)
                assertTrue(lwjgl.every { it.version == (target.minecraft.version == '26.3' ? '3.4.3' : '3.4.1') }, target.id + ' native ABI must match selected SDK')
            }
        }
        assertEquals([], missing, 'all selected extraction targets require the actual native BODY task')
    }

    @Test void selectedCompletionRejectsForeignAndIncompleteRosters() {
        def immediate=['body','owner-live','owner-decline','capture-omission','image-readiness']
        def staged=['measurement-cases','baked-menu-cold','optional-availability','warm-restart','capture-omission','body','owner-live','owner-decline']
        for(String family:['fabric','neoforge']) for(String version:['26.1','26.2','26.3']) {
            String profile=version=='26.1' ? (family=='fabric'?'immediate-26.1':'immediate-26.1-neoforge') : version=='26.2'?'staged':'staged-26.3-'+family
            def roster=version=='26.1'?immediate:version=='26.2'?staged+['image-readiness']:staged[0..5]+['body-capture-omission']+staged[6..7]+['image-readiness']
            def valid=[status:'PASS',profile:profile,loader:family,producer:'genuine-native-mesh',consumer:'canonical-body-material-ack',roster:roster]
            VerifyCpmIntegrationTask.validateNativeCompletion(valid,profile,family)
            if (version=='26.3')
                assertThrows(IllegalStateException) { VerifyCpmIntegrationTask.validateNativeCompletion(valid+[roster:roster.dropRight(1)],profile,family) }
            if (version=='26.2')
                assertThrows(IllegalStateException) { VerifyCpmIntegrationTask.validateNativeCompletion(valid+[roster:staged],profile,family) }
            if (version=='26.1')
                assertThrows(IllegalStateException) { VerifyCpmIntegrationTask.validateNativeCompletion(valid+[roster:immediate.dropRight(1)],profile,family) }
            for(def mutation:[valid+[loader:(family=='fabric'?'neoforge':'fabric')],valid+[profile:'foreign'],valid+[status:'FAIL'],valid+[producer:'fake'],valid+[consumer:'protocol'],valid+[roster:roster.drop(1)],valid+[roster:roster+[roster[0]]],valid+[roster:roster.reverse()]])
                assertThrows(IllegalStateException) { VerifyCpmIntegrationTask.validateNativeCompletion(mutation,profile,family) }
            for(String key:valid.keySet()) {
                def missing=new LinkedHashMap(valid);missing.remove(key)
                assertThrows(IllegalStateException) { VerifyCpmIntegrationTask.validateNativeCompletion(missing,profile,family) }
            }
        }
        assertThrows(IllegalStateException) { VerifyCpmIntegrationTask.validateNativeCompletion([:], 'foreign','fabric') }
    }

    @Test void incompleteOrForeignNativeCompletionCannotReplaceBody() {
        File root=directory.resolve('proof-guards').toFile();root.mkdirs()
        def project=ProjectBuilder.builder().withProjectDir(root).build()
        def task=project.tasks.create('integrationProofGuard',VerifyCpmIntegrationTask)
        File proof=new File(root,'result.json')
        task.nativeBodyProof.set(proof)
        def valid=[status:'PASS',profile:'immediate-26.1',loader:'fabric',producer:'genuine-native-mesh',
                   consumer:'canonical-body-material-ack',roster:['body','owner-live','owner-decline','capture-omission','image-readiness']]
        proof.text=groovy.json.JsonOutput.toJson(valid)
        assertThrows(IllegalStateException) { task.verify() }
        task.nativeBodyProfile.set('immediate-26.1')
        task.nativeBodyLoader.set('fabric')
        for (def mutation : [valid+[profile:'staged'],valid+[loader:'neoforge'],valid+[roster:['body']],valid+[status:'FAIL'],valid+[producer:'fake']]) {
            proof.text=groovy.json.JsonOutput.toJson(mutation)
            assertThrows(IllegalStateException) { task.verify() }
        }
        task.nativeBodyProfile.set('immediate-26.1-neoforge')
        task.nativeBodyLoader.set('neoforge')
        def neo=valid+[profile:'immediate-26.1-neoforge',loader:'neoforge']
        for (def mutation : [neo+[profile:'immediate-26.1'],neo+[loader:'fabric'],neo+[roster:['body']],neo+[status:'FAIL'],neo+[producer:'fake']]) {
            proof.text=groovy.json.JsonOutput.toJson(mutation)
            assertThrows(IllegalStateException) { task.verify() }
        }
    }

    @Test void actualRegistrationMakesNativeBodyMandatoryOnlyForSelectedSdk() {
        File repository=new File('../..').canonicalFile
        def catalog=CatalogTools.loadCatalog(repository)
        String conventions=new File(repository,'gradle/target-conventions.gradle').text
        String registration=conventions.substring(conventions.indexOf('def nativeMeasurementTask ='),conventions.indexOf('def isolationTask ='))
        for (String family : ['fabric','neoforge']) for (String version : ['26.1','26.2']) {
            File root=directory.resolve(family+'-'+version).toFile().canonicalFile;root.mkdirs()
            def project=ProjectBuilder.builder().withProjectDir(root).build()
            project.pluginManager.apply('java')
            project.pluginManager.apply(NclSkinsBuildToolsPlugin)
            project.ext.spec=catalog.targets.find { it.id==family+'-'+version }
            File script=new File(root,'registration.gradle')
            script.text="""
ext.repositoryRoot = layout.projectDirectory
ext.abiSourceSet = sourceSets.main
ext.resolvedBundleIds = ['avatar-pip-ears-measurement']
"""+registration
            project.apply(from:script)
            def integration=project.tasks.getByName('verifyCpmIntegration')
            assertFalse(integration.stringUserProfileId.get())
            if(version in ['26.1','26.2']) {
                def nativeBody=project.tasks.getByName('verifyCpmNativeMeasurement')
                assertEquals(version=='26.1' ? (family=='neoforge'?'immediate-26.1-neoforge':'immediate-26.1') : 'staged', nativeBody.nativeProfile.get())
                assertInstanceOf(VerifyCpmNativeMeasurementTask,nativeBody)
                assertTrue(integration.taskDependencies.getDependencies(integration).contains(nativeBody))
                assertEquals(family,nativeBody.loaderFamily.get())
                assertEquals(new File(nativeBody.workDirectory.get().asFile,'result.json'),integration.nativeBodyProof.get().asFile)
                assertEquals(nativeBody.nativeProfile.get(),integration.nativeBodyProfile.get())
                assertEquals(new File(root,'gradle/build-logic/src/cpmComposition'),nativeBody.probeDirectory.get().asFile)
                assertEquals('Tdfeeflh',project.configurations.cpmNativeMeasurementRuntime.dependencies.find { it.group=='maven.modrinth' }.version)
                assertEquals(4,project.configurations.cpmNativeMeasurementRuntime.dependencies.size())
            } else {
                assertNull(project.tasks.findByName('verifyCpmNativeMeasurement'))
                assertFalse(integration.nativeBodyProof.isPresent())
            }
        }
    }
}

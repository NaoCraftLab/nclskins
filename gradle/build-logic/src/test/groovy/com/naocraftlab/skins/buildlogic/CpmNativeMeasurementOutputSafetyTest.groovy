package com.naocraftlab.skins.buildlogic

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import static org.junit.jupiter.api.Assertions.*

final class CpmNativeMeasurementOutputSafetyTest {
    @TempDir Path directory

    private def task(String name, String profile) {
        File root=directory.resolve(name).toFile();root.mkdirs()
        def project=ProjectBuilder.builder().withProjectDir(root).build()
        def task=project.tasks.create('nativeMeasurement',VerifyCpmNativeMeasurementTask)
        task.nativeProfile.set(profile)
        task.loaderFamily.set('fabric')
        task.workDirectory.set(project.layout.buildDirectory.dir('verification/cpm-native-measurement'))
        task.probeDirectory.set(new File(root,'unused-fixtures'))
        task
    }

    @Test void actualActionsRejectForeignLeafAndBrokenLinksBeforeReset() {
        for(String profile:['staged','staged-26.3-fabric','immediate-26.1']) for(boolean broken:[false,true]) {
            def task=task('leaf-'+profile+'-'+broken,profile)
            Path foreign=directory.resolve('foreign-'+profile+'-'+broken);Files.createDirectories(foreign)
            Path sentinel=foreign.resolve('sentinel');Files.writeString(sentinel,'preserve')
            Path work=task.workDirectory.get().asFile.toPath();Files.createDirectories(work.parent)
            Path target=broken?foreign.resolve('absent'):foreign
            Files.createSymbolicLink(work,target)
            def failure=assertThrows(IllegalStateException) { task.verify() }
            assertTrue(failure.message.contains('Unsafe native measurement output'),failure.message)
            assertEquals('preserve',Files.readString(sentinel))
            assertTrue(Files.isSymbolicLink(work))
            assertEquals(target,Files.readSymbolicLink(work))
        }
    }

    @Test void actualActionsRejectOutsideOverrideAndDescendantAliases() {
        for(String profile:['staged','immediate-26.1']) for(String mode:['outside','intermediate','descendant']) {
            def task=task(mode+'-'+profile,profile)
            Path foreign=directory.resolve('preserved-'+mode+'-'+profile);Files.createDirectories(foreign)
            Path sentinel=foreign.resolve('sentinel');Files.writeString(sentinel,'preserve')
            Path work=task.workDirectory.get().asFile.toPath();Files.createDirectories(work.parent)
            Path link=null
            if(mode=='outside') task.workDirectory.set(foreign.toFile())
            else if(mode=='intermediate') {
                link=work.parent.resolve('alias');Files.createSymbolicLink(link,foreign)
                task.workDirectory.set(link.resolve('cpm-native-measurement').toFile())
            } else {
                Files.createDirectories(work);link=work.resolve('classes');Files.createSymbolicLink(link,foreign)
            }
            def failure=assertThrows(IllegalStateException) { task.verify() }
            assertTrue(failure.message.contains('Unsafe native measurement output'),failure.message)
            assertEquals('preserve',Files.readString(sentinel))
            if(link!=null)assertTrue(Files.isSymbolicLink(link))
        }
    }

    @Test void actualActionsResetOwnedOutputsWithRelocatedBuildAndVerificationParent() {
        for(String profile:['staged','staged-26.3-fabric','immediate-26.1']) for(boolean linked:[false,true]) {
            def task=task('owned-'+profile+'-'+linked,profile)
            Path relocated=directory.resolve('relocated-'+profile+'-'+linked)
            task.project.layout.buildDirectory.set(relocated.toFile())
            Path verification=relocated.resolve('verification');Files.createDirectories(relocated)
            if(linked) {
                Path physical=directory.resolve('physical-'+profile);Files.createDirectories(physical)
                Files.createSymbolicLink(verification,physical)
            } else Files.createDirectories(verification)
            Path work=task.workDirectory.get().asFile.toPath();Files.createDirectories(work)
            Path stale=work.resolve('stale');Files.writeString(stale,'owned')
            def failure=assertThrows(IllegalStateException) { task.verify() }
            assertEquals(profile=='immediate-26.1'?'Immediate selected SDK/CPM/Ears closure missing':'Pinned native CPM/Ears dependency closure missing',failure.message)
            assertFalse(Files.exists(stale))
            assertTrue(Files.isDirectory(work.resolve('classes')))
            assertEquals(linked,Files.isSymbolicLink(verification))
        }
    }
}

package com.naocraftlab.skins.buildlogic

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import static org.junit.jupiter.api.Assertions.*

class NativeModelTestsTest {
    @TempDir
    File root

    @Test
    void selectsOnlyTestsBesideResolvedNativeSources() {
        def project = ProjectBuilder.builder().withProjectDir(root).build()
        project.pluginManager.apply('java')
        File common = new File(root, 'client-runtime/src/main/java')
        File selected = new File(root, 'compat/selected/src/main/java')
        File other = new File(root, 'compat/other/src/main/java')
        [common, selected, other].each {
            it.mkdirs()
            new File(it.parentFile.parentFile, 'test/java').mkdirs()
        }
        NativeModelTests.configure(project, root, [common, selected, selected], project.sourceSets.main)
        Set<File> paths = project.sourceSets.test.java.srcDirs
        assertTrue(paths.contains(new File(root, 'compat/selected/src/test/java')))
        assertFalse(paths.contains(new File(root, 'compat/other/src/test/java')))
        assertFalse(paths.contains(new File(root, 'client-runtime/src/test/java')))
        assertTrue(paths.contains(new File(root, 'client-contract/src/testFixtures/java')))
    }

    @Test
    void leavesTargetsWithoutNativeTestsUnchanged() {
        def project = ProjectBuilder.builder().withProjectDir(root).build()
        project.pluginManager.apply('java')
        Set<File> before = project.sourceSets.test.java.srcDirs
        NativeModelTests.configure(project, root, [], project.sourceSets.main)
        assertEquals(before, project.sourceSets.test.java.srcDirs)
        assertTrue(project.configurations.testImplementation.dependencies.empty)
    }
}

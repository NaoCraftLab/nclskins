package com.naocraftlab.skins.buildlogic

import org.gradle.api.Project
import org.gradle.api.tasks.SourceSet

final class NativeModelTests {
    static void configure(Project project, File root, List<File> sources, SourceSet client) {
        List<File> tests = sources
                .findAll { it.toPath().startsWith(new File(root, 'compat').toPath()) }
                .collect { new File(it.parentFile.parentFile, 'test/java') }
                .findAll { it.isDirectory() }.unique()
        if (tests.isEmpty()) return
        def sourceSets = project.extensions.getByName('sourceSets')
        sourceSets.test.java.srcDirs(tests)
        sourceSets.test.java.srcDir(new File(root, 'client-contract/src/testFixtures/java'))
        sourceSets.test.compileClasspath += client.output + client.compileClasspath
        sourceSets.test.runtimeClasspath += client.output + client.runtimeClasspath
        project.dependencies.add('testImplementation', 'org.junit.jupiter:junit-jupiter:5.11.4')
        project.dependencies.add('testRuntimeOnly', 'org.junit.platform:junit-platform-launcher:1.11.4')
    }
}

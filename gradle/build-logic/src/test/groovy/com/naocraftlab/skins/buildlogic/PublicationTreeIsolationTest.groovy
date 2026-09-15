package com.naocraftlab.skins.buildlogic

import org.junit.jupiter.api.Test

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

import static org.junit.jupiter.api.Assertions.*

final class PublicationTreeIsolationTest {
    @Test
    void neverTraversesClassesBeingReplacedByConcurrentCompilation() {
        File root = Files.createTempDirectory('publication-tree-race-').toFile()
        AtomicBoolean running = new AtomicBoolean(true)
        Thread compiler
        try {
            assertEquals(0, new ProcessBuilder('git', 'init', '-q').directory(root).start().waitFor())
            Files.writeString(new File(root, '.gitignore').toPath(), 'build/\n.gradle/\n')
            File generated = new File(root, 'gradle/build-logic/build/classes/groovy/test')
            generated.mkdirs()
            File source = new File(root, 'gradle/build-logic/src/main/Test.groovy')
            source.parentFile.mkdirs()
            Files.writeString(source.toPath(), 'class Test {}\n')
            compiler = new Thread({
                while (running.get()) {
                    def directory = Files.createTempDirectory(generated.toPath(), 'worker-')
                    def file = directory.resolve('Test.class')
                    Files.write(file, new byte[0])
                    Files.delete(file)
                    Files.delete(directory)
                }
            })
            compiler.start()
            30.times {
                def files = PublicationTreeVerifier.publicFiles(root.toPath())
                assertTrue(files.contains(source.toPath()))
                assertFalse(files.any { it.startsWith(new File(root, 'gradle/build-logic/build').toPath()) })
            }
        } finally {
            running.set(false)
            compiler?.join(5000)
            root.deleteDir()
        }
    }
}

package com.naocraftlab.skins.buildlogic

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.gradle.testfixtures.ProjectBuilder

import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

class SourceOwnershipVerifierTest {
    @TempDir Path directory

    @Test
    void sourceScansStayInsideTheSelectedCheckout() {
        List<String> paths = [
                'core/src/main/java/Own.java',
                'core/src/test/java/OwnTest.java',
                '.worktrees/other/core/src/main/java/Other.java',
                '.worktrees/other/.agents/research/src/main/java/Foreign.java',
                '.agents/research/src/main/java/Foreign.java',
                'core/build/generated/src/main/java/Generated.java'
        ]
        paths.each { String path ->
            File file = directory.resolve(path).toFile()
            file.parentFile.mkdirs()
            file.text = 'package com.naocraftlab.skins.fixture; class Own {}'
        }
        def project = ProjectBuilder.builder().withProjectDir(directory.toFile()).build()
        String rootBuild = new File('../../build.gradle').getText('UTF-8')
        ['sources', 'productionSources'].each { String variable ->
            int start = rootBuild.indexOf("def ${variable} = fileTree(rootDir) {")
            int end = rootBuild.indexOf('\n    }', start)
            assertTrue(start >= 0 && end > start)
            def binding = new Binding([
                    rootDir: directory.toFile(),
                    fileTree: { Object root, Closure options -> project.fileTree(root, options) }
            ])
            def selected = new GroovyShell(binding).evaluate(rootBuild.substring(start, end + 6)
                    + "\n${variable}.files")
                    .collect { File file -> directory.relativize(file.toPath()).toString() }.sort()
            assertEquals(variable == 'sources' ? paths.take(2).sort() : [paths.first()], selected)
        }
    }

    @Test
    void rejectsForeignDeclarationsEvenInMisnamedTestFiles() {
        ['net.minecraft.client', 'com.mojang.blaze3d.platform', 'com.tom.cpm.client',
         'org.bukkit.craftbukkit', 'com.naocraftlab.skinsother'].each { String name ->
            assertEquals(1, SourceOwnershipVerifier.verify('src/test/java/OwnFixture.java',
                    "/* fixture */\n package ${name}; class OwnFixture {}").size())
        }
    }

    @Test
    void permitsOwnedSourcesWithDependencyImportsAndNames() {
        assertTrue(SourceOwnershipVerifier.verify('src/main/java/Adapter.java', '''
            package com.naocraftlab.skins.compat;
            import net.minecraft.client.Minecraft;
            // package net.minecraft.fake;
            /* package com.mojang.fake; */
            class Adapter { String owner = "net.minecraft.client.Minecraft"; }
        ''').isEmpty())
    }
}

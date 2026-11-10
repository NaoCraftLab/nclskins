package com.naocraftlab.skins.buildlogic

import groovy.json.JsonOutput
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import static org.junit.jupiter.api.Assertions.*

final class DedicatedServerIsolationTest {
    @TempDir File root

    @Test void actualIsolationUsesMetadataAndCompiledEntrypointNotSourcePolicy() {
        File repository = new File('../..').canonicalFile
        String conventions = new File(repository, 'gradle/target-conventions.gradle').text
        String registration = conventions.substring(conventions.indexOf("def isolationTask ="),
                conventions.indexOf("tasks.named('check').configure {"))
        def project = ProjectBuilder.builder().withProjectDir(root).build()
        project.pluginManager.apply('java')
        File source = new File(root, 'common/Server.java')
        source.parentFile.mkdirs()
        source.text = '// net.minecraft.client is a policy-only source token\n'
        File script = new File(root, 'isolation.gradle')
        script.text = """
import groovy.json.JsonSlurper
import java.nio.charset.StandardCharsets
def spec = [loader: [id: 'fabric'], minecraft: [predicate: '>=26.2'],
    metadata: [entrypoint: 'fixture.Client', serverEntrypoint: 'fixture.Server', modMenuEntrypoint: 'fixture.Menu']]
def commonMinecraftJava = [file('common')]
""" + registration
        project.apply(from: script)
        File metadata = new File(root, 'build/resources/main/fabric.mod.json')
        metadata.parentFile.mkdirs()
        Map valid = [environment: '*', entrypoints: [main: ['fixture.Server'],
                client: ['fixture.Client'], modmenu: ['fixture.Menu']], depends: [minecraft: '>=26.2']]
        metadata.text = JsonOutput.toJson(valid)
        File compiled = new File(root, 'build/classes/java/main/fixture/Server.class')
        compiled.parentFile.mkdirs()
        compiled.bytes = entrypoint('net/fabricmc/api/ModInitializer')
        def task = project.tasks.getByName('verifyDedicatedServerIsolation')
        task.actions.last().execute(task)
        metadata.text = JsonOutput.toJson(valid + [environment: 'client'])
        assertThrows(GradleException) { task.actions.last().execute(task) }
        metadata.text = JsonOutput.toJson(valid)
        compiled.bytes = entrypoint('net/minecraft/client/Forbidden')
        assertThrows(GradleException) { task.actions.last().execute(task) }
        compiled.delete()
        assertThrows(GradleException) { task.actions.last().execute(task) }
    }

    private static byte[] entrypoint(String contract) {
        ClassWriter writer = new ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, 'fixture/Server', null, 'java/lang/Object', [contract] as String[])
        writer.visitEnd()
        writer.toByteArray()
    }
}

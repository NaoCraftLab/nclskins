package com.naocraftlab.skins.buildlogic

import groovy.json.JsonSlurper
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode

import java.util.zip.ZipFile

class EarsWardMeasurementBindingTest {
    private static Map values(AnnotationNode annotation) {
        Map result = [:]
        for (int i = 0; i < annotation.values.size(); i += 2) {
            result[annotation.values[i]] = annotation.values[i + 1]
        }
        result
    }

    private static String descriptor(AnnotationNode annotation) {
        Map value = values(annotation)
        Type.getMethodDescriptor(value.ret as Type, (value.args ?: []) as Type[])
    }

    @Test void distributedMeasurementSelectorsBindThePinnedWardOverloadAndInvocation() {
        File repository = new File('../..').canonicalFile
        Map catalog = CatalogTools.loadCatalog(repository)
        Map target = catalog.targets.find { it.id == 'fabric-1.21.11' }
        String name = target.artifact.file.replace('{modVersion}', CatalogTools.loadVersion(repository))
        File artifact = new File(repository, "${target.path}/build/libs/${name}")
        assert artifact.isFile(): "Canonical target artifact must exist: ${artifact}"
        File pinnedWard = new File(System.getProperty('nclskins.wardDependencyArtifact', ''))
        assert pinnedWard.isFile() && pinnedWard.length() > 0L: 'Selected artifact task must bind the catalog-pinned Ward dependency'
        ClassNode mixin = new ClassNode()
        new ZipFile(artifact).withCloseable { zip ->
            String entry = 'com/naocraftlab/skins/compat/client/identifier/ward/mixin/EarsMeasurementMixin.class'
            def mixinEntry = zip.getEntry(entry)
            assert mixinEntry != null: "Distributed artifact lacks ${entry}"
            new ClassReader(zip.getInputStream(mixinEntry)).accept(mixin, 0)
        }
        ClassNode ward = new ClassNode()
        URL wardResource = getClass().classLoader.getResource('diy/y2k/five/ward/Ward.class')
        assert wardResource != null
        assert wardResource.protocol == 'jar'
        assert wardResource.toExternalForm().startsWith("jar:${pinnedWard.toURI().toURL().toExternalForm()}!/"):
                "Ward class must come from the selected catalog dependency, got ${wardResource}"
        wardResource.openStream().withCloseable { new ClassReader(it).accept(ward, 0) }
        def handler = mixin.methods.find { it.name == 'nclskins$neutralFeatures' }
        Map injection = values(handler.visibleAnnotations.find { it.desc.endsWith('/ModifyExpressionValue;') })
        assert injection.require == 1 && injection.expect == 1 && injection.allow == 1
        String selector = injection.method[0]
        String methodName
        String methodDescriptor
        if (selector == '@Desc(features)') {
            def selected = handler.visibleAnnotations.find { it.desc == 'Lorg/spongepowered/asm/mixin/injection/Desc;' }
            assert selected != null
            Map dynamic = values(selected)
            assert dynamic.id == 'features'
            methodName = dynamic.value
            methodDescriptor = descriptor(selected)
        } else {
            int start = selector.indexOf('(')
            assert start > 0
            methodName = selector.substring(0, start)
            methodDescriptor = selector.substring(start)
        }
        def selectedMethods = ward.methods.findAll { it.name == methodName && it.desc == methodDescriptor }
        assert selectedMethods.size() == 1: "Distributed measurement selector misses actual Ward: ${selector} / ${methodDescriptor}"
        Map at = values(injection.at[0] as AnnotationNode)
        assert at.value == 'INVOKE'
        String owner
        String callName
        String callDescriptor
        if (at.desc instanceof AnnotationNode) {
            Map call = values(at.desc as AnnotationNode)
            owner = (call.owner as Type).internalName
            callName = call.value
            callDescriptor = descriptor(at.desc as AnnotationNode)
        } else {
            String call = at.target
            int separator = call.indexOf(';')
            int start = call.indexOf('(', separator)
            owner = call.substring(1, separator)
            callName = call.substring(separator + 1, start)
            callDescriptor = call.substring(start)
        }
        assert selectedMethods[0].instructions.toArray().count {
            it instanceof MethodInsnNode && it.owner == owner && it.name == callName && it.desc == callDescriptor
        } == 1: 'Distributed measurement invocation must match the exact pinned Ward call'
        assert handler.desc.contains(methodDescriptor.substring(1, methodDescriptor.indexOf(')')))
    }
}

package com.naocraftlab.skins.buildlogic

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

class PreAlphaSkinCaptureVerifierTest {
    @TempDir Path temp

    @Test
    void artifactGateUsesNativeProcessingIntermediaryRatherThanUploadOnBothFabricEpochs() {
        ['1.20.1': 'playerinfo', '1.21.1': 'skinlookup'].each { String epoch, String leaf ->
            Map target = [id: "fabric-${epoch}", minecraft: [epoch: epoch], loader: [id: 'fabric']]
            String entry = "com/naocraftlab/skins/compat/client/resourcelocation/${leaf}/mixin/HttpTextureSneakyMixin.class"
            assertTrue(artifactErrors(target, entry, fixture('method_22798', 'HEAD')).isEmpty())
            assertFalse(artifactErrors(target, entry, fixture('method_4531', 'HEAD')).isEmpty())
        }
    }

    private List<String> artifactErrors(Map target, String entry, byte[] mixin) {
        Path jar = temp.resolve('mapping-' + target.id + '-' + UUID.randomUUID() + '.jar')
        new ZipOutputStream(jar.toFile().newOutputStream()).withCloseable { ZipOutputStream zip ->
            zip.putNextEntry(new ZipEntry(entry))
            zip.write(mixin)
            zip.closeEntry()
        }
        List<String> errors = []
        new ZipFile(jar.toFile()).withCloseable { ZipFile zip ->
            ArtifactVerifier.verifyPreAlphaSkinCapture(zip, target, [entry], errors)
        }
        errors
    }

    @Test
    void onlyHeadOfNativeProcessingAdmitsLegacyCapture() {
        assertTrue(PreAlphaSkinCaptureVerifier.injectedAtHead(
                fixture('processLegacySkin', 'HEAD'), 'nclskins$capture', 'processLegacySkin'))
        assertFalse(PreAlphaSkinCaptureVerifier.injectedAtHead(
                fixture('upload', 'HEAD'), 'nclskins$capture', 'processLegacySkin'))
        assertFalse(PreAlphaSkinCaptureVerifier.injectedAtHead(
                fixture('processLegacySkin', 'TAIL'), 'nclskins$capture', 'processLegacySkin'))
    }

    @Test
    void nativeImageDuckInterfaceMustLinkFromAnotherPackage() {
        assertTrue(PreAlphaSkinCaptureVerifier.publicDuckLinks(duckFixture(Opcodes.ACC_PUBLIC)))
        assertFalse(PreAlphaSkinCaptureVerifier.publicDuckLinks(duckFixture(0)))
    }

    private static byte[] duckFixture(int visibility) {
        ClassWriter writer = new ClassWriter(0)
        writer.visit(Opcodes.V17, visibility | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                'com/naocraftlab/skins/compat/client/identifier/SneakyUnmodifiedSkinPixels',
                null, 'java/lang/Object', null)
        writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                'nclskins$takeUnmodifiedSkinPixels', '()[I', null, null).visitEnd()
        writer.visitEnd()
        writer.toByteArray()
    }

    private static byte[] fixture(String target, String point) {
        ClassWriter writer = new ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, 'test/PreAlpha', null, 'java/lang/Object', null)
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PRIVATE, 'nclskins$capture', '()V', null, null)
        AnnotationVisitor inject = method.visitAnnotation(
                'Lorg/spongepowered/asm/mixin/injection/Inject;', true)
        AnnotationVisitor methods = inject.visitArray('method')
        methods.visit(null, target)
        methods.visitEnd()
        AnnotationVisitor points = inject.visitArray('at')
        AnnotationVisitor at = points.visitAnnotation(null,
                'Lorg/spongepowered/asm/mixin/injection/At;')
        at.visit('value', point)
        at.visitEnd()
        points.visitEnd()
        inject.visitEnd()
        method.visitCode()
        method.visitInsn(Opcodes.RETURN)
        method.visitMaxs(0, 1)
        method.visitEnd()
        writer.visitEnd()
        writer.toByteArray()
    }
}

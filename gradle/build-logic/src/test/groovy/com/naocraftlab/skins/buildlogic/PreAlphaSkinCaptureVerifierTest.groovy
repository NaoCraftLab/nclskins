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
        assertTrue(PreAlphaSkinCaptureVerifier.publicDuckLinks(duckFixture()))
        assertFalse(PreAlphaSkinCaptureVerifier.publicDuckLinks(duckFixture(visibility: 0)))
    }

    @Test
    void publicDuckLinkageDoesNotDependOnTheVerifierJvmClassfileVersion() {
        [Opcodes.V17, Opcodes.V21, Opcodes.V25].each { int version ->
            assertTrue(PreAlphaSkinCaptureVerifier.publicDuckLinks(duckFixture(version: version)))
            assertFalse(PreAlphaSkinCaptureVerifier.publicDuckLinks(duckFixture(visibility: 0, version: version)))
        }
    }

    @Test
    void duckContractRejectsWrongKindMissingMethodsAndInaccessibleSignatures() {
        [
                [kind: Opcodes.ACC_ABSTRACT],
                [remember: false],
                [takeDescriptor: '()[B'],
                [methodAccess: Opcodes.ACC_PRIVATE | Opcodes.ACC_ABSTRACT],
                [methodAccess: Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC],
                [methodAccess: Opcodes.ACC_PUBLIC]
        ].each { Map options ->
            assertFalse(PreAlphaSkinCaptureVerifier.publicDuckLinks(duckFixture(options)))
        }
    }

    private static byte[] duckFixture(Map options = [:]) {
        int visibility = options.get('visibility', Opcodes.ACC_PUBLIC)
        int version = options.get('version', Opcodes.V17)
        int kind = options.get('kind', Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT)
        int methodAccess = options.get('methodAccess', Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT)
        ClassWriter writer = new ClassWriter(0)
        writer.visit(version, visibility | kind,
                'com/naocraftlab/skins/compat/client/identifier/SneakyUnmodifiedSkinPixels',
                null, 'java/lang/Object', null)
        if (options.get('remember', true)) {
            writer.visitMethod(methodAccess,
                    'nclskins$rememberUnmodifiedSkinPixels', '([I)V', null, null).visitEnd()
        }
        writer.visitMethod(methodAccess,
                'nclskins$takeUnmodifiedSkinPixels', options.get('takeDescriptor', '()[I'), null, null).visitEnd()
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

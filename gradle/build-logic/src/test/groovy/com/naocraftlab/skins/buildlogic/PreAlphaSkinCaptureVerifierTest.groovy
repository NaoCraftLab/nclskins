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
            assertTrue(artifactErrors(target, entry, wrapper('method_22798', true)).isEmpty())
            assertFalse(artifactErrors(target, entry, wrapper('method_4531', true)).isEmpty())
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
    void modernArtifactGateAcceptsRegistrationWrapperAndRejectsWrongTargetOrLatePublication() {
        [[epoch: '1.21.11', loader: 'fabric', process: 'method_65863', register: 'method_65860'],
         [epoch: '26.2', loader: 'neoforge', process: 'processLegacySkin', register: 'registerTextureInManager']].each { Map mapping ->
            assertTrue(modernArtifactErrors(mapping, mapping.register, true).isEmpty())
            assertFalse(modernArtifactErrors(mapping, 'upload', true).isEmpty())
            assertFalse(modernArtifactErrors(mapping, mapping.register, false).isEmpty())
        }
    }

    private List<String> modernArtifactErrors(Map mapping, String register, boolean before) {
        String base = 'com/naocraftlab/skins/compat/client/identifier/'
        ClassWriter writer = new ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, 'com/naocraftlab/skins/test/ModernCapture', null, 'java/lang/Object', null)
        ['nclskins$raw', 'nclskins$captureSkinPixels'].each { String handler ->
            boolean raw = handler == 'nclskins$raw'
            MethodVisitor method = writer.visitMethod(Opcodes.ACC_PRIVATE, handler, '()V', null, null)
            AnnotationVisitor wrap = method.visitAnnotation('Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;', true)
            AnnotationVisitor targets = wrap.visitArray('method')
            targets.visit(null, (raw ? mapping.process : register) + '()V')
            targets.visitEnd()
            wrap.visitEnd()
            method.visitCode()
            Closure capture = {
                if (raw) {
                    method.visitMethodInsn(Opcodes.INVOKESTATIC, 'com/naocraftlab/skins/test/ModernCapture', 'nclskins$rememberUnmodifiedSkinPixels', '()V', false)
                } else {
                    method.visitMethodInsn(Opcodes.INVOKEINTERFACE, base + 'SneakyUnmodifiedSkinPixels', 'nclskins$takeUnmodifiedSkinPixels', '()[I', true)
                    method.visitMethodInsn(Opcodes.INVOKESTATIC, 'com/naocraftlab/skins/runtime/CapeProjection', 'skinTextureReady', '()V', false)
                }
            }
            if (raw || before) capture.call()
            method.visitMethodInsn(Opcodes.INVOKEINTERFACE, 'com/llamalad7/mixinextras/injector/wrapoperation/Operation', 'call', '([Ljava/lang/Object;)Ljava/lang/Object;', true)
            if (!raw && !before) capture.call()
            method.visitInsn(Opcodes.RETURN)
            method.visitMaxs(2, 1)
            method.visitEnd()
        }
        MethodVisitor remember = writer.visitMethod(Opcodes.ACC_PRIVATE, 'nclskins$rememberUnmodifiedSkinPixels', '()V', null, null)
        remember.visitCode()
        remember.visitMethodInsn(Opcodes.INVOKEINTERFACE, base + 'SneakyUnmodifiedSkinPixels', 'nclskins$rememberUnmodifiedSkinPixels', '([I)V', true)
        remember.visitInsn(Opcodes.RETURN)
        remember.visitMaxs(2, 1)
        remember.visitEnd()
        writer.visitEnd()
        Map<String, byte[]> entries = [(base + 'SneakyUnmodifiedSkinPixels.class'): duckFixture(),
                (base + 'mixin/SkinTextureDownloaderProviderMixin.class'): writer.toByteArray(),
                (base + 'mixin/NativeImageSneakyPixelsMixin.class'): duckFixture()]
        Path jar = temp.resolve(UUID.randomUUID().toString() + '.jar')
        new ZipOutputStream(jar.toFile().newOutputStream()).withCloseable { ZipOutputStream zip ->
            entries.each { String entry, byte[] bytes ->
                zip.putNextEntry(new ZipEntry(entry))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        List<String> errors = []
        new ZipFile(jar.toFile()).withCloseable { ZipFile zip ->
            ArtifactVerifier.verifyPreAlphaSkinCapture(zip,
                    [id: mapping.loader + '-' + mapping.epoch, minecraft: [epoch: mapping.epoch], loader: [id: mapping.loader]],
                    entries.keySet().toList(), errors)
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

    @Test
    void wrapperMustCopyBeforeOriginalAndUseTheNativeProcessingDescriptor() {
        assertTrue(PreAlphaSkinCaptureVerifier.wrappedBeforeOriginal(wrapper('processLegacySkin', true), 'processLegacySkin', 'nclskins$capture'))
        assertFalse(PreAlphaSkinCaptureVerifier.wrappedBeforeOriginal(wrapper('processLegacySkin', false), 'processLegacySkin', 'nclskins$capture'))
        assertFalse(PreAlphaSkinCaptureVerifier.wrappedBeforeOriginal(wrapper('upload', true), 'processLegacySkin', 'nclskins$capture'))
    }

    private static byte[] wrapper(String target, boolean before) {
        ClassWriter writer = new ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, 'test/PreAlpha', null, 'java/lang/Object', null)
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PRIVATE, 'nclskins$raw', '()V', null, null)
        AnnotationVisitor wrap = method.visitAnnotation('Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;', true)
        AnnotationVisitor methods = wrap.visitArray('method')
        methods.visit(null, target + '(Lcom/mojang/blaze3d/platform/NativeImage;)Lcom/mojang/blaze3d/platform/NativeImage;')
        methods.visitEnd()
        wrap.visitEnd()
        method.visitCode()
        if (before) method.visitMethodInsn(Opcodes.INVOKESTATIC, 'test/PreAlpha', 'nclskins$capture', '()V', false)
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE, 'com/llamalad7/mixinextras/injector/wrapoperation/Operation', 'call', '([Ljava/lang/Object;)Ljava/lang/Object;', true)
        if (!before) method.visitMethodInsn(Opcodes.INVOKESTATIC, 'test/PreAlpha', 'nclskins$capture', '()V', false)
        method.visitInsn(Opcodes.RETURN)
        method.visitMaxs(2, 1)
        method.visitEnd()
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

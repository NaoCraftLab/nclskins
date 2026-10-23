package com.naocraftlab.skins.buildlogic

import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.nio.file.Files
import static org.junit.jupiter.api.Assertions.*

class WaveyCapesNativeAbiTest {
    @Test
    void immediateFieldAndForwardingBindingsUseExactDescriptors() {
        File baseline = fixture('Z', false, 'sortOnUpload')
        WaveyCapesNativeAbi.verify([baseline], 'player-info-cache')
        assertThrows(IllegalStateException) {
            WaveyCapesNativeAbi.verify([fixture('I', false, 'sortOnUpload')], 'player-info-cache')
        }
        assertThrows(IllegalStateException) {
            WaveyCapesNativeAbi.verify([fixture('Z', true, 'sortOnUpload')], 'player-info-cache')
        }
        assertThrows(IllegalStateException) {
            WaveyCapesNativeAbi.verify([fixture('Z', false, 'otherField')], 'player-info-cache')
        }
    }

    private static File fixture(String fieldDescriptor, boolean finalMethod, String accessor) {
        File directory = new File('build/test-data/wavey-native-abi/' + UUID.randomUUID())
        directory.mkdirs()
        String nativeType = 'net/minecraft/client/renderer/RenderType'
        ClassWriter nativeClass = new ClassWriter(0)
        nativeClass.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, nativeType, null, 'java/lang/Object', null)
        nativeClass.visitField(Opcodes.ACC_PRIVATE, 'sortOnUpload', fieldDescriptor, null, null).visitEnd()
        def end = nativeClass.visitMethod(Opcodes.ACC_PUBLIC | (finalMethod ? Opcodes.ACC_FINAL : 0), 'end',
                '(Lcom/mojang/blaze3d/vertex/BufferBuilder;Lcom/mojang/blaze3d/vertex/VertexSorting;)V', null, null)
        end.visitCode(); end.visitInsn(Opcodes.RETURN); end.visitMaxs(0, 3); end.visitEnd()
        nativeClass.visitEnd()
        write(directory, nativeType, nativeClass.toByteArray())
        String ownType = 'com/naocraftlab/skins/compat/client/cape/mixin/RenderTypeFlagsMixin'
        ClassWriter ownClass = new ClassWriter(0)
        ownClass.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                ownType, null, 'java/lang/Object', null)
        def mixin = ownClass.visitAnnotation('Lorg/spongepowered/asm/mixin/Mixin;', false)
        def targets = mixin.visitArray('value'); targets.visit(null, Type.getObjectType(nativeType)); targets.visitEnd(); mixin.visitEnd()
        def getter = ownClass.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, 'nclskins$sortOnUpload', '()Z', null, null)
        def annotation = getter.visitAnnotation('Lorg/spongepowered/asm/mixin/gen/Accessor;', false)
        annotation.visit('value', accessor); annotation.visitEnd(); getter.visitEnd(); ownClass.visitEnd()
        write(directory, ownType, ownClass.toByteArray())
        directory
    }

    private static void write(File directory, String name, byte[] bytes) {
        File output = new File(directory, name + '.class')
        output.parentFile.mkdirs()
        output.bytes = bytes
    }
}

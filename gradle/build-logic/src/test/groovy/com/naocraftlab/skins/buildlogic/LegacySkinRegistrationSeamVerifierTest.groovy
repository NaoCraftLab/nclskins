package com.naocraftlab.skins.buildlogic

import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

class LegacySkinRegistrationSeamVerifierTest {
    @Test
    void registrationMustBindItsLocationArgumentBeforeRegister() {
        assertTrue(LegacySkinRegistrationSeamVerifier.bindsRegisteredLocationBeforeTextureRegistration(
                registration(2)))
        assertFalse(LegacySkinRegistrationSeamVerifier.bindsRegisteredLocationBeforeTextureRegistration(
                registration(1)), 'another location, such as the constructor fallback, cannot identify an upload')
    }

    @Test
    void textureMustNotCaptureFallbackIdentityFromConstructor() {
        assertTrue(LegacySkinRegistrationSeamVerifier.uploadsOnlyRegisteredLocation(texture(false)))
        assertFalse(LegacySkinRegistrationSeamVerifier.uploadsOnlyRegisteredLocation(texture(true)))
    }

    private static byte[] registration(int locationSlot) {
        ClassWriter writer = new ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, 'test/Registration', null, 'java/lang/Object', null)
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PRIVATE, 'nclskins$register', '()V', null, null)
        method.visitCode()
        method.visitVarInsn(Opcodes.ALOAD, 3)
        method.visitVarInsn(Opcodes.ALOAD, locationSlot)
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE, 'test/RegisteredSkinTexture',
                'nclskins$registeredSkinLocation', '(Ljava/lang/Object;)V', true)
        method.visitVarInsn(Opcodes.ALOAD, 4)
        method.visitInsn(Opcodes.ICONST_0)
        method.visitTypeInsn(Opcodes.ANEWARRAY, 'java/lang/Object')
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                'com/llamalad7/mixinextras/injector/wrapoperation/Operation',
                'call', '([Ljava/lang/Object;)Ljava/lang/Object;', true)
        method.visitInsn(Opcodes.POP)
        method.visitInsn(Opcodes.RETURN)
        method.visitMaxs(3, 5)
        method.visitEnd()
        writer.visitEnd()
        writer.toByteArray()
    }

    private static byte[] texture(boolean constructorCapture) {
        ClassWriter writer = new ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, 'test/Texture', null, 'java/lang/Object', null)
        writer.visitField(Opcodes.ACC_PRIVATE, 'nclskins$skinLocation', 'Ljava/lang/String;', null, null).visitEnd()
        MethodVisitor setter = writer.visitMethod(Opcodes.ACC_PUBLIC, 'nclskins$registeredSkinLocation',
                '(Ljava/lang/String;)V', null, null)
        setter.visitCode()
        setter.visitVarInsn(Opcodes.ALOAD, 0)
        setter.visitVarInsn(Opcodes.ALOAD, 1)
        setter.visitFieldInsn(Opcodes.PUTFIELD, 'test/Texture', 'nclskins$skinLocation', 'Ljava/lang/String;')
        setter.visitInsn(Opcodes.RETURN)
        setter.visitMaxs(2, 2)
        setter.visitEnd()
        if (constructorCapture) {
            MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, '<init>', '()V', null, null)
            constructor.visitCode()
            constructor.visitVarInsn(Opcodes.ALOAD, 0)
            constructor.visitInsn(Opcodes.ACONST_NULL)
            constructor.visitFieldInsn(Opcodes.PUTFIELD, 'test/Texture', 'nclskins$skinLocation', 'Ljava/lang/String;')
            constructor.visitInsn(Opcodes.RETURN)
            constructor.visitMaxs(2, 1)
            constructor.visitEnd()
        }
        MethodVisitor upload = writer.visitMethod(Opcodes.ACC_PRIVATE, 'nclskins$capture', '()V', null, null)
        upload.visitCode()
        upload.visitVarInsn(Opcodes.ALOAD, 0)
        upload.visitFieldInsn(Opcodes.GETFIELD, 'test/Texture', 'nclskins$skinLocation', 'Ljava/lang/String;')
        upload.visitInsn(Opcodes.ACONST_NULL)
        upload.visitMethodInsn(Opcodes.INVOKESTATIC, 'com/naocraftlab/skins/runtime/CapeProjection',
                'skinTextureReady', '(Ljava/lang/String;[I)V', false)
        upload.visitInsn(Opcodes.RETURN)
        upload.visitMaxs(2, 1)
        upload.visitEnd()
        writer.visitEnd()
        writer.toByteArray()
    }
}

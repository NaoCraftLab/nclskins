package com.naocraftlab.skins.buildlogic

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

final class LegacySkinRegistrationSeamVerifier {
    static boolean bindsRegisteredLocationBeforeTextureRegistration(byte[] bytes) {
        int[] bindings = [0] as int[]
        int[] registrations = [0] as int[]
        boolean[] valid = [true] as boolean[]
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            MethodVisitor visitMethod(int access, String name, String descriptor,
                                      String signature, String[] exceptions) {
                if (name != 'nclskins$register') return null
                return new MethodVisitor(Opcodes.ASM9) {
                    int precedingLocationLoad = -1

                    @Override
                    void visitVarInsn(int opcode, int variable) {
                        precedingLocationLoad = opcode == Opcodes.ALOAD ? variable : -1
                    }

                    @Override
                    void visitInsn(int opcode) { precedingLocationLoad = -1 }

                    @Override
                    void visitFieldInsn(int opcode, String owner, String field, String fieldDescriptor) {
                        precedingLocationLoad = -1
                    }

                    @Override
                    void visitTypeInsn(int opcode, String type) { precedingLocationLoad = -1 }

                    @Override
                    void visitMethodInsn(int opcode, String owner, String called,
                                         String calledDescriptor, boolean isInterface) {
                        if (owner.endsWith('/RegisteredSkinTexture') && called == 'nclskins$registeredSkinLocation') {
                            bindings[0]++
                            if (opcode != Opcodes.INVOKEINTERFACE || precedingLocationLoad != 2
                                    || registrations[0] != 0) valid[0] = false
                        }
                        if (owner == 'com/llamalad7/mixinextras/injector/wrapoperation/Operation' && called == 'call') {
                            registrations[0]++
                            if (bindings[0] != 1) valid[0] = false
                        }
                        precedingLocationLoad = -1
                    }
                }
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES)
        valid[0] && bindings[0] == 1 && registrations[0] == 1
    }

    static boolean uploadsOnlyRegisteredLocation(byte[] bytes) {
        int[] writes = [0] as int[]
        int[] reads = [0] as int[]
        int[] uploads = [0] as int[]
        boolean[] valid = [true] as boolean[]
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            MethodVisitor visitMethod(int access, String name, String descriptor,
                                      String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    void visitFieldInsn(int opcode, String owner, String field, String fieldDescriptor) {
                        if (field != 'nclskins$skinLocation') return
                        if (opcode == Opcodes.PUTFIELD) {
                            writes[0]++
                            if (name != 'nclskins$registeredSkinLocation') valid[0] = false
                        }
                        if (opcode == Opcodes.GETFIELD && name == 'nclskins$capture') reads[0]++
                    }

                    @Override
                    void visitMethodInsn(int opcode, String owner, String called,
                                         String calledDescriptor, boolean isInterface) {
                        if (name == 'nclskins$capture'
                                && owner == 'com/naocraftlab/skins/runtime/CapeProjection'
                                && called == 'skinTextureReady') uploads[0]++
                    }
                }
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES)
        valid[0] && writes[0] == 1 && reads[0] >= 1 && uploads[0] == 1
    }
}

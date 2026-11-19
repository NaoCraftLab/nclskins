package com.naocraftlab.skins.buildlogic

import org.objectweb.asm.*
import org.objectweb.asm.tree.*

import static org.objectweb.asm.Opcodes.*

final class PreAlphaSkinCaptureVerifier {
    static boolean publicDuckLinks(byte[] interfaceBytes) {
        ClassReader reader = new ClassReader(interfaceBytes)
        int requiredType = Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT
        int forbiddenType = Opcodes.ACC_FINAL | Opcodes.ACC_ANNOTATION | Opcodes.ACC_ENUM
        if ((reader.access & requiredType) != requiredType || (reader.access & forbiddenType) != 0
                || reader.superName != 'java/lang/Object' || reader.interfaces.length != 0) return false
        Map<String, String> requiredMethods = [
                'nclskins$rememberUnmodifiedSkinPixels': '([I)V',
                'nclskins$takeUnmodifiedSkinPixels': '()[I'
        ]
        Set<String> found = [] as Set<String>
        boolean[] valid = [true] as boolean[]
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            MethodVisitor visitMethod(int access, String name, String descriptor,
                                      String signature, String[] exceptions) {
                int required = Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT
                int forbidden = Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED | Opcodes.ACC_STATIC
                        | Opcodes.ACC_FINAL | Opcodes.ACC_NATIVE
                if (requiredMethods[name] != descriptor || (access & required) != required
                        || (access & forbidden) != 0 || !found.add(name)) valid[0] = false
                null
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES)
        valid[0] && found == requiredMethods.keySet()
    }

    static boolean injectedAtHead(byte[] bytes, String handler, String targetMethod) {
        int[] handlers = [0] as int[]
        boolean[] method = [false] as boolean[]
        boolean[] head = [false] as boolean[]
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            MethodVisitor visitMethod(int access, String name, String methodDescriptor,
                                      String signature, String[] exceptions) {
                if (name != handler) return null
                handlers[0]++
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                        if (annotation != 'Lorg/spongepowered/asm/mixin/injection/Inject;') return null
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            AnnotationVisitor visitArray(String key) {
                                if (key == 'method') return new AnnotationVisitor(Opcodes.ASM9) {
                                    @Override
                                    void visit(String ignored, Object value) {
                                        if (value == targetMethod) method[0] = true
                                    }
                                }
                                if (key == 'at') return new AnnotationVisitor(Opcodes.ASM9) {
                                    @Override
                                    AnnotationVisitor visitAnnotation(String ignored, String atType) {
                                        if (atType != 'Lorg/spongepowered/asm/mixin/injection/At;') return null
                                        return new AnnotationVisitor(Opcodes.ASM9) {
                                            @Override
                                            void visit(String field, Object value) {
                                                if (field == 'value' && value == 'HEAD') head[0] = true
                                            }
                                        }
                                    }
                                }
                                null
                            }
                        }
                    }
                }
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES)
        handlers[0] == 1 && method[0] && head[0]
    }

    static boolean wrappedBeforeOriginal(byte[] bytes, String target, String capture, String handler = 'nclskins$raw') {
        boolean[] annotated = [false] as boolean[]
        List<String> calls = []
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                if (name != handler) return null
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                        if (annotation != 'Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;') return null
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override AnnotationVisitor visitArray(String key) {
                                if (key != 'method') return null
                                return new AnnotationVisitor(Opcodes.ASM9) {
                                    @Override void visit(String ignored, Object value) {
                                        if (value.toString().startsWith(target + '(')) annotated[0] = true
                                    }
                                }
                            }
                        }
                    }
                    @Override void visitMethodInsn(int opcode, String owner, String called, String calledDescriptor, boolean itf) {
                        calls.add(owner + '.' + called)
                    }
                }
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES)
        int raw = calls.findIndexOf { it.endsWith('.' + capture) }
        int original = calls.indexOf('com/llamalad7/mixinextras/injector/wrapoperation/Operation.call')
        annotated[0] && raw >= 0 && original > raw
    }

    static boolean providerRegistrationStages(byte[] bytes) {
        if (!wrappedBeforeOriginal(bytes, 'processLegacySkin', 'nclskins$rememberUnmodifiedSkinPixels')) return false
        Set<String> handlers = [] as Set<String>
        List<String> publicationSites = []
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                handlers.add(name)
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    void visitMethodInsn(int opcode, String owner, String called, String desc, boolean isInterface) {
                        if (owner == 'com/naocraftlab/skins/runtime/CapeProjection' && called == 'skinTextureReady') {
                            publicationSites.add(name)
                        }
                    }
                }
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES)
        handlers.contains('ncl$registerSkinProvider') && !handlers.contains('nclskins$captureSkinPixels')
                && publicationSites == ['ncl$publishRegisteredSkin']
    }

    static boolean dynamicRegistrationStages(byte[] downloader, byte[] manager, boolean intermediary) {
        String process = intermediary ? 'method_65863' : 'processLegacySkin'
        if (!wrappedBeforeOriginal(downloader, process, 'nclskins$rememberUnmodifiedSkinPixels')) return false
        def nodes = [downloader, manager].collect { byte[] bytes ->
            ClassNode node = new ClassNode(Opcodes.ASM9)
            new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES)
            node
        }
        Closure calls = { MethodNode method ->
            method.instructions.toArray().findAll { it instanceof MethodInsnNode }.collect { it.owner + '.' + it.name }
        }
        String carrier = 'com/naocraftlab/skins/compat/client/identifier/SneakyUnmodifiedSkinPixels.'
        String projection = 'com/naocraftlab/skins/runtime/CapeProjection.skinTextureReady'
        String register = intermediary ? 'method_4616(Lnet/minecraft/class_2960;Lnet/minecraft/class_1044;)V' :
                'register(Lnet/minecraft/resources/Identifier;Lnet/minecraft/client/renderer/texture/AbstractTexture;)V'
        def remember = nodes[0].methods.findAll { it.name == 'nclskins$rememberUnmodifiedSkinPixels' }
        def publish = nodes[1].methods.findAll { it.name == 'nclskins$publishRegisteredSkin' }
        if (remember.size() != 1 || publish.size() != 1) return false
        def binding = publish[0].visibleAnnotations?.find {
            it.desc == 'Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;'
        }?.values
        binding != null && binding.indexOf('method') >= 0 && binding[binding.indexOf('method') + 1] == [register]
                && ['require', 'expect', 'allow'].every { key -> binding.indexOf(key) >= 0 && binding[binding.indexOf(key) + 1] == 1 }
                && calls(remember[0]).contains(carrier + 'nclskins$rememberUnmodifiedSkinPixels')
                && (nodes[0].methods + nodes[1].methods).findAll { calls(it).contains(projection) } == publish
                && registeredDynamicBody(nodes[1], publish[0], intermediary)
    }

    private static boolean registeredDynamicBody(ClassNode owner, MethodNode publish, boolean intermediary) {
        if (publish.tryCatchBlocks) return false
        List<AbstractInsnNode> code = publish.instructions.toArray().findAll { it.opcode >= 0 }
        if (code*.opcode != [ALOAD, ICONST_2, ANEWARRAY, DUP, ICONST_0, ALOAD, AASTORE, DUP, ICONST_1, ALOAD, AASTORE,
                INVOKEINTERFACE, POP, ALOAD, INSTANCEOF, IFEQ, ALOAD, CHECKCAST, ASTORE, ALOAD, GETFIELD, ALOAD,
                INVOKEINTERFACE, ALOAD, IF_ACMPEQ, RETURN, ALOAD, INVOKEVIRTUAL, ASTORE, ALOAD, IFNONNULL, RETURN,
                ALOAD, CHECKCAST, INVOKEINTERFACE, ASTORE, ALOAD, IFNONNULL, RETURN, ALOAD, INVOKEVIRTUAL, ASTORE,
                ALOAD, INVOKEVIRTUAL, LDC, INVOKEVIRTUAL, IFEQ, ALOAD, INVOKESTATIC, GETSTATIC, INVOKEVIRTUAL,
                CHECKCAST, ASTORE, ALOAD, GETSTATIC, IF_ACMPEQ, ALOAD, GETSTATIC, IF_ACMPEQ, RETURN,
                ALOAD, ALOAD, ALOAD, INVOKESTATIC, RETURN]) return false
        if (code.findAll { it instanceof VarInsnNode }*.var !=
                [3, 1, 2, 2, 2, 4, 0, 1, 2, 4, 5, 5, 5, 6, 6, 1, 7, 1, 7, 8, 8, 8, 7, 6, 2]) return false
        Map branches = code.indexed().findAll { i, insn -> insn instanceof JumpInsnNode }.collectEntries { i, insn ->
            AbstractInsnNode target = insn.label
            while (target != null && target.opcode < 0) target = target.next
            [(i): code.indexOf(target)]
        }
        if (branches != [15: 25, 24: 26, 30: 32, 37: 39, 46: 60, 55: 60, 58: 60]) return false
        String state = 'com/naocraftlab/skins/client/NativePlayerSkinLifecycle$State'
        String identifier = intermediary ? 'net/minecraft/class_2960' : 'net/minecraft/resources/Identifier'
        String abstractTexture = intermediary ? 'net/minecraft/class_1044' : 'net/minecraft/client/renderer/texture/AbstractTexture'
        String dynamicTexture = intermediary ? 'net/minecraft/class_1043' : 'net/minecraft/client/renderer/texture/DynamicTexture'
        String textureManager = intermediary ? 'net/minecraft/class_1060' : 'net/minecraft/client/renderer/texture/TextureManager'
        String image = intermediary ? 'net/minecraft/class_1011' : 'com/mojang/blaze3d/platform/NativeImage'
        String carrier = 'com/naocraftlab/skins/compat/client/identifier/SneakyUnmodifiedSkinPixels'
        String operation = 'com/llamalad7/mixinextras/injector/wrapoperation/Operation'
        if ((publish.access & (ACC_PUBLIC | ACC_PROTECTED | ACC_PRIVATE | ACC_STATIC)) != ACC_PRIVATE
                || publish.desc != "(L${identifier};L${abstractTexture};L${operation};)V") return false
        List mixins = owner.invisibleAnnotations?.findAll { it.desc == 'Lorg/spongepowered/asm/mixin/Mixin;' }
        if (mixins?.size() != 1 || mixins[0].values != ['value', [Type.getObjectType(textureManager)]]) return false
        if (code.findAll { it instanceof TypeInsnNode }*.desc != ['java/lang/Object', dynamicTexture, dynamicTexture, carrier, state]) return false
        Map registryFields = [20: [owner.name, intermediary ? 'field_5286' : 'byPath', 'Ljava/util/Map;'],
                              49: [state, 'RETIRED', "L${state};"], 54: [state, 'PENDING', "L${state};"],
                              57: [state, 'READY', "L${state};"]]
        Map identityCalls = [11: [operation, 'call', '([Ljava/lang/Object;)Ljava/lang/Object;'],
                             22: ['java/util/Map', 'get', '(Ljava/lang/Object;)Ljava/lang/Object;'],
                             27: [dynamicTexture, intermediary ? 'method_4525' : 'getPixels', "()L${image};"],
                             34: [carrier, 'nclskins$takeUnmodifiedSkinPixels', '()[I'],
                             40: [identifier, 'toString', '()Ljava/lang/String;'],
                             43: [identifier, intermediary ? 'method_12836' : 'getNamespace', '()Ljava/lang/String;'],
                             45: ['java/lang/String', 'equals', '(Ljava/lang/Object;)Z'],
                             48: ['com/naocraftlab/skins/client/NativePlayerSkinLifecycle', 'managedState', '(Ljava/lang/String;)Ljava/util/Optional;'],
                             50: ['java/util/Optional', 'orElse', '(Ljava/lang/Object;)Ljava/lang/Object;'],
                             63: ['com/naocraftlab/skins/runtime/CapeProjection', 'skinTextureReady', '(Ljava/lang/String;[ILjava/lang/Object;)V']]
        registryFields.every { i, field -> [code[i].owner, code[i].name, code[i].desc] == field }
                && code[44].cst == 'nclskins'
                && identityCalls.every { i, call ->
                    code[i].itf == (code[i].opcode == INVOKEINTERFACE) && [code[i].owner, code[i].name, code[i].desc] == call
                }
    }
}

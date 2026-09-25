package com.naocraftlab.skins.buildlogic

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

import java.security.MessageDigest
import java.util.zip.ZipFile

final class OptifineCapeAbiVerifier {
    private static final String PLAYER = 'net/minecraft/client/player/AbstractClientPlayer'
    private static final String CAPE = 'net/minecraft/client/renderer/entity/layers/CapeLayer'
    private static final String ELYTRA = 'net/minecraft/client/renderer/entity/layers/ElytraLayer'
    private static final String CAPE_UTILS = 'net/optifine/player/CapeUtils'
    private static final String LOCATION = '()Lnet/minecraft/resources/ResourceLocation;'
    private static final String CAPE_ARGUMENT = '(Lnet/minecraft/client/player/AbstractClientPlayer;)V'

    static void verify(Map pinned, File optifineJar, File minecraftClientJar) {
        verifiedClasses(pinned, optifineJar, minecraftClientJar)
    }

    static Map<String, byte[]> verifiedClasses(Map pinned, File optifineJar, File minecraftClientJar) {
        requireHash(optifineJar, pinned.jarSha256.toString())
        requireHash(minecraftClientJar, pinned.minecraftClientSha256.toString())
        return new ZipFile(optifineJar).withCloseable { ZipFile optifine ->
            new ZipFile(minecraftClientJar).withCloseable { ZipFile vanilla ->
                String index = new String(read(optifine, 'patch2.cfg'), 'UTF-8')
                Map<String, byte[]> patched = [:]
                (pinned.patchedClasses as Map).each { Object rawName, Object rawPin ->
                    String name = rawName.toString()
                    Map pin = rawPin as Map
                    String base = pin.base.toString()
                    if (!index.contains("srg/${name}.class=${base}")) {
                        throw new IllegalStateException("OptiFine patch2.cfg changed for ${name}")
                    }
                    byte[] bytes = reconstruct(read(vanilla, base),
                            read(optifine, "patch/srg/${name}.class.xdelta"))
                    String embeddedMd5 = new String(
                            read(optifine, "patch/srg/${name}.class.md5"), 'US-ASCII').trim()
                    String actualMd5 = digest(bytes, 'MD5')
                    if (actualMd5 != embeddedMd5 || actualMd5 != pin.md5) {
                        throw new IllegalStateException("OptiFine reconstructed MD5 changed for ${name}")
                    }
                    patched[name] = bytes
                }
                byte[] utils = read(optifine, "srg/${CAPE_UTILS}.class")
                inspect(patched, utils)
                patched[CAPE_UTILS] = utils
                patched
            }
        }
    }

    static byte[] reconstruct(byte[] base, byte[] patch) {
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(patch))
        if (input.readUnsignedByte() != 0xd1 || input.readUnsignedByte() != 0xff
                || input.readUnsignedByte() != 0xd1 || input.readUnsignedByte() != 0xff
                || input.readUnsignedByte() != 4) {
            throw new IllegalStateException('Unsupported OptiFine GDIFF header')
        }
        ByteArrayOutputStream result = new ByteArrayOutputStream()
        while (input.available() > 0) {
            int opcode = input.readUnsignedByte()
            if (opcode == 0) {
                if (input.available() != 0) {
                    throw new IllegalStateException('OptiFine GDIFF has trailing bytes')
                }
            } else if (opcode <= 246) {
                result.write(input.readNBytes(opcode))
            } else if (opcode == 247 || opcode == 248) {
                int size = opcode == 247 ? input.readUnsignedShort() : input.readInt()
                checkedSize(size)
                result.write(input.readNBytes(size))
            } else {
                long offset = opcode <= 251 ? input.readUnsignedShort()
                        : opcode <= 254 ? Integer.toUnsignedLong(input.readInt()) : input.readLong()
                int size = opcode in [249, 252] ? input.readUnsignedByte()
                        : opcode in [250, 253] ? input.readUnsignedShort() : input.readInt()
                checkedSize(size)
                if (offset < 0 || offset > base.length || size > base.length - offset) {
                    throw new IllegalStateException('OptiFine GDIFF copy exceeds vanilla class')
                }
                result.write(base, (int) offset, size)
            }
            if (result.size() > 16 * 1024 * 1024) {
                throw new IllegalStateException('OptiFine patched class exceeds 16 MiB')
            }
        }
        if (input.available() != 0) {
            throw new IllegalStateException('OptiFine GDIFF has trailing bytes')
        }
        result.toByteArray()
    }

    private static void checkedSize(int size) {
        if (size < 0 || size > 16 * 1024 * 1024) {
            throw new IllegalStateException('OptiFine GDIFF invalid block size')
        }
    }

    private static void inspect(Map<String, byte[]> patched, byte[] capeUtils) {
        Map<String, Map<String, Integer>> player = methods(patched[PLAYER])
        Map<String, Map<String, Integer>> capeLayer = methods(patched[CAPE])
        Map<String, Map<String, Integer>> elytraLayer = methods(patched[ELYTRA])
        Map<String, Map<String, Integer>> utils = methods(capeUtils)
        exact(player, "m_108561_${LOCATION}", 'ARETURN', 3)
        exact(player, "m_108563_${LOCATION}", 'ARETURN', 1)
        exact(player, 'hasElytraCape()Z', 'IRETURN', 3)
        exact(player, "<init>(Lnet/minecraft/client/multiplayer/ClientLevel;Lcom/mojang/authlib/GameProfile;)V",
                "${CAPE_UTILS}.downloadCape${CAPE_ARGUMENT}", 1)
        exact(player, "m_108561_${LOCATION}", "${CAPE_UTILS}.reloadCape${CAPE_ARGUMENT}", 1)
        exact(utils, "downloadCape${CAPE_ARGUMENT}", 'METHOD_COUNT', 1)
        exact(utils, "reloadCape${CAPE_ARGUMENT}", 'METHOD_COUNT', 1)
        String render = 'm_6494_(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/player/AbstractClientPlayer;FFFFFF)V'
        String elytraRender = 'm_6494_(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/LivingEntity;FFFFFF)V'
        exact(capeLayer, render, "${PLAYER}.m_108561_${LOCATION}", 2)
        exact(elytraLayer, elytraRender, "${PLAYER}.hasElytraCape()Z", 1)
        exact(elytraLayer, elytraRender, "${PLAYER}.m_108561_${LOCATION}", 2)
    }

    private static void exact(Map<String, Map<String, Integer>> methods,
                              String method, String anchor, int expected) {
        int actual = methods[method]?.get(anchor) ?: 0
        if (actual != expected) {
            throw new IllegalStateException("OptiFine ABI ${method} ${anchor}: expected ${expected}, found ${actual}")
        }
    }

    private static Map<String, Map<String, Integer>> methods(byte[] bytes) {
        Map<String, Map<String, Integer>> found = [:]
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            MethodVisitor visitMethod(int access, String name, String descriptor,
                                      String signature, String[] exceptions) {
                String key = name + descriptor
                Map<String, Integer> counts = found.computeIfAbsent(key) { [:] }
                counts['METHOD_COUNT'] = 1
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    void visitInsn(int opcode) {
                        if (opcode == Opcodes.ARETURN || opcode == Opcodes.IRETURN) {
                            String anchor = opcode == Opcodes.ARETURN ? 'ARETURN' : 'IRETURN'
                            counts[anchor] = (counts[anchor] ?: 0) + 1
                        }
                    }

                    @Override
                    void visitMethodInsn(int opcode, String owner, String methodName,
                                         String methodDescriptor, boolean isInterface) {
                        String anchor = owner + '.' + methodName + methodDescriptor
                        counts[anchor] = (counts[anchor] ?: 0) + 1
                    }
                }
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES)
        found
    }

    private static byte[] read(ZipFile zip, String name) {
        def entry = zip.getEntry(name)
        if (entry == null) throw new IllegalStateException("Missing upstream entry ${name}")
        zip.getInputStream(entry).withCloseable { it.readAllBytes() }
    }

    private static void requireHash(File file, String expected) {
        String actual = digest(file.bytes, 'SHA-256')
        if (actual != expected) throw new IllegalStateException("Upstream SHA-256 mismatch: ${file.name}")
    }

    private static String digest(byte[] bytes, String algorithm) {
        MessageDigest.getInstance(algorithm).digest(bytes).encodeHex().toString()
    }
}

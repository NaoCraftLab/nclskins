package com.naocraftlab.skins.compat.client.resourcelocation.skinlookup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.vertex.PoseStack;
import com.naocraftlab.skins.client.CenteredPlayerPreviewGeometry;
import java.util.ArrayList;
import java.util.List;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class LivePreviewPitchTest {
    @Test
    void liveInventoryPassConsumesBothCenteredTranslationComponents() throws Exception {
        List<String> calls = new ArrayList<>();
        try (var stream = VanillaAppearancePreviewRenderer.class.getResourceAsStream(
                "VanillaAppearancePreviewRenderer.class")) {
            new ClassReader(stream).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                  String signature, String[] exceptions) {
                    if (!name.equals("render") || !descriptor.startsWith(
                            "(Lnet/minecraft/client/gui/GuiGraphics;")) return null;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String method,
                                                    String desc, boolean isInterface) {
                            calls.add(owner + "." + method + desc);
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        String geometry = "com/naocraftlab/skins/client/CenteredPlayerPreviewGeometry";
        String translation = geometry + "$EntityTranslation";
        int centered = calls.indexOf(geometry + ".centeredEntityTranslation(FF)L" + translation + ";");
        int y = calls.indexOf(translation + ".y()F");
        int z = calls.indexOf(translation + ".z()F");
        int inventory = calls.indexOf("net/minecraft/client/gui/screens/inventory/InventoryScreen"
                + ".renderEntityInInventory(Lnet/minecraft/client/gui/GuiGraphics;FFF"
                + "Lorg/joml/Vector3f;Lorg/joml/Quaternionf;Lorg/joml/Quaternionf;"
                + "Lnet/minecraft/world/entity/LivingEntity;)V");
        assertTrue(centered >= 0, "Live render must use pitch-aware center compensation");
        assertTrue(centered < y && y < z && z < inventory,
                "Both center components must reach the native inventory pass");
    }

    @Test
    void nativeInventoryTransformKeepsCenterFixedThroughPitchZoomAndYaw() {
        for (float height : new float[]{1.8F, 1.62F, 0.9F}) {
            float center = height / 2.0F + 0.0625F;
            for (float zoom : new float[]{0.68F, 1.0F, 2.0F}) {
                float scale = CenteredPlayerPreviewGeometry.fittedScale(240, zoom);
                for (int degrees = -30; degrees <= 30; degrees += 5) {
                    float pitch = (float) Math.toRadians(degrees);
                    var offset = CenteredPlayerPreviewGeometry.centeredEntityTranslation(height, pitch);
                    for (int yaw : new int[]{0, 90, 180, 270}) {
                        PoseStack pose = new PoseStack();
                        pose.translate(120.0F, 137.0F, 50.0F);
                        pose.scale(scale, scale, -scale);
                        pose.translate(0.0F, offset.y(), offset.z());
                        pose.mulPose(new Quaternionf().rotateZ((float) Math.PI)
                                .mul(new Quaternionf().rotateX(pitch)));
                        pose.mulPose(new Quaternionf().rotateY((float) Math.toRadians(yaw)));
                        Vector3f actual = pose.last().pose().transformPosition(new Vector3f(0, center, 0));
                        assertEquals(120.0F, actual.x, 0.0001F);
                        assertEquals(137.0F, actual.y, 0.0001F);
                        assertEquals(50.0F, actual.z, 0.0001F);
                    }
                }
            }
        }
    }
}

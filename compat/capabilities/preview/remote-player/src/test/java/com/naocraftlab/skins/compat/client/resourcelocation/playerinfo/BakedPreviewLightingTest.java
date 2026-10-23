package com.naocraftlab.skins.compat.client.resourcelocation.playerinfo;

import com.mojang.blaze3d.vertex.BufferVertexConsumer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.naocraftlab.skins.client.PlayerPreviewLighting;
import com.naocraftlab.skins.client.VanillaBackEquipmentTransform;
import com.naocraftlab.skins.client.VanillaPlayerModelTransform;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.core.Direction;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BakedPreviewLightingTest {
    private static final float EPSILON = 0.0001F;
    private static final float PACKING_ERROR = (float) (0.6 * 2 * Math.sqrt(3) / 127);
    private static final Class<?>[] OWNERS = {
        SimplePreviewRenderer.class, RemotePlayerPreviewRenderer.class
    };
    private static final ModelPart.Cube FACE = new ModelPart.Cube(
            0, 0, -4, -8, -4, 8, 8, 8, 0, 0, 0, false, 64, 64,
            Set.of(Direction.NORTH));

    @Test
    void productionPlayerCallbacksPreserveUnitFacesAndPackedPitchGradient() throws Exception {
        for (Class<?> owner : OWNERS) {
            var operations = playerOperations(owner);
            for (float scale : new float[]{3, 50, 200}) {
                for (float zoom : new float[]{0.68F, 1, 2}) {
                    float previous = -1;
                    for (int pitch = -30; pitch <= 30; pitch++) {
                        PoseStack pose = new PoseStack();
                        VanillaPlayerModelTransform.applyCentered(
                                pose, scale * zoom, 0, pitch, operations);
                        Vector3f expected = playerNormal(new Vector3f(0, 0, -1), 0, pitch);
                        float light = assertFace(pose, expected);
                        assertTrue(light + PACKING_ERROR >= previous);
                        previous = light;
                        assertPositions(pose, playerPositions(scale * zoom, 0, pitch));
                    }
                    float[] center = new float[3];
                    int i = 0;
                    for (float pitch : new float[]{-0.1F, 0, 0.1F}) {
                        PoseStack pose = new PoseStack();
                        VanillaPlayerModelTransform.applyCentered(
                                pose, scale * zoom, 0, pitch, operations);
                        center[i++] = assertFace(
                                pose, playerNormal(new Vector3f(0, 0, -1), 0, pitch));
                    }
                    assertTrue(Math.abs(center[0] - center[1]) <= PACKING_ERROR);
                    assertTrue(Math.abs(center[2] - center[1]) <= PACKING_ERROR);
                }
            }
        }
    }

    @Test
    void yawAndAttachedEquipmentPreserveDirectionsAndPositions() throws Exception {
        for (Class<?> owner : OWNERS) {
            for (float yaw : new float[]{-135, -45, 0, 45, 135}) {
                for (float pitch : new float[]{-30, -0.1F, 0, 0.1F, 30}) {
                    PoseStack pose = new PoseStack();
                    VanillaPlayerModelTransform.applyCentered(
                            pose, 50, yaw, pitch, playerOperations(owner));
                    assertFace(pose, playerNormal(new Vector3f(0, 0, -1), yaw, pitch));
                    assertPositions(pose, playerPositions(50, yaw, pitch));
                    var equipment = equipmentOperations(owner);
                    pose.pushPose();
                    VanillaBackEquipmentTransform.applyCapeAttachment(pose, equipment);
                    float tilt = (float) Math.toRadians(6);
                    Vector3f capeNormal = new Vector3f(0,
                            -(float) Math.sin(tilt), (float) Math.cos(tilt));
                    assertFace(pose, playerNormal(capeNormal, yaw, pitch));
                    assertPositions(pose, playerPositions(50, yaw, pitch)
                            .translate(0, 0, 0.125F).rotateX(tilt).rotateY((float) Math.PI));
                    pose.popPose();
                    pose.pushPose();
                    VanillaBackEquipmentTransform.applyElytraAttachment(pose, equipment);
                    assertFace(pose, playerNormal(new Vector3f(0, 0, -1), yaw, pitch));
                    assertPositions(pose, playerPositions(50, yaw, pitch)
                            .translate(0, 0, 0.125F));
                    pose.popPose();
                    assertFace(pose, playerNormal(new Vector3f(0, 0, -1), yaw, pitch));
                }
            }
        }
    }

    @Test
    void standaloneEquipmentUsesTheSameCorrectedScale() throws Exception {
        for (Class<?> owner : OWNERS) {
            for (float scale : new float[]{3, 50, 200}) {
                PoseStack pose = new PoseStack();
                VanillaBackEquipmentTransform.applyStandalone(
                        pose, scale, equipmentOperations(owner));
                assertFace(pose, new Vector3f(0, 0, -1));
                assertPositions(pose, playerPositions(scale, 180, 0)
                        .translate(0, -0.125F, 0));
            }
        }
    }

    private static float assertFace(PoseStack pose, Vector3f expected) {
        FaceVertices vertices = new FaceVertices();
        FACE.compile(pose.last(), vertices, 0, 0, 1, 1, 1, 1);
        assertEquals(4, vertices.normals.size());
        for (Vector3f raw : vertices.normals) {
            assertTrue(Float.isFinite(raw.x) && Float.isFinite(raw.y) && Float.isFinite(raw.z));
            assertEquals(1, raw.length(), EPSILON);
            assertEquals(expected.x, raw.x, EPSILON);
            assertEquals(expected.y, raw.y, EPSILON);
            assertEquals(expected.z, raw.z, EPSILON);
            Vector3f packed = new Vector3f(
                    BufferVertexConsumer.normalIntValue(raw.x) / 127.0F,
                    BufferVertexConsumer.normalIntValue(raw.y) / 127.0F,
                    BufferVertexConsumer.normalIntValue(raw.z) / 127.0F);
            assertEquals(expected.x, packed.x, 1.0F / 127 + EPSILON);
            assertEquals(expected.y, packed.y, 1.0F / 127 + EPSILON);
            assertEquals(expected.z, packed.z, 1.0F / 127 + EPSILON);
            assertEquals(shaderLight(expected), shaderLight(packed), PACKING_ERROR);
        }
        Vector3f raw = vertices.normals.get(0);
        return shaderLight(new Vector3f(
                BufferVertexConsumer.normalIntValue(raw.x) / 127.0F,
                BufferVertexConsumer.normalIntValue(raw.y) / 127.0F,
                BufferVertexConsumer.normalIntValue(raw.z) / 127.0F));
    }

    private static float shaderLight(Vector3f normal) {
        PlayerPreviewLighting.Rig rig = PlayerPreviewLighting.centeredFront();
        return Math.min(1, 0.4F + 0.6F * (
                Math.max(0, dot(rig.primary(), normal)) + Math.max(0, dot(rig.fill(), normal))));
    }

    private static float dot(PlayerPreviewLighting.Direction light, Vector3f normal) {
        return light.x() * normal.x + light.y() * normal.y + light.z() * normal.z;
    }

    private static Vector3f playerNormal(Vector3f face, float yaw, float pitch) {
        double y = Math.toRadians(yaw);
        double p = Math.toRadians(pitch);
        double x = -face.x * Math.cos(y) + face.z * Math.sin(y);
        double z = face.x * Math.sin(y) + face.z * Math.cos(y);
        return new Vector3f((float) -x,
                (float) (face.y * Math.cos(p) + z * Math.sin(p)),
                (float) (face.y * Math.sin(p) - z * Math.cos(p)));
    }

    private static Matrix4f playerPositions(float scale, float yaw, float pitch) {
        return new Matrix4f().scale(scale, scale, -scale)
                .rotateZ((float) Math.PI).rotateX((float) Math.toRadians(pitch))
                .rotateY((float) Math.toRadians(yaw)).scale(-1, -1, 1)
                .translate(0, -0.5F, 0);
    }

    private static void assertPositions(PoseStack pose, Matrix4f expected) {
        for (Vector3f vertex : new Vector3f[]{
                new Vector3f(0, -0.5F, 0), new Vector3f(0, 1.5F, 0),
                new Vector3f(0, 0.5F, 0), new Vector3f(0.25F, 0.75F, 0.125F)}) {
            Vector3f actual = pose.last().pose().transformPosition(new Vector3f(vertex));
            Vector3f reference = expected.transformPosition(new Vector3f(vertex));
            assertEquals(reference.x, actual.x, 0.001F);
            assertEquals(reference.y, actual.y, 0.001F);
            assertEquals(reference.z, actual.z, 0.001F);
        }
    }

    @SuppressWarnings("unchecked")
    private static VanillaPlayerModelTransform.Operations<PoseStack> playerOperations(
            Class<?> owner) throws Exception {
        return (VanillaPlayerModelTransform.Operations<PoseStack>) operations(owner, "POSE_OPERATIONS");
    }

    @SuppressWarnings("unchecked")
    private static VanillaBackEquipmentTransform.Operations<PoseStack> equipmentOperations(
            Class<?> owner) throws Exception {
        return (VanillaBackEquipmentTransform.Operations<PoseStack>) operations(
                owner, "BACK_EQUIPMENT_OPERATIONS");
    }

    private static Object operations(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private static final class FaceVertices implements VertexConsumer {
        final List<Vector3f> normals = new ArrayList<>();

        @Override public VertexConsumer vertex(double x, double y, double z) { return this; }
        @Override public VertexConsumer color(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer uv(float u, float v) { return this; }
        @Override public VertexConsumer overlayCoords(int u, int v) { return this; }
        @Override public VertexConsumer uv2(int u, int v) { return this; }
        @Override public VertexConsumer normal(float x, float y, float z) {
            normals.add(new Vector3f(x, y, z));
            return this;
        }
        @Override public void endVertex() { }
        @Override public void defaultColor(int r, int g, int b, int a) { }
        @Override public void unsetDefaultColor() { }
    }
}

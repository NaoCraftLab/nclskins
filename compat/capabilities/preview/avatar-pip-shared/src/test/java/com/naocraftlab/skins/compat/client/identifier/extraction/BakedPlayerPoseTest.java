package com.naocraftlab.skins.compat.client.identifier.extraction;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.naocraftlab.skins.client.OuterLayerPart;
import com.naocraftlab.skins.client.OuterLayerVisibility;
import com.naocraftlab.skins.client.tck.BakedPlayerPoseAssertions;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.core.Direction;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BakedPlayerPoseTest {
    @Test
    void nativePitchKeepsModelNormalsUnitAndLightingContinuousAtEveryScale() {
        ModelPart.Cube face = new ModelPart.Cube(
                0, 0, -4, -8, -4, 8, 8, 8, 0, 0, 0, false, 64, 64,
                Set.of(Direction.NORTH));
        for (float scale : new float[]{3, 50, 200}) {
            float previous = -1;
            for (int step = -300; step <= 300; step++) {
                float pitch = step / 10.0F;
                PoseStack pose = new PoseStack();
                pose.scale(scale, scale, scale);
                BakedPlayerPose.applyPitch(pose, pitch);
                List<Vector3f> normals = new ArrayList<>();
                VertexConsumer consumer = (VertexConsumer) Proxy.newProxyInstance(
                        VertexConsumer.class.getClassLoader(), new Class<?>[]{VertexConsumer.class},
                        (proxy, method, args) -> {
                            if (method.isDefault()) {
                                return InvocationHandler.invokeDefault(proxy, method, args);
                            }
                            if (method.getName().equals("setNormal")) {
                                normals.add(new Vector3f((float) args[0], (float) args[1], (float) args[2]));
                            }
                            return proxy;
                        });
                face.compile(pose.last(), consumer, 0, 0, -1);
                assertEquals(4, normals.size());
                double radians = Math.toRadians(pitch);
                for (Vector3f normal : normals) {
                    assertEquals(1, normal.length(), 0.0001F);
                    assertEquals(0, normal.x, 0.0001F);
                    assertEquals(-Math.sin(radians), normal.y, 0.0001F);
                    assertEquals(-Math.cos(radians), normal.z, 0.0001F);
                }
                float light = Math.min(1, 0.4F + 0.6F * Math.max(0,
                        normals.get(0).dot(new Vector3f(0.2F, -1, -1).normalize())));
                if (previous >= 0) assertTrue(Math.abs(light - previous) < 0.002F);
                previous = light;
                Vector3f point = new Vector3f(0.25F, 0.5F, -0.25F);
                Vector3f actual = pose.last().pose().transformPosition(new Vector3f(point));
                assertEquals(scale * point.x, actual.x, 0.0001F);
                assertEquals(scale * (point.y * Math.cos(radians) + point.z * Math.sin(radians)),
                        actual.y, 0.0001F);
                assertEquals(scale * (-point.y * Math.sin(radians) + point.z * Math.cos(radians)),
                        actual.z, 0.0001F);
            }
        }
    }

    @Test
    void bakedNativeMeshHasNeutralAlignedLayers() throws Exception {
        var configure = BakedPlayerPose.class.getDeclaredMethod("configureOuterLayer",
                ModelPart.class, OuterLayerVisibility.class);
        configure.setAccessible(true);
        String[] names = {"head", "body", "left_arm", "right_arm", "left_leg", "right_leg"};
        String[] overlays = {"hat", "jacket", "left_sleeve", "right_sleeve", "left_pants", "right_pants"};
        OuterLayerPart[] flags = {OuterLayerPart.HEAD, OuterLayerPart.BODY,
                OuterLayerPart.LEFT_ARM, OuterLayerPart.RIGHT_ARM,
                OuterLayerPart.LEFT_LEG, OuterLayerPart.RIGHT_LEG};
        for (boolean slim : new boolean[]{false, true}) {
            ModelPart root = LayerDefinition.create(PlayerModel.createMesh(CubeDeformation.NONE, slim),
                    64, 64).bakeRoot();
            for (int mask = 0; mask < 64; mask++) {
                OuterLayerVisibility visible = OuterLayerVisibility.noneVisible();
                for (OuterLayerPart flag : flags) {
                    visible = visible.with(flag, (mask & (1 << flag.ordinal())) != 0);
                }
                configure.invoke(null, root, visible);
                for (int i = 0; i < names.length; i++) {
                    ModelPart base = root.getChild(names[i]);
                    ModelPart overlay = base.getChild(overlays[i]);
                    float[] basePose = transform(base);
                    float[] overlayPose = transform(overlay);
                    for (int j = 0; j < basePose.length; j++) overlayPose[j] += basePose[j];
                    BakedPlayerPoseAssertions.neutral(basePose, overlayPose,
                            overlay.visible, visible.visible(flags[i]));
                }
            }
        }
    }

    private static float[] transform(ModelPart part) {
        return new float[]{part.x, part.y, part.z, part.xRot, part.yRot, part.zRot};
    }
}

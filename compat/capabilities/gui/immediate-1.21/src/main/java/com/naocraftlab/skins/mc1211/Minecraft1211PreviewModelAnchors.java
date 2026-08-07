package com.naocraftlab.skins.mc1211;

import com.naocraftlab.skins.mc1211.mixin.PlayerModelPreviewAccessor;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;

public final class Minecraft1211PreviewModelAnchors implements AutoCloseable {
    private static final ThreadLocal<Minecraft1211PreviewModelAnchors> ACTIVE = new ThreadLocal<>();

    private final Map<ModelPart, ModelPart.Cube> anchors = new IdentityHashMap<>();

    private Minecraft1211PreviewModelAnchors(EntityModel<?> model) {
        if (model instanceof PlayerModel<?> player) {
            boolean slim = ((PlayerModelPreviewAccessor) player).nclskins$isSlim();
            anchors.put(player.head, cube(-4.0F, -8.0F, -4.0F, 8.0F, 8.0F, 8.0F));
            anchors.put(player.body, cube(-4.0F, 0.0F, -2.0F, 8.0F, 12.0F, 4.0F));
            anchors.put(player.rightArm, cube(slim ? -2.0F : -3.0F, -2.0F, -2.0F,
                    slim ? 3.0F : 4.0F, 12.0F, 4.0F));
            anchors.put(player.leftArm, cube(-1.0F, -2.0F, -2.0F,
                    slim ? 3.0F : 4.0F, 12.0F, 4.0F));
            anchors.put(player.rightLeg, cube(-2.0F, 0.0F, -2.0F, 4.0F, 12.0F, 4.0F));
            anchors.put(player.leftLeg, cube(-2.0F, 0.0F, -2.0F, 4.0F, 12.0F, 4.0F));
        }
    }

    public static Minecraft1211PreviewModelAnchors open(EntityModel<?> model) {
        if (ACTIVE.get() != null) {
            throw new IllegalStateException("Player model anchors are already active");
        }
        Minecraft1211PreviewModelAnchors scope = new Minecraft1211PreviewModelAnchors(model);
        ACTIVE.set(scope);
        return scope;
    }

    public static ModelPart.Cube anchor(ModelPart part) {
        Minecraft1211PreviewModelAnchors scope = ACTIVE.get();
        return scope == null ? null : scope.anchors.get(part);
    }

    private static ModelPart.Cube cube(float x, float y, float z, float width, float height, float depth) {
        return new ModelPart.Cube(
                0, 0, x, y, z, width, height, depth,
                0.0F, 0.0F, 0.0F, false, 64.0F, 64.0F, Set.of());
    }

    @Override
    public void close() {
        if (ACTIVE.get() != this) {
            throw new IllegalStateException("Preview model anchors closed out of order");
        }
        ACTIVE.remove();
    }
}

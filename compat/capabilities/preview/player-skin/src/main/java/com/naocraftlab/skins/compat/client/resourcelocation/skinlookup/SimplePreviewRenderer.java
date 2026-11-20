package com.naocraftlab.skins.compat.client.resourcelocation.skinlookup;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.naocraftlab.skins.compat.client.cape.CapeTranslucentFoilMaterial;
import com.naocraftlab.skins.client.BackEquipmentPreviewRenderer;
import com.naocraftlab.skins.client.CenteredPlayerPreviewGeometry;
import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.client.VanillaBackEquipmentTransform;
import com.naocraftlab.skins.client.VanillaPlayerModelTransform;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.ElytraModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Quaternionf;


public final class SimplePreviewRenderer
        implements PreviewRenderer<GuiGraphics>, BackEquipmentPreviewRenderer<GuiGraphics> {
    private static final float GUI_DEPTH = 120.0F;
    private static final VanillaPlayerModelTransform.Operations<PoseStack> POSE_OPERATIONS =
            new VanillaPlayerModelTransform.Operations<>() {
                @Override
                public void scale(PoseStack pose, float x, float y, float z) {
                    pose.scale(x, y, z);
                }

                @Override
                public void rotateZThenX(PoseStack pose, float zRadians, float xRadians) {
                    pose.mulPose(new Quaternionf().rotateZ(zRadians).rotateX(xRadians));
                }

                @Override
                public void rotateY(PoseStack pose, float radians) {
                    pose.mulPose(new Quaternionf().rotateY(radians));
                }

                @Override
                public void translate(PoseStack pose, float x, float y, float z) {
                    pose.translate(x, y, z);
                }
            };
    private static final VanillaBackEquipmentTransform.Operations<PoseStack>
            BACK_EQUIPMENT_OPERATIONS = new VanillaBackEquipmentTransform.Operations<>() {
                @Override
                public void scale(PoseStack pose, float x, float y, float z) {
                    pose.scale(x, y, z);
                }

                @Override
                public void rotateZThenX(
                        PoseStack pose, float zRadians, float xRadians) {
                    pose.mulPose(new Quaternionf().rotateZ(zRadians).rotateX(xRadians));
                }

                @Override
                public void rotateX(PoseStack pose, float radians) {
                    pose.mulPose(new Quaternionf().rotateX(radians));
                }

                @Override
                public void rotateY(PoseStack pose, float radians) {
                    pose.mulPose(new Quaternionf().rotateY(radians));
                }

                @Override
                public void translate(PoseStack pose, float x, float y, float z) {
                    pose.translate(x, y, z);
                }
            };

    private final PlayerModel<?> classic;
    private final PlayerModel<?> slim;
    private final ModelPart classicCloak;
    private final ModelPart slimCloak;
    private final ModelPart elytraRoot;
    private final ElytraModel<?> elytra;

    public SimplePreviewRenderer(Minecraft minecraft) {
        Objects.requireNonNull(minecraft, "minecraft");
        VanillaPreviewModels models =
                VanillaPreviewModels.instance();
        classic = models.classic;
        slim = models.slim;
        classicCloak = models.classicCloak;
        slimCloak = models.slimCloak;
        elytraRoot = models.elytraRoot;
        elytra = models.elytra;
    }

    private Object imageSource;
    @Override public void prepareImage(Object source, long startedNanos, boolean inputsReady) {
        prepareImage(source, startedNanos);
        imageSession.imagePrerequisite(inputsReady, startedNanos);
    }

    @Override public void prepareImage(Object source, long startedNanos) {
        java.util.Objects.requireNonNull(source);
        if (!java.util.Objects.equals(imageSource, source) && displayedImageSession != null) {
            if (imageSession != displayedImageSession) imageSession.invalidateDisplayedBody();
            imageSession = imageSession.nextImageSession();
        }
        imageSource = java.util.Objects.requireNonNull(source);
        imageSession.beginImage(source, startedNanos);
    }

    private com.naocraftlab.skins.client.EditorPreviewSession imageSession =
            new com.naocraftlab.skins.client.EditorPreviewSession();

    private Object implicitImageSource;
    private PreviewRequest displayedImage;
    private com.naocraftlab.skins.client.EditorPreviewSession displayedImageSession;
    private java.util.function.BooleanSupplier displayedImageLifetime;

    @Override
    public void render(GuiGraphics graphics, PreviewRequest request) {
        var appearance = request.appearance();
        String skin = appearance.skin().location();
        var registration = com.naocraftlab.skins.client.NativePlayerSkinLifecycle.registration(skin);
        var menuCape = imageSource == null && request.intent() == PreviewIntent.CURRENT_APPEARANCE
                ? appearance.cape().orElse(null) : null;
        var capeOwner = menuCape == null ? null : appearance.capeAssetOwner();
        if (capeOwner != null && !capeOwner.ownsFrame(menuCape.location())) return;
        if (imageSource == null) {
            var identity = java.util.List.of(capeOwner == null ? appearance : new MenuCapeImageIdentity(appearance), registration);
            if (!java.util.Objects.equals(implicitImageSource, identity)) {
                if (displayedImageSession != null) {
                    if (imageSession != displayedImageSession) imageSession.invalidateDisplayedBody();
                    imageSession = imageSession.nextImageSession();
                }
                implicitImageSource = identity;
            }
            imageSession.beginImage(identity);
        }
        if (!imageSession.imageVisible() && !imageSession.imageSpinner() && imageSession.imagePreparing()
                && displayedImage != null && displayedImageSession.imageVisible() && displayedImageLifetime.getAsBoolean()) {
            try { renderImage(graphics, displayedImage); }
            catch (RuntimeException | Error failure) {
                displayedImageSession.invalidateDisplayedBody();
                displayedImage = null;
                imageSession.imagePreparation(() -> true).accept(new com.naocraftlab.skins.client.PreviewModelFrames.Body(BodyStatus.FAILED, () -> true));
                throw failure;
            }
        }
        if (!com.naocraftlab.skins.client.NativePlayerSkinLifecycle.isReady(skin)) {
            if (registration.filter(value -> value.state() != com.naocraftlab.skins.client.NativePlayerSkinLifecycle.State.PENDING).isPresent())
                imageSession.imagePreparation(() -> true).accept(new com.naocraftlab.skins.client.PreviewModelFrames.Body(BodyStatus.FAILED, () -> true));
            renderLoading(graphics, imageSession, request);
            return;
        }
        renderLoading(graphics, imageSession, request);
        if (capeOwner != null && !capeOwner.ownsFrame(menuCape.location())) return;
        if (imageSession.imageVisible()) {
            renderImage(graphics, request);
            {
                var client = Minecraft.getInstance();
                var world = client.level;
                var player = client.player;
                var previous = displayedImageSession;
                displayedImage = request;
                displayedImageSession = imageSession;
                displayedImageLifetime = () -> client.level == world && client.player == player
                        && com.naocraftlab.skins.client.NativePlayerSkinLifecycle.registration(skin).equals(registration)
                        && (request.appearance().cape().isEmpty() || request.appearance().capeMode() == CapeMode.OFF
                                || request.appearance().capeMode() == CapeMode.ELYTRA && !request.appearance().capeHasElytra()
                                || request.appearance().capeValidity().isValid());
                if (previous != null && previous != imageSession) previous.invalidateDisplayedBody();
            }
            return;
        }
        if (!imageSession.imagePreparing()) return;
        var completion = imageSession.imagePreparation(
                () -> com.naocraftlab.skins.client.NativePlayerSkinLifecycle.registration(skin).equals(registration)
                        && com.naocraftlab.skins.client.NativePlayerSkinLifecycle.isReady(skin));
        try {
            renderImage(graphics, request.hidden());
            if (capeOwner != null && !capeOwner.ownsFrame(menuCape.location())) return;
            completion.accept(new com.naocraftlab.skins.client.PreviewModelFrames.Body(BodyStatus.ORDINARY,
                    () -> com.naocraftlab.skins.client.NativePlayerSkinLifecycle.registration(skin).equals(registration)
                        && com.naocraftlab.skins.client.NativePlayerSkinLifecycle.isReady(skin)));
        } catch (RuntimeException | Error failure) {
            completion.accept(new com.naocraftlab.skins.client.PreviewModelFrames.Body(BodyStatus.FAILED, () -> true));
            throw failure;
        }
    }

    private record MenuCapeImageIdentity(
            com.naocraftlab.skins.client.TextureRegistry.TextureHandle skin, SkinModel model,
            com.naocraftlab.skins.client.CapeAssetOwner capeOwner, CapeMode capeMode,
            com.naocraftlab.skins.client.OuterLayerVisibility outerLayerVisibility, boolean capeHasElytra,
            boolean capeGlint, boolean nativeAccessories, com.naocraftlab.skins.client.TextureUseValidity capeValidity) {
        MenuCapeImageIdentity(PreviewAppearance appearance) {
            this(appearance.skin(), appearance.model(), appearance.capeAssetOwner(), appearance.capeMode(),
                    appearance.outerLayerVisibility(), appearance.capeHasElytra(), appearance.capeGlint(),
                    appearance.nativeAccessories(), appearance.capeValidity());
        }
    }

    static void renderLoading(GuiGraphics graphics, com.naocraftlab.skins.client.EditorPreviewSession session, PreviewRequest request) {
        if (!session.imageSpinner()) return;
        var frame = com.naocraftlab.skins.client.PreviewLoading.frame((long) session.imageFrame() * 100_000_000L,
                request.left(), request.top(), request.width(), request.height(), 32);
        graphics.blit(ResourceLocation.tryParse(frame.resource()), frame.left(), frame.top(), frame.size(), frame.size(),
                (float) frame.u(), (float) frame.v(), frame.width(), frame.height(), frame.textureWidth(), frame.textureHeight());
    }

    private void renderImage(GuiGraphics graphics, PreviewRequest request) {
        Objects.requireNonNull(graphics, "graphics");
        Objects.requireNonNull(request, "request");
        ResourceLocation skin = parseTexture(request.appearance().skin().location());
        ResourceLocation cape = request.appearance().cape()
                .map(handle -> request.appearance().capeMode() == CapeMode.ELYTRA && !request.appearance().capeHasElytra()
                        ? ResourceLocation.withDefaultNamespace("textures/entity/elytra.png") : parseTexture(handle.location()))
                .orElse(null);
        boolean slimModel = request.appearance().model() == SkinModel.SLIM;
        PlayerModel<?> player = slimModel ? slim : classic;
        ModelPart cloak = slimModel ? slimCloak : classicCloak;

        BakedPlayerPose.configure(player, request.appearance().outerLayerVisibility());
        resetCloaks();
        PoseStack pose = graphics.pose();
        pose.pushPose();
        try {
            CenteredPlayerPreviewGeometry.Layout layout =
                    CenteredPlayerPreviewGeometry.fit(
                            request.left(),
                            request.top(),
                            request.width(),
                            request.height(),
                            request.scale());
            pose.translate(
                    layout.centerX(),
                    layout.centerY(),
                    GUI_DEPTH);
            VanillaPlayerModelTransform.applyCentered(
                    pose,
                    layout.scale(),
                    request.yawDegrees(),
                    request.pitchDegrees(),
                    POSE_OPERATIONS);

            Lighting.setupForEntityInInventory();
            MultiBufferSource.BufferSource buffers = graphics.bufferSource();

            VertexConsumer skinBuffer = buffers.getBuffer(RenderType.entityTranslucent(skin));
            player.renderToBuffer(
                    pose,
                    skinBuffer,
                    LightTexture.FULL_BRIGHT,
                    OverlayTexture.NO_OVERLAY,
                    0xFFFFFFFF);
            if (cape != null) {
                renderBackEquipment(
                        pose,
                        buffers,
                        cloak,
                        request.appearance().capeMode(),
                        request.appearance().capeGlint(),
                        cape,
                        false);
            }
            graphics.flush();
        } finally {
            pose.popPose();
            Lighting.setupFor3DItems();
        }
    }

    @Override
    public void render(GuiGraphics graphics, BackEquipmentPreviewRenderer.Request request) {
        if (imageSource == null) imageSession.beginImage(java.util.List.of(request.texture(), request.mode(), request.capeHasElytra(), request.fitPolicy()));
        if (!imageSession.imageVisible() && !imageSession.imagePreparing()) return;
        if (imageSession.imageSpinner()) {
            var frame = com.naocraftlab.skins.client.PreviewLoading.frame((long) imageSession.imageFrame() * 100_000_000L,
                    request.left(), request.top(), request.width(), request.height(), 32);
            graphics.blit(ResourceLocation.tryParse(frame.resource()), frame.left(), frame.top(), frame.size(), frame.size(),
                    (float) frame.u(), (float) frame.v(), frame.width(), frame.height(), frame.textureWidth(), frame.textureHeight());
        }
        if (imageSession.imageVisible()) { renderEquipment(graphics, request); return; }
        var hidden = new BackEquipmentPreviewRenderer.Request(request.texture(), request.mode(), -request.width() - 1,
                request.top(), request.width(), request.height(), request.capeHasElytra(), request.fitPolicy());
        var completion = imageSession.imagePreparation(() -> true);
        try {
            renderEquipment(graphics, hidden);
            completion.accept(new com.naocraftlab.skins.client.PreviewModelFrames.Body(BodyStatus.ORDINARY, () -> true));
        } catch (RuntimeException | Error failure) {
            completion.accept(new com.naocraftlab.skins.client.PreviewModelFrames.Body(BodyStatus.FAILED, () -> true));
            throw failure;
        }
    }

    private void renderEquipment(GuiGraphics graphics, BackEquipmentPreviewRenderer.Request request) {
        Objects.requireNonNull(graphics, "graphics");
        Objects.requireNonNull(request, "request");
        PoseStack pose = graphics.pose();
        pose.pushPose();
        try {
            float scale = VanillaBackEquipmentTransform.fitScale(
                    request.width(), request.height(), request.mode(), request.fitPolicy());
            pose.translate(
                    request.left() + request.width() / 2.0F,
                    request.top() + request.height() / 2.0F
                            + scale * VanillaBackEquipmentTransform.modelOffsetY(request.mode(), request.fitPolicy()),
                    GUI_DEPTH);
            VanillaBackEquipmentTransform.applyStandalone(
                    pose, scale, BACK_EQUIPMENT_OPERATIONS);

            Lighting.setupForEntityInInventory();
            MultiBufferSource.BufferSource buffers = graphics.bufferSource();
            ResourceLocation texture = parseTexture(request.mode() == BackEquipmentPreviewRenderer.Mode.ELYTRA && !request.capeHasElytra()
                        ? "minecraft:textures/entity/elytra.png" : request.texture().location());
            renderBackEquipment(
                    pose,
                    buffers,
                    classicCloak,
                    request.mode() == BackEquipmentPreviewRenderer.Mode.CAPE
                            ? CapeMode.CAPE
                            : CapeMode.ELYTRA,
                    false,
                    texture,
                    true);
            graphics.flush();
        } finally {
            pose.popPose();
            Lighting.setupFor3DItems();
        }
    }

    private void renderBackEquipment(
            PoseStack pose,
            MultiBufferSource.BufferSource buffers,
            ModelPart cloak,
            CapeMode capeMode,
            boolean capeGlint,
            ResourceLocation texture,
            boolean standalone) {
        if (capeMode == CapeMode.CAPE) {
            pose.pushPose();
            try {
                if (standalone) {
                    VanillaBackEquipmentTransform.applyUvCorrectedStandaloneCapeAttachment(
                            pose, BACK_EQUIPMENT_OPERATIONS);
                } else {
                    VanillaBackEquipmentTransform.applyCapeAttachment(
                            pose, BACK_EQUIPMENT_OPERATIONS);
                }
                cloak.resetPose();
                cloak.visible = true;
                try {
                    VertexConsumer capeBuffer = CapeTranslucentFoilMaterial.buffer(buffers, texture, capeGlint,
                            RenderType.entityTranslucent(texture));
                    cloak.render(
                            pose,
                            capeBuffer,
                            LightTexture.FULL_BRIGHT,
                            OverlayTexture.NO_OVERLAY);
                } finally {
                    cloak.visible = false;
                }
            } finally {
                pose.popPose();
            }
        } else if (capeMode == CapeMode.ELYTRA) {
            pose.pushPose();
            try {
                VanillaBackEquipmentTransform.applyElytraAttachment(
                        pose, BACK_EQUIPMENT_OPERATIONS);
                configureElytra();
                elytra.renderToBuffer(
                        pose,
                        buffers.getBuffer(RenderType.armorCutoutNoCull(texture)),
                        LightTexture.FULL_BRIGHT,
                        OverlayTexture.NO_OVERLAY,
                        0xFFFFFFFF);
            } finally {
                pose.popPose();
            }
        }
    }

    private void configureElytra() {
        elytraRoot.getAllParts().forEach(ModelPart::resetPose);
        elytra.young = false;
        elytra.riding = false;
        elytra.attackTime = 0.0F;
    }

    private void resetCloaks() {
        classicCloak.resetPose();
        classicCloak.visible = false;
        slimCloak.resetPose();
        slimCloak.visible = false;
    }

    private static ResourceLocation parseTexture(String value) {
        ResourceLocation location = ResourceLocation.tryParse(value);
        if (location == null) {
            throw new IllegalArgumentException("Invalid preview texture location");
        }
        return location;
    }
}

package com.naocraftlab.skins.buildlogic.probe;

import com.naocraftlab.skins.client.*;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.diagnostics.DiagnosticSinks;
import com.naocraftlab.skins.runtime.*;
import java.nio.file.*;
import java.util.*;

public final class SubmissionModelFitDemandProbe {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        var image = new java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) image.setRGB(x, y, 0xffaaccbb);
        var bytes = new java.io.ByteArrayOutputStream();
        check(javax.imageio.ImageIO.write(image, "PNG", bytes), "synthetic ordinary PNG");
        byte[] png = bytes.toByteArray();
        var appearance = new PreviewRenderer.PreviewAppearance(new TextureRegistry.TextureHandle("fixture:demand", 64, 64),
                SkinModel.CLASSIC, Optional.empty(), PreviewRenderer.CapeMode.OFF, OuterLayerVisibility.allVisible(), false, false);
        try {
            for (String surface : List.of("gallery", "editor", "providers")) {
                try (var fixture = GalleryNativePassFixture.numeric(appearance, png, Path.of(args[0], surface))) {
                    if (surface.equals("editor")) {
                        var selected = fixture.runtime.snapshot().selectedPresetId().orElseThrow();
                        fixture.runtime.dispatchWidget("gallery.preset." + selected + ".more");
                        fixture.runtime.dispatchWidget("gallery.preset." + selected + ".edit");
                    } else if (surface.equals("providers")) fixture.runtime.dispatchWidget("gallery.providers");
                    fixture.drainWorkers();
                    var view = fixture.runtime.view(854, 480, 0, 0);
                    check(view.screenId().equals(surface.equals("editor") ? "preset_editor" : surface), "actual selected surface " + surface + ": " + view.screenId());
                    var boundary = new CpmOwnedPreviewDispatchProbe.RuntimeBoundary(fixture.runtime, null, SkinVariant.CLASSIC, png);
                    boundary.pending = true;
                    var renderer = new CpmOwnedPreviewDispatchProbe.RenderTerminal();
                    renderer.boundary = boundary;
                    var registry = new CpmOwnedPreviewDispatchProbe.Registry();
                    Class<?> host = CpmPreviewScreenSlice.load("com.naocraftlab.skins.compat.client.identifier.submission.NclSkinsScreen");
                    Object screen = host.getConstructor().newInstance();
                    try (var skins = new PreviewAssetCache<Object>(registry, TextureRegistry.TextureKind.PLAYER_SKIN, DiagnosticSinks.discarding());
                         var capes = new PreviewAssetCache<Object>(registry, TextureRegistry.TextureKind.IMAGE, DiagnosticSinks.discarding())) {
                        for (var field : host.getDeclaredFields()) {
                            Object value = switch (field.getName()) {
                                case "runtime" -> boundary;
                                case "skinTextures" -> skins;
                                case "capeTextures" -> capes;
                                case "editorRenderer" -> renderer;
                                case "textureRegistry" -> registry;
                                default -> field.getType() == Map.class ? new HashMap<>() : null;
                            };
                            if (value != null) { field.setAccessible(true); field.set(screen, value); }
                        }
                        var synchronize = host.getDeclaredMethod("synchronizePreviewAssets", ViewSpec.class);
                        var render = host.getDeclaredMethod("renderPreviews", CpmOwnedPreviewDispatchProbe.Graphics.class, ViewSpec.class);
                        synchronize.setAccessible(true);
                        render.setAccessible(true);
                        synchronize.invoke(screen, view);
                        render.invoke(screen, new CpmOwnedPreviewDispatchProbe.Graphics(), view);
                        check(renderer.fitApplicabilityChecks == 1, "actual compiled " + surface + " must observe foreground numeric demand before texture readiness");
                        check(boundary.bodyRegistrations > 0 && boundary.lastRegistration != null && boundary.releases == 0,
                                "pending texture retains exact BODY observer " + surface);
                        check(renderer.preparedDemand != null && !renderer.preparedDemand.equals(renderer.preparedSource),
                                "actual compiled host forwards independent semantic demand " + surface);
                        check(renderer.fitPreparations == 0, "pending own texture cannot request native measurement " + surface);
                        for (int i = 0; i < 100; i++) render.invoke(screen, new CpmOwnedPreviewDispatchProbe.Graphics(), view);
                        check(renderer.fitApplicabilityChecks == 1 && renderer.fitPreparations == 0,
                                "settled pending frames do not restart demand or native preparation " + surface);
                        check(boundary.releases == 0, "settled pending frames retain BODY observer " + surface);
                        check(fixture.numericLoads == 0 && fixture.numericCreates == 0, "no numeric disk work before own texture " + surface);
                        check(!boundary.pendingSkinLoads.isEmpty(), "actual texture producer held " + surface);
                        for (var pending : List.copyOf(boundary.pendingSkinLoads)) pending.complete(Optional.of(png));
                        render.invoke(screen, new CpmOwnedPreviewDispatchProbe.Graphics(), view);
                        check(renderer.fitPreparations == 1, "same opening delegates current ready appearance to native fit owner " + surface);
                        check(renderer.fitApplicabilityChecks == 1, "texture readiness refines original demand " + surface);
                        for (int i = 0; i < 100; i++) render.invoke(screen, new CpmOwnedPreviewDispatchProbe.Graphics(), view);
                        check(renderer.fitPreparations == 1 && fixture.numericCreates == 0,
                                "unavailable native preparation is terminal without false full record or ambient retries " + surface);
                        fixture.worldChanged();
                        check(renderer.retirements == 1, "actual runtime coarse retirement reaches selected host native owner " + surface);
                        System.out.println("PASS_SUBMISSION_DEMAND " + surface + " pending=101 ready=101 applicability=1 nativePreparation=1 disk=0 retainedBody=true semanticDemand=true retirement=1");
                    }
                }
            }
        } finally {
            GalleryNativePassFixture.closeNumericCoordinator();
        }
    }
}

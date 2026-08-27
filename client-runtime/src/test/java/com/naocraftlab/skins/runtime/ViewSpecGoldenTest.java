package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.OuterLayerPart;
import com.naocraftlab.skins.client.OuterLayerVisibility;
import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.core.importing.ExternalImportProbe;
import com.naocraftlab.skins.core.importing.ExternalImportSource;
import com.naocraftlab.skins.core.model.AccountState;
import com.naocraftlab.skins.core.model.AppearancePreset;
import com.naocraftlab.skins.core.model.AppearanceSyncStatus;
import com.naocraftlab.skins.core.model.PersonalSkinSource;
import com.naocraftlab.skins.core.model.SkinVariant;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ViewSpecGoldenTest {
    @Test
    void serializerSchemaCoversEveryViewSpecRecordComponent() {
        String schema = Stream.concat(
                        Stream.of(ViewSpec.class),
                        Arrays.stream(ViewSpec.class.getDeclaredClasses()).filter(Class::isRecord))
                .sorted(Comparator.comparing(Class::getSimpleName))
                .map(type -> type.getSimpleName() + ":" + Arrays.stream(type.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName)
                        .collect(java.util.stream.Collectors.joining(",")))
                .collect(java.util.stream.Collectors.joining("\n"));
        assertEquals(golden("view-spec-schema.txt"), schema);
    }

    private static final GalleryPresenter GALLERY = new GalleryPresenter();
    private static final TextResolver TEXT = message -> switch (message.key()) {
        case "nclskins.editor.default_name" -> "Preset " + message.arguments().get(0);
        default -> message.key();
    };

    @Test
    void completeSmallViewSpecMatchesReferenceGolden() {
        assertEquals(golden("view-spec-320.txt"), describePair(
                gallery(4, 3, 1, 320, 240, 160, 100),
                editor(320, 240)).stripTrailing());
    }

    @Test
    void completeDefaultViewSpecMatchesReferenceGolden() {
        assertEquals(golden("view-spec-854.txt"), describePair(
                gallery(4, 3, 1, 854, 480, 427, 180),
                editor(854, 480)).stripTrailing());
    }

    @Test
    void completeWideViewSpecMatchesReferenceGolden() {
        assertEquals(golden("view-spec-wide.txt"), describePair(
                gallery(6, 5, 1, 1600, 720, 800, 200),
                editor(1600, 720)).stripTrailing());
    }

    @Test
    void portrait240OfflineGalleryMatchesGolden() {
        assertEquals(
                golden("gallery-view-spec-240.txt"),
                describe(galleryWithSessionState(240, 360, false, AppearanceSyncStatus.LOCAL_ONLY))
                        .stripTrailing());
    }

    @Test
    void portrait240ConnectingGalleryMatchesGolden() {
        assertEquals(
                golden("gallery-view-spec-240-connecting.txt"),
                describe(galleryWithSessionState(
                        240,
                        360,
                        false,
                        AppearanceSyncStatus.LOCAL_ONLY,
                        true,
                        UiMessage.info("nclskins.status.checking_session")))
                        .stripTrailing());
    }

    @Test
    void portrait320HealthyGalleryMatchesGolden() {
        assertEquals(
                golden("gallery-view-spec-320.txt"),
                describe(galleryWithSessionState(320, 480, true, AppearanceSyncStatus.LOCAL_ONLY))
                        .stripTrailing());
    }

    @Test
    void portrait427RecoveryGalleryMatchesGolden() {
        assertEquals(
                golden("gallery-view-spec-427.txt"),
                describe(galleryWithSessionState(427, 640, true, AppearanceSyncStatus.UNKNOWN))
                        .stripTrailing());
    }

    @Test
    void rateLimitedPendingGalleryMatchesProgressGolden() {
        AccountState account = TestFixtures.account(2);
        UUID active = account.presets().get(0).id();
        ClientSnapshot base = TestFixtures.ready(account, active, 0);
        ClientSnapshot snapshot = new ClientSnapshot(
                base.lifecycle(),
                base.account(),
                base.session(),
                base.remoteProfile(),
                base.lastMutation(),
                base.selectedSkinId(),
                Optional.of(active),
                base.selectedCapeId(),
                base.currentOfficialSkinId(),
                Optional.of(active),
                base.editor(),
                base.addSource(),
                base.status(),
                false,
                true,
                Optional.of(new ClientSnapshot.RateLimitProgress(
                        Duration.ofSeconds(30), Duration.ofSeconds(60), 0.5)),
                base.galleryOffset(),
                base.generation(),
                7,
                AppearanceSyncStatus.PARTIAL,
                false);
        ViewSpec view = GALLERY.present(
                snapshot,
                854,
                480,
                427,
                180,
                PreviewRenderer.CapeMode.CAPE);
        StringBuilder actual = new StringBuilder("widgets\n");
        for (ViewSpec.ProgressDecoration decoration : view.progressDecorations()) {
            ViewSpec.Widget widget = view.widget(decoration.ownerWidgetId()).orElseThrow();
            actual.append(widget.id())
                    .append("|hint=")
                    .append(widget.hint().map(ViewSpecGoldenTest::message).orElse("-"))
                    .append("|enabled=").append(widget.enabled())
                    .append("|clipped=").append(view.clipFor(widget.id()).isPresent())
                    .append('\n');
        }
        actual.append("progress\n");
        for (ViewSpec.ProgressDecoration decoration : view.progressDecorations()) {
            actual.append(decoration.id())
                    .append("|owner=").append(decoration.ownerWidgetId())
                    .append("|fraction=").append(decoration.fraction())
                    .append("|color=").append(String.format("%08X", decoration.color()))
                    .append("|height=").append(decoration.height())
                    .append('\n');
        }
        assertEquals(golden("gallery-view-spec-rate-limit.txt"), actual.toString().stripTrailing());
    }

    @Test
    void cold320LoadingGalleryMatchesGolden() {
        ClientSnapshot initial = ClientSnapshot.initial();
        ClientSnapshot loading = new ClientSnapshot(
                ClientSnapshot.Lifecycle.INITIALIZING,
                initial.account(),
                initial.session(),
                initial.remoteProfile(),
                initial.lastMutation(),
                initial.selectedSkinId(),
                initial.selectedPresetId(),
                initial.selectedCapeId(),
                initial.currentOfficialSkinId(),
                initial.activePresetId(),
                initial.editor(),
                initial.addSource(),
                UiMessage.info("nclskins.status.loading"),
                true,
                false,
                0,
                1,
                0,
                AppearanceSyncStatus.LOCAL_ONLY,
                false);

        assertEquals(
                golden("gallery-view-spec-320-loading.txt"),
                describe(GALLERY.present(
                        loading,
                        320,
                        240,
                        160,
                        100,
                        PreviewRenderer.CapeMode.CAPE)).stripTrailing());
    }

    @Test
    void externalImportChooserBusyAndRetryViewsMatchGolden() {
        ExternalImportPresenter presenter = new ExternalImportPresenter();
        ExternalImportModel launcher = ExternalImportModel.open(ExternalImportModel.Category.LAUNCHER)
                .withAutomaticProbes(Map.of(
                        ExternalImportSource.MINECRAFT_LAUNCHER, ExternalImportProbe.AVAILABLE,
                        ExternalImportSource.CURSEFORGE_APP, ExternalImportProbe.DEPENDENCY_MISSING,
                        ExternalImportSource.MODRINTH_APP, ExternalImportProbe.DEPENDENCY_MISSING,
                        ExternalImportSource.PRISM_LAUNCHER, ExternalImportProbe.UNAVAILABLE));
        ExternalImportModel failed = ExternalImportModel.open(ExternalImportModel.Category.MOD)
                .withAutomaticProbes(Map.of(
                        ExternalImportSource.SKIN_SHUFFLE, ExternalImportProbe.UNAVAILABLE))
                .withManualProbe(ExternalImportSource.SKIN_SHUFFLE, Path.of("instance"), false);
        ExternalImportModel probing = ExternalImportModel.open(ExternalImportModel.Category.LAUNCHER);
        String actual = "[chooser-240]\n" + describe(presenter.present(
                launcher,
                false,
                Optional.of(UiMessage.info("nclskins.external_import.choose_source")),
                240,
                240))
                + "[failed-320]\n" + describe(presenter.present(
                failed,
                false,
                Optional.of(UiMessage.error("nclskins.external_import.not_found.skin_shuffle")),
                320,
                240))
                + "[busy-427]\n" + describe(presenter.present(
                probing,
                true,
                Optional.of(UiMessage.info("nclskins.external_import.searching")),
                427,
                240))
                + "[retry-854]\n" + describe(presenter.present(
                failed,
                false,
                Optional.of(UiMessage.error("nclskins.external_import.no_valid")),
                854,
                480));
        assertEquals(golden("external-import-view-spec.txt"), actual.stripTrailing());
    }

    @Test
    void externalImportReviewMatchesGoldenAtControlSizes() {
        ExternalImportPresenter presenter = new ExternalImportPresenter();
        ExternalImportModel review = ExternalImportModel.open(ExternalImportModel.Category.MOD)
                .withAutomaticProbes(Map.of(
                        ExternalImportSource.SKIN_SHUFFLE, ExternalImportProbe.AVAILABLE))
                .withReview(new ClientOperations.ExternalImportReview(
                        ExternalImportSource.SKIN_SHUFFLE,
                        List.of(
                                externalCandidate("candidate-0", "New classic", false),
                                externalCandidate("candidate-1", "Existing slim", true)),
                        1,
                        1));
        StringBuilder actual = new StringBuilder();
        for (int[] size : List.of(
                new int[]{240, 240},
                new int[]{320, 240},
                new int[]{427, 240},
                new int[]{854, 480})) {
            actual.append("[review-").append(size[0]).append('x').append(size[1]).append("]\n")
                    .append(describe(presenter.present(
                            review,
                            false,
                            Optional.of(UiMessage.info("nclskins.external_import.review_ready")),
                            size[0],
                            size[1])));
        }
        assertEquals(
                golden("external-import-review-view-spec.txt"),
                actual.toString().stripTrailing());
    }

    private static ClientOperations.ExternalImportCandidate externalCandidate(
            String id, String name, boolean duplicate) {
        return new ClientOperations.ExternalImportCandidate(
                id,
                name,
                duplicate ? SkinVariant.SLIM : SkinVariant.CLASSIC,
                PersonalSkinSource.FILE,
                new byte[]{1, 2, 3},
                (duplicate ? "1" : "0").repeat(64),
                duplicate ? "owned-cape" : null,
                duplicate ? 1 : 0,
                duplicate);
    }

    private static ViewSpec gallery(
            int presetCount,
            int activeIndex,
            int offset,
            int width,
            int height,
            int mouseX,
            int mouseY) {
        AccountState account = TestFixtures.account(presetCount);
        UUID active = account.presets().get(activeIndex).id();
        return GALLERY.present(
                TestFixtures.ready(account, active, offset),
                width,
                height,
                mouseX,
                mouseY,
                PreviewRenderer.CapeMode.ELYTRA);
    }

    private static ViewSpec galleryWithSessionState(
            int width, int height, boolean validSession, AppearanceSyncStatus syncStatus) {
        return galleryWithSessionState(
                width,
                height,
                validSession,
                syncStatus,
                false,
                UiMessage.info("nclskins.status.profile_loaded"));
    }

    private static ViewSpec galleryWithSessionState(
            int width,
            int height,
            boolean validSession,
            AppearanceSyncStatus syncStatus,
            boolean reconnecting,
            UiMessage status) {
        AccountState account = TestFixtures.account(4);
        UUID active = account.presets().get(3).id();
        ClientSnapshot base = TestFixtures.ready(account, active, 1);
        ClientSnapshot snapshot = new ClientSnapshot(
                base.lifecycle(),
                base.account(),
                validSession ? base.session() : Optional.empty(),
                validSession ? base.remoteProfile() : Optional.empty(),
                base.lastMutation(),
                base.selectedSkinId(),
                base.selectedPresetId(),
                base.selectedCapeId(),
                base.currentOfficialSkinId(),
                base.activePresetId(),
                base.editor(),
                base.addSource(),
                status,
                false,
                false,
                Optional.empty(),
                0,
                base.generation(),
                base.intentRevision(),
                syncStatus,
                false,
                reconnecting
                        ? ClientSnapshot.SessionActivity.RECONNECTING
                        : ClientSnapshot.SessionActivity.NONE);
        return GALLERY.present(
                snapshot,
                width,
                height,
                width / 2,
                height / 3,
                PreviewRenderer.CapeMode.ELYTRA);
    }

    private static ViewSpec editor(int width, int height) {
        AccountState account = TestFixtures.account(2);
        AppearancePreset original = account.presets().get(1);
        PresetEditorModel model = PresetEditorModel.open(
                        account,
                        Optional.of(original),
                        Optional.of(TestFixtures.validSession().profile()),
                        Optional.of(original.id()),
                        TEXT,
                        height,
                        PreviewRenderer.CapeMode.CAPE)
                .withName("Golden draft")
                .toggleVariant()
                .withPng("golden.png", new byte[] {1, 2, 3})
                .withPreview(new PreviewInteractionModel(
                        13.0F,
                        -11.0F,
                        1.24F,
                        OuterLayerVisibility.noneVisible(),
                        PreviewRenderer.CapeMode.ELYTRA,
                        false));
        return model.present(width, height);
    }

    private static String describePair(ViewSpec gallery, ViewSpec editor) {
        return "[gallery]\n" + describe(gallery) + "[editor]\n" + describe(editor);
    }

    private static String describe(ViewSpec view) {
        StringBuilder result = new StringBuilder();
        result.append("screen=").append(view.screenId())
                .append(" size=").append(view.width()).append('x').append(view.height())
                .append(" title=").append(message(view.title())).append('\n');
        result.append("panels\n");
        for (int index = 0; index < view.panels().size(); index++) {
            ViewSpec.Panel panel = view.panels().get(index);
            result.append(index).append('|').append(panel.id()).append('|')
                    .append(bounds(panel.bounds())).append('|').append(panel.style()).append('\n');
        }
        result.append("texts\n");
        for (int index = 0; index < view.texts().size(); index++) {
            ViewSpec.Text text = view.texts().get(index);
            result.append(index).append('|').append(text.id()).append('|')
                    .append(bounds(text.bounds())).append('|').append(message(text.message())).append('|')
                    .append(text.alignment()).append('\n');
        }
        result.append("widgets\n");
        for (int index = 0; index < view.widgets().size(); index++) {
            ViewSpec.Widget widget = view.widgets().get(index);
            result.append(index).append('|').append(widget.id()).append('|').append(widget.kind()).append('|')
                    .append(bounds(widget.bounds())).append('|').append(message(widget.label())).append('|')
                    .append("value=").append(widget.value().orElse("-")).append('|')
                    .append("hint=").append(widget.hint().map(ViewSpecGoldenTest::message).orElse("-")).append('|')
                    .append("enabled=").append(widget.enabled()).append('|')
                    .append("visible=").append(widget.visible()).append('|')
                    .append("max=").append(widget.maxLength()).append('\n');
        }
        result.append("previews\n");
        for (int index = 0; index < view.previews().size(); index++) {
            ViewSpec.Preview preview = view.previews().get(index);
            result.append(index).append('|').append(preview.id()).append('|')
                    .append(bounds(preview.bounds())).append('|');
            if (!preview.anchorBounds().equals(preview.bounds())) {
                result.append("anchor=").append(bounds(preview.anchorBounds())).append('|');
            }
            result
                    .append("skin=").append(preview.skin().kind()).append(':')
                    .append(preview.skin().optionalAssetId().map(UUID::toString).orElse("-")).append('|')
                    .append("revision=").append(preview.imageRevision()).append('|')
                    .append("variant=").append(preview.variant()).append('|')
                    .append("cape=").append(preview.capeId().orElse("-")).append('|')
                    .append("mode=").append(preview.capeMode()).append('|')
                    .append("outer=").append(outerLayer(preview.outerLayerVisibility())).append('|')
                    .append("yaw=").append(preview.yawDegrees()).append('|')
                    .append("pitch=").append(preview.pitchDegrees()).append('|')
                    .append("scale=").append(preview.scale()).append('|')
                    .append("preset=").append(preview.presetId().map(UUID::toString).orElse("-")).append('\n');
        }
        result.append("back_equipment_previews\n");
        for (int index = 0; index < view.backEquipmentPreviews().size(); index++) {
            ViewSpec.BackEquipmentPreview preview = view.backEquipmentPreviews().get(index);
            result.append(index).append('|').append(preview.id()).append('|')
                    .append(bounds(preview.bounds())).append("|cape=").append(preview.capeId()).append('|')
                    .append("mode=").append(preview.mode()).append('\n');
        }
        result.append("icon_decorations\n");
        for (int index = 0; index < view.iconDecorations().size(); index++) {
            ViewSpec.IconDecoration decoration = view.iconDecorations().get(index);
            result.append(index).append('|').append(decoration.id()).append('|')
                    .append(bounds(decoration.bounds())).append("|icon=").append(decoration.icon()).append('|')
                    .append("owner=").append(decoration.ownerWidgetId()).append('|')
                    .append("idle=").append(decoration.idleOpacity()).append('|')
                    .append("active=").append(decoration.activeOpacity()).append('\n');
        }
        result.append("clip_regions\n");
        for (int index = 0; index < view.clipRegions().size(); index++) {
            ViewSpec.ClipRegion clip = view.clipRegions().get(index);
            result.append(index).append('|').append(clip.id()).append('|')
                    .append(bounds(clip.bounds())).append("|prefixes=")
                    .append(String.join(",", clip.elementPrefixes())).append('\n');
        }
        result.append("scrollbar=");
        if (view.scrollbar().isEmpty()) {
            result.append("-\n");
        } else {
            ViewSpec.Scrollbar scrollbar = view.scrollbar().orElseThrow();
            result.append(bounds(scrollbar.track())).append('|').append(bounds(scrollbar.thumb()))
                    .append("|offset=").append(scrollbar.offset())
                    .append("|maximum=").append(scrollbar.maximum()).append('\n');
        }
        return result.toString();
    }

    private static String outerLayer(OuterLayerVisibility visibility) {
        StringBuilder value = new StringBuilder();
        for (OuterLayerPart part : OuterLayerPart.values()) {
            if (visibility.visible(part)) {
                if (value.length() > 0) {
                    value.append(',');
                }
                value.append(part.name());
            }
        }
        return value.length() == 0 ? "-" : value.toString();
    }

    private static String bounds(Bounds bounds) {
        return bounds.x() + "," + bounds.y() + "," + bounds.width() + "," + bounds.height();
    }

    private static String message(UiMessage message) {
        StringBuilder result = new StringBuilder(message.literal() ? "literal:" : "key:");
        result.append(message.key());
        if (!message.arguments().isEmpty()) {
            result.append('(');
            for (int index = 0; index < message.arguments().size(); index++) {
                if (index > 0) {
                    result.append(',');
                }
                Object argument = message.arguments().get(index);
                result.append(argument instanceof UiMessage nested ? message(nested) : argument);
            }
            result.append(')');
        }
        return result.append(':').append(message.severity()).toString();
    }

    private static String golden(String name) {
        String resource = "/golden/" + name;
        try (InputStream input = ViewSpecGoldenTest.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new AssertionError("Missing golden resource: " + resource);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).stripTrailing();
        } catch (IOException failure) {
            throw new AssertionError("Cannot read golden resource: " + resource, failure);
        }
    }

}

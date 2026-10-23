package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.CatalogCollectionOrder;
import com.naocraftlab.skins.client.CatalogText;
import com.naocraftlab.skins.client.MinecraftSkinCatalog;
import com.naocraftlab.skins.client.PersonalSkinCatalog;
import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.client.SkinCatalogSource;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.core.compatibility.SkinConflictReason;
import com.naocraftlab.skins.core.compatibility.SkinConsumer;
import com.naocraftlab.skins.core.compatibility.SkinConsumerState;
import com.naocraftlab.skins.core.compatibility.SkinExtensionEnvironment;
import com.naocraftlab.skins.core.compatibility.SkinFeature;
import com.naocraftlab.skins.core.compatibility.SkinFeatureEvidence;
import com.naocraftlab.skins.core.model.AccountUiPreferences;
import com.naocraftlab.skins.core.model.AddSourceTab;
import com.naocraftlab.skins.core.model.SkinVariant;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AddSourceModelPresenterTest {
    private final AddSourcePresenter presenter = new AddSourcePresenter();

    @Test
    void countQuotaProjectsCurrentForeignLabelsBeforeEveryMeasurementAndReload() {
        var keys = List.of("button.cpm.open_editor", "button.cpm.edit", "button.cpm.edit.pastes");
        var labels = new java.util.HashMap<String, String>();
        var measured = new java.util.ArrayList<UiMessage>();
        var lookups = new java.util.ArrayList<String>();
        TextResolver resolver = TextResolver.withCatalogTranslations(TextResolver.withLayout(UiMessage::key,
                (message, width) -> { measured.add(message); return 90; }), (key, fallback) -> {
                    lookups.add(key);
                    assertEquals("", fallback);
                    return labels.getOrDefault(key, fallback);
                });
        AddSourceModel model = AddSourceModel.open(AccountUiPreferences.defaults(TestFixtures.ACCOUNT_ID), List.of(),
                SkinVariant.CLASSIC, resolver).withSelectedTab(AddSourceTab.FILE);
        var raw = UiMessage.error("nclskins.add.cpm_publication_count_quota");
        for (var names : List.of(List.of("Редактор CPM", "Редактировать", "Вставки"),
                List.of("Open Skin Editor", "Edit", "Pastes"), List.of("Reloaded editor", "Reloaded edit", "Reloaded pastes"))) {
            for (int i = 0; i < keys.size(); i++) labels.put(keys.get(i), names.get(i));
            measured.clear(); lookups.clear();
            var view = presenter.present(model, false, Optional.of(raw), 180, 120);
            var message = view.texts().stream().filter(text -> text.id().equals("add.import.status")).findFirst().orElseThrow().message();
            assertEquals(raw.key(), message.key());
            assertEquals(names, message.arguments());
            assertEquals(UiMessage.Severity.ERROR, message.severity());
            assertEquals(keys, lookups);
            assertTrue(measured.stream().filter(value -> value.key().startsWith(raw.key())).allMatch(message::equals));
            assertImportStatusHasNoInfoTarget(view);
        }
        for (String missing : keys) for (String invalid : List.of("", " \t", missing)) {
            keys.forEach(key -> labels.put(key, "Current label"));
            labels.put(missing, invalid);
            var view = presenter.present(model, false, Optional.of(raw), 180, 120);
            var message = view.texts().stream().filter(text -> text.id().equals("add.import.status")).findFirst().orElseThrow().message();
            assertEquals("nclskins.add.cpm_publication_count_quota_unavailable", message.key());
            assertTrue(message.arguments().isEmpty());
        }
        labels.clear();
        var stale = presenter.present(model, false, Optional.of(raw), 180, 120);
        assertTrue(stale.texts().stream().anyMatch(text -> text.message().key().equals(
                "nclskins.add.cpm_publication_count_quota_unavailable") && text.message().arguments().isEmpty()));
        for (String reason : List.of("size_quota", "protocol", "uncertain", "network", "timeout")) {
            lookups.clear();
            var other = UiMessage.error("nclskins.add.cpm_publication_" + reason);
            var view = presenter.present(model, false, Optional.of(other), 180, 120);
            assertTrue(view.texts().stream().anyMatch(text -> text.message().equals(other)));
            assertTrue(lookups.isEmpty());
        }
    }

    @Test
    void localizedCountQuotaUsesOneFullWrappedMessageAboveFooterAtNarrowLowSizes() throws Exception {
        var catalog = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(java.nio.file.Path.of("../gradle/targets.json")))
                .getAsJsonObject().getAsJsonObject("mod").getAsJsonObject("localization").getAsJsonArray("sourceLocales");
        var raw = UiMessage.error("nclskins.add.cpm_publication_count_quota");
        for (var locale : catalog) {
            var language = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(java.nio.file.Path.of(
                    "../compat/resources/canonical/src/main/resources/assets/nclskins/lang/" + locale.getAsString() + ".json"))).getAsJsonObject();
            for (boolean available : List.of(false, true)) {
                TextResolver resolver = TextResolver.withCatalogTranslations(message -> {
                    String value = language.has(message.key()) ? language.get(message.key()).getAsString() : message.key();
                    return message.arguments().isEmpty() ? value : String.format(java.util.Locale.ROOT, value, message.arguments().toArray());
                }, (key, fallback) -> available ? "Long current translated menu label ".repeat(8) : fallback);
                AddSourceModel model = AddSourceModel.open(AccountUiPreferences.defaults(TestFixtures.ACCOUNT_ID), List.of(),
                        SkinVariant.CLASSIC, resolver).withSelectedTab(AddSourceTab.FILE);
                for (int[] size : List.of(new int[]{180, 120}, new int[]{240, 180}, new int[]{854, 160})) {
                    var first = presenter.present(model, false, Optional.of(raw), size[0], size[1]);
                    var surface = first.scrollSurfaces().get(0);
                    var end = presenter.present(model.withScrollOffset((int) surface.maximumPixels()), false,
                            Optional.of(raw), size[0], size[1]);
                    var status = end.texts().stream().filter(text -> text.id().equals("add.import.status")).findFirst().orElseThrow();
                    assertEquals(available ? raw.key() : raw.key() + "_unavailable", status.message().key());
                    assertEquals(available ? 3 : 0, status.message().arguments().size());
                    assertEquals(model.messageHeight(status.message(), status.bounds().width()), status.bounds().height());
                    assertEquals(ViewSpec.Text.Layout.WRAP, status.layout());
                    assertTrue(status.bounds().bottom() <= surface.viewport().bottom());
                    assertTrue(end.widget("add.cancel").orElseThrow().bounds().y() >= surface.viewport().bottom());
                    assertFalse(resolver.resolve(status.message()).contains("button.cpm."));
                    assertFalse(resolver.resolve(status.message()).contains("%1$s"));
                    assertImportStatusHasNoInfoTarget(end);
                    assertTrue(end.widgets().stream().noneMatch(widget -> widget.id().startsWith("add.cpm.publication.")));
                }
            }
        }
    }

    @Test
    void urlPlaceholderDependsOnPresenceAndPurposeWithoutChangingInputOrLabel() {
        for (var purpose : List.of(AddSourceModel.Purpose.CREATE_PRESET, AddSourceModel.Purpose.CPM_BASE)) {
            AddSourceModel model = AddSourceModel.open(AccountUiPreferences.defaults(TestFixtures.ACCOUNT_ID), List.of())
                    .withSelectedTab(AddSourceTab.FILE)
                    .withUrlInput("https://example.invalid/retained-input")
                    .withImportContext(new AddSourceModel.ImportContext(Optional.empty(), Optional.empty(), purpose));
            for (boolean present : List.of(false, true, false, true)) {
                model = model.withCompatibilityContext(new SkinExtensionEnvironment(9, Map.of(), present), Map.of(), true);
                var input = presenter.present(model, false, 240, 240).widget("add.url.input").orElseThrow();
                assertEquals(present && purpose == AddSourceModel.Purpose.CREATE_PRESET
                        ? "nclskins.add_source.url_cpm_hint" : "nclskins.add_source.url_hint", input.hint().orElseThrow().key());
                assertEquals("nclskins.add_source.url_label", input.label().key());
                assertEquals(Optional.of("https://example.invalid/retained-input"), input.value());
                assertEquals(purpose, model.importContext().purpose());
            }
        }
    }

    @Test
    void dependencyWarningUsesScrollableClippedFormAndPersistentCancel() {
        for (int height : List.of(120, 240)) {
            AddSourceModel model = AddSourceModel.open(AccountUiPreferences.defaults(TestFixtures.ACCOUNT_ID),
                    List.of(), SkinVariant.CLASSIC, message -> message.key().endsWith("warning") ? "Long warning ".repeat(120) : "Title")
                    .withImportContext(new AddSourceModel.ImportContext(Optional.empty(), Optional.empty(),
                            AddSourceModel.Purpose.CREATE_PRESET, Optional.of(new AddSourceModel.DependencyPrompt(7))));
            ViewSpec view = presenter.present(model, false, 240, height);
            assertTrue(view.tabGroups().isEmpty());
            assertEquals(2, view.widgets().size());
            assertTrue(view.scrollbar().isPresent());
            var surface = view.scrollSurfaces().get(0);
            var confirm = view.widget("add.cpm.dependency.confirm.7").orElseThrow();
            assertEquals("nclskins.add.cpm_dependency_warning", confirm.hint().orElseThrow().key());
            assertTrue(view.clipRegions().stream().anyMatch(region -> region.elementPrefixes().contains("add.cpm.dependency.confirm.")));
            ViewSpec bottom = presenter.present(model.withScrollOffset((int) surface.maximumPixels()), true, 240, height);
            var finalConfirm = bottom.widget(confirm.id()).orElseThrow();
            assertTrue(finalConfirm.bounds().y() >= surface.viewport().y());
            assertTrue(finalConfirm.bounds().bottom() <= surface.viewport().bottom());
            assertFalse(finalConfirm.enabled());
            var cancel = bottom.widget("add.cpm.dependency.cancel.7").orElseThrow();
            assertTrue(cancel.enabled());
            assertTrue(cancel.bounds().y() >= surface.viewport().bottom());
            assertEquals(2, bottom.navigationNodes().size());
        }
    }

    @Test
    void ordinaryPublicationFeedbackKeepsFullWrappedTextWithoutInfoTargetsAndDisablesBusyControls() {
        var loading = UiMessage.info("nclskins.add.cpm_publication_loading");
        var error = UiMessage.error("nclskins.add.cpm_publication_timeout");
        AddSourceModel model = AddSourceModel.open(AccountUiPreferences.defaults(TestFixtures.ACCOUNT_ID), List.of(),
                SkinVariant.CLASSIC, message -> "Полное многострочное сообщение для загрузки модели CPM Paste")
                .withSelectedTab(AddSourceTab.FILE).withUrlInput("https://example.invalid/model");
        for (int height : List.of(120, 240, 480)) for (UiMessage message : List.of(loading, error)) {
            boolean busy = message == loading;
            var view = presenter.present(model, busy, Optional.of(message), 240, height);
            var status = view.texts().stream().filter(text -> text.id().equals("add.import.status")).findFirst().orElseThrow();
            assertEquals(message, status.message());
            assertEquals(ViewSpec.Text.Layout.WRAP, status.layout());
            assertEquals(ViewSpec.Text.Alignment.CENTER, status.alignment());
            assertTrue(status.bounds().height() > 10);
            assertImportStatusHasNoInfoTarget(view);
            var surface = view.scrollSurfaces().get(0);
            var scrolled = presenter.present(model.withScrollOffset((int) surface.maximumPixels()),
                    busy, Optional.of(message), 240, height);
            assertImportStatusHasNoInfoTarget(scrolled);
            var finalStatus = scrolled.texts().stream().filter(text -> text.id().equals("add.import.status")).findFirst().orElseThrow();
            assertEquals(status.message(), finalStatus.message());
            assertEquals(status.bounds().height(), finalStatus.bounds().height());
            assertTrue(finalStatus.bounds().bottom() <= surface.viewport().bottom());
            assertEquals(surface.viewport(), scrolled.clipFor(finalStatus.id()).orElseThrow());
            assertTrue(view.widgets().stream().filter(widget -> !widget.id().equals("add.cancel"))
                    .allMatch(widget -> !busy || !widget.enabled()));
            assertEquals(!busy, view.widget("add.cancel").orElseThrow().enabled());
            assertTrue(view.tabGroups().stream().flatMap(group -> group.tabs().stream()).allMatch(tab -> tab.enabled() == !busy));
            assertEquals("nclskins.add_source.title", view.title().key());
            assertTrue(view.widget("add.catalog.filter").isEmpty());
            assertTrue(view.widgets().stream().noneMatch(widget -> widget.id().startsWith("add.cpm.publication.")));
        }
    }

    @Test
    void ordinaryFileErrorKeepsItsMessageAndGeometryWithoutStatusInfo() {
        var error = UiMessage.error("nclskins.add_source.file_io_error");
        var model = AddSourceModel.open(AccountUiPreferences.defaults(TestFixtures.ACCOUNT_ID), List.of())
                .withSelectedTab(AddSourceTab.FILE);
        for (int height : List.of(120, 240, 480)) {
            var view = presenter.present(model, false, Optional.of(error), 240, height);
            var text = view.texts().stream().filter(value -> value.id().equals("add.import.status")).findFirst().orElseThrow();
            assertEquals(error, text.message());
            assertEquals(new Bounds(10, 187, 220, model.messageHeight(error, 220)), text.bounds());
            assertEquals(ViewSpec.Text.Alignment.CENTER, text.alignment());
            assertEquals(ViewSpec.Text.Layout.WRAP, text.layout());
            assertImportStatusHasNoInfoTarget(view);
            assertTrue(view.widget("add.file.choose").orElseThrow().enabled());
            assertTrue(view.widget("add.cancel").orElseThrow().enabled());
        }
    }

    @Test
    void cpmBaseTitleIsForegroundUnderUnchangedTabsOnBothTabs() {
        AddSourceModel ordinary = AddSourceModel.open(
                AccountUiPreferences.defaults(TestFixtures.ACCOUNT_ID), List.of());
        assertEquals("nclskins.add_source.title", presenter.present(ordinary, false, 240, 240).title().key());
        assertTrue(presenter.present(ordinary, false, 240, 240).texts().stream()
                .noneMatch(text -> text.id().equals("add.context.title")));
        for (AddSourceTab tab : AddSourceTab.values()) {
            AddSourceModel model = ordinary.withSelectedTab(tab).withImportContext(
                    new AddSourceModel.ImportContext(Optional.of(SkinModel.SLIM), Optional.empty()));
            ViewSpec view = presenter.present(model, false, 240, 240);
            assertEquals(new Bounds(0, 0, 240, 24), view.tabGroups().get(0).bounds());
            ViewSpec.Text title = view.texts().stream().filter(text -> text.id().equals("add.context.title"))
                    .findFirst().orElseThrow(() -> new AssertionError("CPM title must materialize as foreground text"));
            assertEquals("nclskins.add_source.cpm.title", title.message().key());
            assertEquals(title.message(), view.title());
            assertEquals(ViewSpec.Text.Layout.WRAP, title.layout());
            assertTrue(title.bounds().y() >= 24);
            assertTrue(view.widgets().stream().filter(widget -> !widget.id().equals("add.cancel"))
                    .allMatch(widget -> widget.bounds().y() >= title.bounds().bottom()));
        }
    }

    @Test
    void cpmRequiredFilterAndBlockedSourcesKeepVisibleLabelsAndReasons() {
        for (SkinModel required : SkinModel.values()) {
            AddSourceModel model = AddSourceModel.open(AccountUiPreferences.defaults(TestFixtures.ACCOUNT_ID), List.of())
                    .withImportContext(new AddSourceModel.ImportContext(Optional.of(required), Optional.empty()));
            ViewSpec catalog = presenter.present(model.withSelectedTab(AddSourceTab.CATALOG), false, 240, 240);
            ViewSpec.Widget filter = catalog.widget("add.catalog.filter").orElseThrow();
            assertFalse(filter.enabled(), "required filter must be disabled, not merely labelled");
            assertEquals("nclskins.add_source.filter_" + required.name().toLowerCase(java.util.Locale.ROOT), filter.label().key());
            assertEquals("nclskins.add_source.cpm.required_" + required.name().toLowerCase(java.util.Locale.ROOT),
                    filter.hint().orElseThrow().key());
            ViewSpec file = presenter.present(model.withSelectedTab(AddSourceTab.FILE), false, 240, 240);
            for (String id : List.of("add.external.launcher", "add.external.mod")) {
                ViewSpec.Widget action = file.widget(id).orElseThrow();
                assertTrue(action.visible());
                assertFalse(action.enabled());
                assertEquals("nclskins.add_source.cpm." + (id.endsWith("launcher") ? "launcher" : "mod") + "_blocked", action.hint().orElseThrow().key());
            }
            assertTrue(presenter.present(model.withSelectedTab(AddSourceTab.CATALOG), true, 240, 240)
                    .widget("add.cancel").orElseThrow().enabled(), "pending CPM work remains cancellable on Catalog");
        }
        AddSourceModel ordinary = AddSourceModel.open(AccountUiPreferences.defaults(TestFixtures.ACCOUNT_ID), List.of())
                .withSelectedTab(AddSourceTab.CATALOG);
        assertTrue(presenter.present(ordinary, false, 240, 240).widget("add.catalog.filter").orElseThrow().enabled());
        assertFalse(presenter.present(ordinary, true, 240, 240).widget("add.cancel").orElseThrow().enabled());
    }

    @Test
    void cpmTypedFailuresAreWrappedOnTheCurrentTabWithRequiredTypeNotPreference() {
        Map<com.naocraftlab.skins.client.CpmModelFileConversion.Reason, String> expected = Map.ofEntries(
                Map.entry(com.naocraftlab.skins.client.CpmModelFileConversion.Reason.CPM_MISSING, "dependency_missing"),
                Map.entry(com.naocraftlab.skins.client.CpmModelFileConversion.Reason.CPM_UNAVAILABLE, "adapter_unavailable"),
                Map.entry(com.naocraftlab.skins.client.CpmModelFileConversion.Reason.NESTED_MODEL, "nested_file"),
                Map.entry(com.naocraftlab.skins.client.CpmModelFileConversion.Reason.INVALID_MODEL, "invalid_model"),
                Map.entry(com.naocraftlab.skins.client.CpmModelFileConversion.Reason.MODEL_UNSUPPORTED, "model_unsupported"),
                Map.entry(com.naocraftlab.skins.client.CpmModelFileConversion.Reason.NONPORTABLE_OVERFLOW, "local_overflow"),
                Map.entry(com.naocraftlab.skins.client.CpmModelFileConversion.Reason.PORTABILITY_UNSUPPORTED, "portability_unsupported"),
                Map.entry(com.naocraftlab.skins.client.CpmModelFileConversion.Reason.CONVERSION_FAILED, "conversion_failed"),
                Map.entry(com.naocraftlab.skins.client.CpmModelFileConversion.Reason.INVALID_OUTPUT_PNG, "invalid_output"),
                Map.entry(com.naocraftlab.skins.client.CpmModelFileConversion.Reason.STALE_RESOURCES, "stale_resources"),
                Map.entry(com.naocraftlab.skins.client.CpmModelFileConversion.Reason.STALE_WORLD, "stale_world"));
        for (AddSourceTab tab : AddSourceTab.values()) {
            for (SkinModel required : SkinModel.values()) {
                AddSourceModel base = AddSourceModel.open(AccountUiPreferences.defaults(TestFixtures.ACCOUNT_ID), List.of())
                        .withSelectedTab(tab);
                for (var reason : com.naocraftlab.skins.client.CpmModelFileConversion.Reason.values()) {
                    AddSourceModel model = base.withImportContext(new AddSourceModel.ImportContext(Optional.of(required), Optional.of(reason)));
                    ViewSpec view = presenter.present(model, false, 240, 240);
                    var statuses = view.texts().stream().filter(text -> text.id().equals(tab == AddSourceTab.FILE
                            ? "add.import.status" : "add.catalog.status")).toList();
                    if (reason == com.naocraftlab.skins.client.CpmModelFileConversion.Reason.CLOSED) {
                        assertTrue(statuses.isEmpty());
                        continue;
                    }
                    assertEquals(1, statuses.size(), "typed reason must not disappear on " + tab + ": " + reason);
                    String key = reason == com.naocraftlab.skins.client.CpmModelFileConversion.Reason.IO_FAILED
                            ? "nclskins.add_source.file_io_error"
                            : "nclskins.add_source.cpm." + (reason == com.naocraftlab.skins.client.CpmModelFileConversion.Reason.VARIANT_UNSUPPORTED
                                    ? "required_" + required.name().toLowerCase(java.util.Locale.ROOT) : expected.get(reason));
                    assertEquals(key, statuses.get(0).message().key());
                    assertEquals(UiMessage.Severity.ERROR, statuses.get(0).message().severity());
                    assertEquals(ViewSpec.Text.Layout.WRAP, statuses.get(0).layout());
                }
            }
        }
    }

    @Test
    void cpmWorkspaceScrollClipsFormErrorsAndReasonsBeforeFixedFooter() throws Exception {
        var catalog = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(java.nio.file.Path.of("../gradle/targets.json")))
                .getAsJsonObject().getAsJsonObject("mod").getAsJsonObject("localization").getAsJsonArray("sourceLocales");
        for (var locale : catalog) {
            var language = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(java.nio.file.Path.of(
                    "../compat/resources/canonical/src/main/resources/assets/nclskins/lang/" + locale.getAsString() + ".json"))).getAsJsonObject();
            TextResolver resolver = message -> language.has(message.key()) ? language.get(message.key()).getAsString() : message.key();
            for (int[] size : List.of(new int[] {240, 240}, new int[] {180, 180}, new int[] {854, 160}, new int[] {320, 240})) {
                for (AddSourceTab tab : AddSourceTab.values()) {
                    AddSourceModel model = AddSourceModel.open(AccountUiPreferences.defaults(TestFixtures.ACCOUNT_ID), List.of(),
                            SkinVariant.CLASSIC, resolver).withSelectedTab(tab).withImportContext(new AddSourceModel.ImportContext(
                                    Optional.of(SkinModel.SLIM), Optional.of(com.naocraftlab.skins.client.CpmModelFileConversion.Reason.NONPORTABLE_OVERFLOW)));
                    ViewSpec initial = presenter.present(model, false, size[0], size[1]);
                    var surface = initial.scrollSurfaces().stream().findFirst()
                            .orElseThrow(() -> new AssertionError("CPM form and error need one reachable scroll surface"));
                    assertEquals(size[1] - 33, surface.viewport().bottom());
                    var title = initial.texts().stream().filter(t -> t.id().equals("add.context.title")).findFirst().orElseThrow();
                    assertTrue(surface.viewport().y() >= title.bounds().bottom());
                    ViewSpec end = presenter.present(model.withScrollOffset((int) surface.maximumPixels()), false, size[0], size[1]);
                    var status = end.texts().stream().filter(t -> t.id().equals(tab == AddSourceTab.FILE ? "add.import.status" : "add.catalog.status"))
                            .findFirst().orElseThrow();
                    assertTrue(status.bounds().bottom() <= surface.viewport().bottom(), locale + ":" + tab + ":" + status.bounds());
                    assertTrue(status.bounds().bottom() > surface.viewport().y());
                    assertEquals(surface.viewport(), end.clipFor(status.id()).orElseThrow());
                    assertEquals(surface.viewport(), end.clipFor(tab == AddSourceTab.FILE ? "add.file.choose" : "add.catalog.search").orElseThrow());
                    assertEquals(new Bounds((size[0] - Math.min(200, Math.max(100, size[0] - 32))) / 2, size[1] - 28,
                            Math.min(200, Math.max(100, size[0] - 32)), 20), end.widget("add.cancel").orElseThrow().bounds());
                    assertTrue(end.clipFor("add.cancel").isEmpty());
                    assertEquals(surface.maximumPixels(), end.scrollSurfaces().get(0).maximumPixels());
                    for (ViewSpec.Widget widget : end.widgets()) {
                        assertTrue(widget.bounds().x() >= 0 && widget.bounds().right() <= size[0], widget.id());
                        if (!widget.id().equals("add.cancel")) assertTrue(end.navigationNode(widget.id()).isPresent(), widget.id());
                    }
                    for (int offset = 0; offset <= (int) surface.maximumPixels(); offset++) {
                        var view = presenter.present(model.withScrollOffset(offset), false, size[0], size[1]);
                        for (String id : tab == AddSourceTab.FILE ? List.of("add.player.input", "add.url.input", "add.file.choose", "add.external.launcher", "add.external.mod")
                                : List.of("add.catalog.search", "add.catalog.filter")) {
                            Bounds b = view.widget(id).orElseThrow().bounds();
                            assertFalse(ViewHostPolicy.pointerInsideClip(view, id, b.x() + 1, surface.viewport().bottom() + 1));
                        }
                    }
                }
            }
        }
    }

    @Test
    void cpmCatalogReasonsRemainKeyboardReachableWhileFileMismatchHasNoInfoTarget() {
        for (AddSourceTab tab : AddSourceTab.values()) for (SkinModel required : SkinModel.values()) {
            AddSourceModel model = AddSourceModel.open(AccountUiPreferences.defaults(TestFixtures.ACCOUNT_ID), List.of())
                    .withSelectedTab(tab).withImportContext(new AddSourceModel.ImportContext(Optional.of(required),
                            Optional.of(com.naocraftlab.skins.client.CpmModelFileConversion.Reason.VARIANT_UNSUPPORTED)));
            ViewSpec view = presenter.present(model, true, 240, 240);
            assertTrue(view.widget("add.catalog.reason").isEmpty());
            assertTrue(view.texts().stream().noneMatch(text -> text.id().equals("add.catalog.reason.text")));
            String key = "nclskins.add_source.cpm.required_" + required.name().toLowerCase(java.util.Locale.ROOT);
            if (tab == AddSourceTab.FILE) {
                for (int[] size : List.of(new int[] {180, 120}, new int[] {240, 240}, new int[] {854, 160})) {
                    var initial = presenter.present(model, true, size[0], size[1]);
                    var surface = initial.scrollSurfaces().get(0);
                    for (int offset : List.of(0, (int) surface.maximumPixels())) {
                        var file = presenter.present(model.withScrollOffset(offset), true, size[0], size[1]);
                        var status = file.texts().stream().filter(text -> text.id().equals("add.import.status")).findFirst().orElseThrow();
                        assertEquals(key, status.message().key());
                        assertEquals(UiMessage.Severity.ERROR, status.message().severity());
                        assertEquals(ViewSpec.Text.Alignment.CENTER, status.alignment());
                        assertEquals(ViewSpec.Text.Layout.WRAP, status.layout());
                        assertEquals(model.messageHeight(status.message(), status.bounds().width()), status.bounds().height());
                        assertImportStatusHasNoInfoTarget(file);
                        assertTrue(ViewNavigationPolicy.activationAction(file, "add.external.launcher").isEmpty());
                        assertTrue(ViewNavigationPolicy.activationAction(file, "add.external.mod").isEmpty());
                        assertTrue(file.widget("add.cancel").orElseThrow().enabled());
                    }
                }
                continue;
            }
            String reasonId = "add.catalog.status.info";
            var reason = view.widget(reasonId).orElseThrow(() -> new AssertionError("disabled reason needs a focusable narration consumer"));
            assertTrue(reason.enabled());
            assertEquals(ViewSpec.WidgetKind.INFO_BUTTON, reason.kind());
            assertEquals(key, reason.label().key());
            String cursor = null;
            java.util.Set<String> reached = new java.util.HashSet<>();
            for (int i = 0; i < view.navigationNodes().size() + 1; i++) {
                var next = ViewNavigationPolicy.target(view, cursor, ViewSpec.NavigationCommand.TAB_FORWARD).orElseThrow();
                cursor = next.id();
                reached.add(cursor);
            }
            assertTrue(reached.containsAll(List.of(reasonId, "add.cancel")), reached.toString());
            assertTrue(ViewNavigationPolicy.activationAction(view, reasonId).isEmpty());
            var node = view.navigationNode(reasonId).orElseThrow();
            var desired = ViewNavigationPolicy.ensureVisibleOffset(view, node);
            ViewSpec scrolled = presenter.present(model.withScrollOffset((int) Math.round(desired.orElse(0.0))), true, 240, 240);
            Bounds bounds = scrolled.widget(reasonId).orElseThrow().bounds();
            Bounds clip = scrolled.clipFor(reasonId).orElseThrow();
            assertTrue(bounds.y() >= clip.y() && bounds.bottom() <= clip.bottom());
            assertEquals(reasonId, ViewHostPolicy.pointerOwnerAt(scrolled, bounds.x() + 1, bounds.y() + 1).orElseThrow().id());
        }
    }

    private static void assertImportStatusHasNoInfoTarget(ViewSpec view) {
        String id = "add.import.status.info";
        assertTrue(view.widget(id).isEmpty(), "Import status must not produce a duplicate info widget");
        assertTrue(view.navigationNode(id).isEmpty());
        assertTrue(view.focusRequest().filter(request -> request.widgetId().equals(id)).isEmpty());
        assertTrue(view.tooltipRegions().stream().noneMatch(region -> region.id().equals(id)));
        assertTrue(view.iconDecorations().stream().noneMatch(icon -> icon.id().equals(id)));
        var status = view.texts().stream().filter(text -> text.id().equals("add.import.status")).findFirst().orElseThrow();
        assertEquals(1, view.texts().stream().filter(text -> text.id().equals(status.id())).count());
        assertTrue(view.widgets().stream().noneMatch(widget -> widget.hint().filter(status.message()::equals).isPresent()));
        int formerX = Math.max(0, status.bounds().x() - 16);
        for (int x = formerX; x < formerX + 14; x++) for (int y = status.bounds().y(); y < status.bounds().y() + 20; y++) {
            assertTrue(ViewHostPolicy.pointerOwnerAt(view, x, y).isEmpty(), "former status icon area must not own pointer input");
        }
        for (var command : List.of(ViewSpec.NavigationCommand.TAB_FORWARD, ViewSpec.NavigationCommand.TAB_BACKWARD)) {
            String cursor = null;
            for (int i = 0; i <= view.navigationNodes().size() + view.widgets().size(); i++) {
                var next = ViewNavigationPolicy.target(view, cursor, command);
                if (next.isEmpty()) break;
                cursor = next.orElseThrow().id();
                assertNotEquals(id, cursor);
            }
        }
    }

    @Test
    void readOnlySkinCardsRemoveActionReserveAndMatchPersonalPreviewHeight() {
        SkinCatalogSource.SkinDescriptor item = skin(hash('a'), "Skin", SkinModel.CLASSIC);
        List<SkinCatalogSource.CollectionDescriptor> collections = List.of(
                personalCollection(item), orderedCollection("pack", 0, item),
                new SkinCatalogSource.CollectionDescriptor("vanilla", CatalogText.literal("Vanilla"),
                        Optional.empty(), Optional.empty(), List.of(item), CatalogCollectionOrder.vanilla("minecraft")));
        for (int height : new int[] {191, 240, 480}) {
            for (int footer : new int[] {33, 38}) {
                int personalHeight = Math.max(72, Math.min(132, height - 86 - footer));
                for (SkinCatalogSource.CollectionDescriptor collection : collections) {
                    boolean personal = collection.order().kind() == CatalogCollectionOrder.Kind.PERSONAL;
                    ViewSpec view = presenter.present(openCatalog(collection), false, Optional.empty(),
                            320, height, Optional.empty(), new ViewChromeMetrics(footer));
                    String id = "add.catalog.skin:" + collection.id() + ":" + item.id();
                    Bounds card = view.widget(id).orElseThrow().bounds();
                    assertEquals(new Bounds(16, 78, 68, personalHeight - (personal ? 0 : 23)), card);
                    Bounds preview = view.previews().get(0).bounds();
                    assertEquals(new Bounds(21, 98, 58, personalHeight - 48), preview);
                    assertTrue(preview.bottom() <= card.bottom() - 5);
                    assertEquals(card, view.navigationNode(id).orElseThrow().bounds());
                }
            }
        }
    }

    @Test
    void mixedSkinCollectionsUseActualRowHeightsForHeadersAndScroll() {
        SkinCatalogSource.SkinDescriptor[] items = IntStream.range(0, 5)
                .mapToObj(index -> skin(hash((char) ('a' + index)), "Skin " + index, SkinModel.CLASSIC))
                .toArray(SkinCatalogSource.SkinDescriptor[]::new);
        AddSourceModel model = openCatalog(List.of(personalCollection(items), orderedCollection("pack", 0, items),
                new SkinCatalogSource.CollectionDescriptor("vanilla", CatalogText.literal("Vanilla"),
                        Optional.empty(), Optional.empty(), List.of(items), CatalogCollectionOrder.vanilla("minecraft"))));
        ViewSpec view = presenter.present(model, false, 320, 240);
        assertEquals(340, view.navigationNode("add.catalog.collection:pack").orElseThrow().bounds().y());
        assertEquals(576, view.navigationNode("add.catalog.collection:vanilla").orElseThrow().bounds().y());
        assertEquals(605, presenter.present(model, false, 320, 240).scrollSurface("add.catalog").orElseThrow().maximumPixels());
        ViewSpec end = presenter.present(model.withScrollOffset(605), false, 320, 240);
        Bounds last = end.navigationNode("add.catalog.skin:vanilla:" + items[4].id()).orElseThrow().bounds();
        assertEquals(193, last.bottom());
        assertEquals(98, last.height());
        assertEquals(389, presenter.present(model.withCollectionCollapsed("pack", true), false, 320, 240).scrollSurface("add.catalog").orElseThrow().maximumPixels());
    }

    @Test
    void catalogWorkspaceUsesAvailableColumnsAndWrapsWithoutStretchingShortCollections() {
        for (int count : new int[] {12, 60}) {
            AddSourceModel model = openCatalog(collection("minecraft", IntStream.range(0, count)
                    .mapToObj(index -> skin("skin-" + index, "Skin " + index, SkinModel.CLASSIC))
                    .toArray(SkinCatalogSource.SkinDescriptor[]::new)));
            for (int[] dimensions : new int[][] {{320, 4, 68}, {854, 11, 69}, {3840, 51, 68}}) {
                ViewSpec view = presenter.present(model, false, dimensions[0], 480);
                List<Bounds> cards = view.navigationNodes().stream()
                        .filter(node -> node.id().startsWith("add.catalog.skin:minecraft:"))
                        .map(ViewSpec.NavigationNode::bounds).toList();
                int columns = dimensions[1];
                assertEquals(count, cards.size());
                assertEquals(Math.min(count, columns), cards.stream().map(Bounds::x).distinct().count());
                assertEquals((count + columns - 1) / columns,
                        cards.stream().map(Bounds::y).distinct().count());
                for (int index = 0; index < count; index++) {
                    assertEquals(new Bounds(16 + index % columns * (dimensions[2] + 6),
                            78 + index / columns * 115, dimensions[2], 109), cards.get(index));
                    assertTrue(cards.get(index).right() <= dimensions[0] - 14);
                }
                if (count >= columns) {
                    int remainder = dimensions[0] - 14 - cards.get(columns - 1).right();
                    assertTrue(remainder >= 0 && remainder < columns);
                }
            }
        }
    }

    @Test
    void catalogHidingRemovesOnlyIncompatibleVariantsAndMarkersAreFocusable() {
        SkinCatalogSource.CollectionDescriptor mixed = collection(
                "mixed", skin("dual", "Dual", SkinModel.CLASSIC, SkinModel.SLIM));
        SkinCatalogSource.CollectionDescriptor blocked = collection(
                "blocked", skin("only", "Only", SkinModel.CLASSIC));
        SkinExtensionEnvironment environment = new SkinExtensionEnvironment(
                4, Map.of(SkinConsumer.FRESH_MOVES, SkinConsumerState.ACTIVE));
        SkinFeatureEvidence conflict = new SkinFeatureEvidence(
                List.of(), List.of(SkinConflictReason.MALFORMED_EXPRESSIVE_DATA));
        Map<String, SkinFeatureEvidence> evidence = Map.of(
                "mixed:dual:CLASSIC", conflict,
                "mixed:dual:SLIM", new SkinFeatureEvidence(
                        List.of(
                                SkinFeature.EARS,
                                SkinFeature.FRESH_MOVES,
                                SkinFeature.JUST_EXPRESSIONS),
                        List.of()),
                "blocked:only:CLASSIC", conflict);

        AddSourceModel hidden = openCatalog(List.of(mixed, blocked))
                .withCompatibilityContext(environment, evidence, true);
        assertEquals(List.of("mixed"), hidden.visibleCollections().stream()
                .map(SkinCatalogSource.CollectionDescriptor::id).toList());
        SkinCatalogSource.SkinDescriptor remaining = hidden.visibleSkins(mixed).get(0);
        assertEquals(List.of(SkinModel.SLIM), remaining.models());
        assertEquals(SkinVariant.SLIM, hidden.selectedVariant(remaining));

        ViewSpec hiddenView = presenter.present(hidden, false, 854, 480);
        String sparkleId = "add.catalog.skin:mixed:dual.compatibility";
        assertEquals(
                ViewSpec.WidgetKind.PASSIVE_INDICATOR,
                hiddenView.widget(sparkleId).orElseThrow().kind());
        assertTrue(hiddenView.widget(sparkleId).orElseThrow().enabled());
        assertEquals(
                Optional.of(hiddenView.widget(sparkleId).orElseThrow().label()),
                hiddenView.widget(sparkleId).orElseThrow().hint());
        Bounds sparkleCard = hiddenView.widget("add.catalog.skin:mixed:dual").orElseThrow().bounds();
        Bounds sparkleBounds = hiddenView.widget(sparkleId).orElseThrow().bounds();
        assertEquals(sparkleCard.x() + 2, sparkleBounds.x());
        assertEquals(sparkleCard.bottom() - 2, sparkleBounds.bottom());
        assertTrue(hiddenView.widget("add.catalog.collection:blocked").isEmpty());

        AddSourceModel visible = openCatalog(List.of(mixed, blocked))
                .withCompatibilityContext(environment, evidence, false);
        ViewSpec visibleView = presenter.present(visible, false, 854, 480);
        String warningId = "add.catalog.skin:mixed:dual.compatibility";
        assertEquals(
                Optional.of(visibleView.widget(warningId).orElseThrow().label()),
                visibleView.widget(warningId).orElseThrow().hint());
        assertEquals(
                Optional.of(GuiIcon.STATUS_COMPATIBILITY_INCOMPATIBLE),
                visibleView.widget(warningId).orElseThrow().icon());
        assertEquals(20, visibleView.widget(warningId).orElseThrow().bounds().width());
        assertTrue(visibleView.iconDecorations().stream().noneMatch(icon ->
                icon.ownerWidgetId().equals(warningId)));
        assertTrue(visibleView.widget("add.catalog.collection:blocked").isPresent());
    }

    @Test
    void catalogHidesSneakyVariantOnlyWhenExpressiveConsumerIsActive() {
        SkinCatalogSource.CollectionDescriptor collection = collection(
                "sneaky", skin("dual", "Dual", SkinModel.CLASSIC, SkinModel.SLIM));
        SkinFeatureEvidence sneaky = new SkinFeatureEvidence(
                List.of(SkinFeature.SNEAKY_CAPES),
                List.of(SkinConflictReason.SNEAKY_FRESH_MOVES_OVERLAP,
                        SkinConflictReason.SNEAKY_JUST_EXPRESSIONS_OVERLAP));
        Map<String, SkinFeatureEvidence> evidence = Map.of("sneaky:dual:CLASSIC", sneaky);
        AddSourceModel active = openCatalog(List.of(collection)).withCompatibilityContext(
                new SkinExtensionEnvironment(6,
                        Map.of(SkinConsumer.FRESH_MOVES, SkinConsumerState.ACTIVE)), evidence, true);
        assertEquals(List.of(SkinModel.SLIM), active.visibleSkins(collection).get(0).models());
        AddSourceModel inactive = openCatalog(List.of(collection)).withCompatibilityContext(
                new SkinExtensionEnvironment(7,
                        Map.of(SkinConsumer.FRESH_MOVES, SkinConsumerState.INACTIVE)), evidence, true);
        assertEquals(List.of(SkinModel.CLASSIC, SkinModel.SLIM),
                inactive.visibleSkins(collection).get(0).models());
    }

    @Test
    void importTabShowsFilePlayerAndUrlWithoutStartingNetworkFromTextInput() {
        AddSourceModel model = AddSourceModel.open(
                        AccountUiPreferences.defaults(TestFixtures.ACCOUNT_ID), List.of())
                .withSelectedTab(AddSourceTab.FILE)
                .withPlayerInput("jeb_")
                .withUrlInput("https://textures.minecraft.net/texture/example");

        ViewSpec view = presenter.present(model, false, 320, 240);

        assertTrue(view.panels().stream().noneMatch(panel -> panel.id().equals("add.header")));
        assertTrue(view.panels().stream().noneMatch(panel -> panel.id().equals("add.footer")));
        assertEquals(new Bounds(0, 0, 320, 24), view.tabGroups().get(0).bounds());
        assertEquals(List.of("add.tab.catalog", "add.tab.file"),
                view.tabGroups().get(0).tabs().stream().map(ViewSpec.Tab::id).toList());
        assertTrue(view.widget("add.file.choose").orElseThrow().enabled());
        assertEquals(
                view.widget("add.file.choose").orElseThrow().bounds().width(),
                view.widget("add.external.launcher").orElseThrow().bounds().width());
        assertTrue(view.widget("add.external.mod").isPresent());
        ViewSpec.Widget playerInput = view.widget("add.player.input").orElseThrow();
        assertEquals("jeb_", playerInput.value().orElseThrow());
        assertTrue(playerInput.selectAllOnFocusAcquire());
        assertEquals(Optional.of("add.player.load"), playerInput.submitActionId());
        ViewSpec.Widget urlInput = view.widget("add.url.input").orElseThrow();
        assertTrue(urlInput.selectAllOnFocusAcquire());
        assertEquals(Optional.of("add.url.load"), urlInput.submitActionId());
        assertTrue(view.widget("add.player.load").orElseThrow().enabled());
        assertTrue(view.widget("add.url.load").orElseThrow().enabled());
        assertTrue(view.texts().stream().anyMatch(text -> text.id().equals("add.url.privacy")));
    }

    @Test
    void compactImportLayoutKeepsBothExternalActionsStatusAndCancelSeparated() {
        AddSourceModel model = AddSourceModel.open(
                        AccountUiPreferences.defaults(TestFixtures.ACCOUNT_ID), List.of())
                .withSelectedTab(AddSourceTab.FILE);
        ViewSpec view = presenter.present(
                model,
                false,
                Optional.of(UiMessage.error("nclskins.error.png")),
                240,
                240);

        List<Bounds> vertical = List.of(
                view.widget("add.player.input").orElseThrow().bounds(),
                view.widget("add.url.input").orElseThrow().bounds(),
                view.texts().stream().filter(text -> text.id().equals("add.url.privacy"))
                        .findFirst().orElseThrow().bounds(),
                view.widget("add.file.choose").orElseThrow().bounds(),
                view.widget("add.external.launcher").orElseThrow().bounds(),
                view.widget("add.external.mod").orElseThrow().bounds(),
                view.texts().stream().filter(text -> text.id().equals("add.import.status"))
                        .findFirst().orElseThrow().bounds(),
                view.widget("add.cancel").orElseThrow().bounds());
        for (int index = 1; index < vertical.size(); index++) {
            assertTrue(vertical.get(index - 1).bottom() <= vertical.get(index).y());
        }

        ViewSpec withoutPrompt = presenter.present(
                model,
                false,
                Optional.of(UiMessage.info("nclskins.external_import.choose_source")),
                240,
                240);
        assertTrue(withoutPrompt.texts().stream()
                .noneMatch(text -> text.id().equals("add.import.status")));
    }

    @Test
    void searchIsCaseInsensitiveSubstringAndFiltersOnlyAvailableModels() {
        SkinCatalogSource.CollectionDescriptor collection = collection(
                "minecraft",
                skin("steve", "Steve", SkinModel.CLASSIC, SkinModel.SLIM),
                skin("sunny", "Sunny Day", SkinModel.SLIM),
                skin("alex", "Alex", SkinModel.CLASSIC));
        AddSourceModel opened = openCatalog(collection);

        assertEquals(List.of("Steve", "Sunny Day", "Alex"), names(opened.visibleSkins(collection)));
        AddSourceModel searched = opened.withQuery("UnNy");
        assertEquals(List.of("Sunny Day"), names(searched.visibleSkins(collection)));
        assertEquals(0, searched.scrollOffset());

        AddSourceModel classic = searched.cycleFilter();
        assertTrue(classic.visibleSkins(collection).isEmpty());
        AddSourceModel slim = classic.cycleFilter();
        assertEquals(List.of("Sunny Day"), names(slim.visibleSkins(collection)));
        assertEquals(SkinVariant.SLIM, slim.selectedVariant(slim.visibleSkins(collection).get(0)));
        assertEquals(AddSourceModel.CatalogFilter.SLIM, opened.cycleFilter(true).filter());
        assertEquals(AddSourceModel.CatalogFilter.ALL,
                opened.cycleFilter().cycleFilter(true).filter());

        ViewSpec noResults = presenter.present(opened.withQuery("missing"), false, 320, 240);
        ViewSpec.Widget catalogSearch = noResults.widget("add.catalog.search").orElseThrow();
        assertTrue(catalogSearch.selectAllOnFocusAcquire());
        assertEquals(Optional.empty(), catalogSearch.submitActionId());
        assertTrue(noResults.texts().stream().anyMatch(text -> text.id().equals("add.catalog.empty")));
    }

    @Test
    void queryHidesCollectionsWithoutMatchingSkinsAndRestoresThemWhenCleared() {
        SkinCatalogSource.CollectionDescriptor forest = collection(
                "forest", skin("red_fox", "Red Fox", SkinModel.CLASSIC));
        SkinCatalogSource.CollectionDescriptor ocean = collection(
                "ocean", skin("blue_whale", "Blue Whale", SkinModel.CLASSIC));
        AddSourceModel opened = openCatalog(List.of(forest, ocean));

        AddSourceModel searched = opened.withQuery("red fox");
        ViewSpec searchedView = presenter.present(searched, false, 320, 240);
        assertEquals(
                List.of("forest"),
                searched.visibleCollections().stream()
                        .map(SkinCatalogSource.CollectionDescriptor::id)
                        .toList());
        assertTrue(searchedView.widget("add.catalog.collection:forest").isPresent());
        assertTrue(searchedView.widget("add.catalog.skin:forest:red_fox").isPresent());
        assertTrue(searchedView.widget("add.catalog.collection:ocean").isEmpty());
        assertTrue(searchedView.widget("add.catalog.skin:ocean:blue_whale").isEmpty());

        ViewSpec collapsedMatch = presenter.present(
                searched.withCollectionCollapsed("forest", true), false, 320, 240);
        assertTrue(collapsedMatch.widget("add.catalog.collection:forest").isPresent(),
                "a matching collapsed group keeps its disclosure header");
        assertTrue(collapsedMatch.widget("add.catalog.collection:ocean").isEmpty());

        AddSourceModel restoredModel = searched.withQuery("");
        ViewSpec restored = presenter.present(restoredModel, false, 854, 480);
        assertEquals(
                List.of("forest", "ocean"),
                restoredModel.visibleCollections().stream()
                        .map(SkinCatalogSource.CollectionDescriptor::id)
                        .toList());
        assertTrue(restored.widget("add.catalog.collection:forest").isPresent());
        assertTrue(restored.widget("add.catalog.collection:ocean").isPresent());
    }

    @Test
    void explicitVariantFilterHidesEmptyCollectionsAndReflowsTheCanonicalContract() {
        SkinCatalogSource.CollectionDescriptor classic = new SkinCatalogSource.CollectionDescriptor(
                "classic_only",
                "Classic only",
                Optional.of("Classic collection description"),
                Optional.of("Classic collection authors"),
                List.of(new SkinCatalogSource.SkinDescriptor(
                        "classic_skin",
                        "Classic skin",
                        Optional.of("Classic skin description"),
                        Optional.of("Classic skin authors"),
                        List.of(SkinModel.CLASSIC))));
        SkinCatalogSource.CollectionDescriptor slim = new SkinCatalogSource.CollectionDescriptor(
                "slim_only",
                "Slim only",
                Optional.of("Slim collection description"),
                Optional.of("Slim collection authors"),
                List.of(new SkinCatalogSource.SkinDescriptor(
                        "slim_skin",
                        "Slim skin",
                        Optional.of("Slim skin description"),
                        Optional.of("Slim skin authors"),
                        List.of(SkinModel.SLIM))));

        AddSourceModel classicFilter = openCatalog(List.of(classic, slim)).cycleFilter();
        ViewSpec classicView = presenter.present(classicFilter, false, 320, 240);

        assertEquals(
                List.of("classic_only"),
                classicFilter.visibleCollections().stream()
                        .map(SkinCatalogSource.CollectionDescriptor::id)
                        .toList());
        assertTrue(classicView.widget("add.catalog.collection:classic_only").isPresent());
        assertTrue(classicView.tooltipRegions().stream().anyMatch(region ->
                region.id().equals("add.catalog.tooltip:collection:classic_only")));
        assertTrue(classicView.widget("add.catalog.skin:classic_only:classic_skin").isPresent());
        assertTrue(classicView.widget("add.catalog.collection:slim_only").isEmpty());
        assertTrue(classicView.tooltipRegions().stream().noneMatch(region ->
                region.id().contains("slim_only")));
        assertTrue(classicView.widget("add.catalog.skin:slim_only:slim_skin").isEmpty());

        AddSourceModel slimFilter = classicFilter.cycleFilter();
        ViewSpec slimView = presenter.present(slimFilter, false, 320, 240);
        assertEquals(
                List.of("slim_only"),
                slimFilter.visibleCollections().stream()
                        .map(SkinCatalogSource.CollectionDescriptor::id)
                        .toList());
        assertTrue(slimView.widget("add.catalog.collection:classic_only").isEmpty());
        assertTrue(slimView.tooltipRegions().stream().noneMatch(region ->
                region.id().contains("classic_only")));
        assertTrue(slimView.widget("add.catalog.collection:slim_only").isPresent());
        assertTrue(slimView.tooltipRegions().stream().anyMatch(region ->
                region.id().equals("add.catalog.tooltip:collection:slim_only")));

        AddSourceModel noClassic = openCatalog(slim).cycleFilter();
        ViewSpec emptyView = presenter.present(noClassic, false, 320, 240);
        assertTrue(noClassic.visibleCollections().isEmpty());
        assertTrue(emptyView.widget("add.catalog.collection:slim_only").isEmpty());
        assertTrue(emptyView.tooltipRegions().isEmpty());
        assertTrue(emptyView.texts().stream().anyMatch(text -> text.id().equals("add.catalog.empty")));
        assertEquals(0, presenter.present(noClassic, false, 320, 240).scrollSurface("add.catalog").orElseThrow().maximumPixels());
        assertTrue(emptyView.scrollbar().isEmpty());

        SkinCatalogSource.CollectionDescriptor manyClassic = collection(
                "many_classic",
                IntStream.range(0, 16)
                        .mapToObj(index -> skin(
                                "classic-" + index,
                                "Classic " + index,
                                SkinModel.CLASSIC))
                        .toArray(SkinCatalogSource.SkinDescriptor[]::new));
        SkinCatalogSource.CollectionDescriptor manySlim = collection(
                "many_slim",
                IntStream.range(0, 16)
                        .mapToObj(index -> skin(
                                "slim-" + index,
                                "Slim " + index,
                                SkinModel.SLIM))
                        .toArray(SkinCatalogSource.SkinDescriptor[]::new));
        AddSourceModel mixedFilter = openCatalog(List.of(manyClassic, manySlim)).cycleFilter();
        AddSourceModel matchingOnly = openCatalog(manyClassic).cycleFilter();

        assertEquals(
                presenter.present(matchingOnly, false, 320, 240).scrollSurface("add.catalog").orElseThrow().maximumPixels(),
                presenter.present(mixedFilter, false, 320, 240).scrollSurface("add.catalog").orElseThrow().maximumPixels(),
                "hidden collections must not reserve header, metadata or scroll space");
        assertEquals(
                presenter.present(matchingOnly, false, 320, 240).scrollbar(),
                presenter.present(mixedFilter, false, 320, 240).scrollbar());
    }

    @Test
    void allPrefersClassicAndFallsBackToSlimWhenThatIsTheOnlyAvailableModel() {
        SkinCatalogSource.SkinDescriptor both = skin("both", "Both", SkinModel.CLASSIC, SkinModel.SLIM);
        SkinCatalogSource.SkinDescriptor slimOnly = skin("slim", "Slim only", SkinModel.SLIM);
        SkinCatalogSource.CollectionDescriptor collection = collection("minecraft", both, slimOnly);
        AddSourceModel model = openCatalog(collection);

        assertEquals(SkinVariant.CLASSIC, model.selectedVariant(both));
        assertEquals(SkinVariant.SLIM, model.selectedVariant(slimOnly));
        assertEquals(SkinVariant.CLASSIC, model.cycleFilter().selectedVariant(both));
        assertEquals(SkinVariant.SLIM, model.cycleFilter().cycleFilter().selectedVariant(slimOnly));
    }

    @Test
    void nativeTabsFocusCatalogSearchAndRememberCollapsedCollectionsInModel() {
        SkinCatalogSource.CollectionDescriptor collection = collection(
                "minecraft", skin("steve", "Steve", SkinModel.CLASSIC, SkinModel.SLIM));
        AddSourceModel model = AddSourceModel.open(
                new AccountUiPreferences(
                        AccountUiPreferences.CURRENT_SCHEMA_VERSION,
                        TestFixtures.ACCOUNT_ID,
                        AddSourceTab.FILE,
                        Set.of()),
                List.of(collection));

        AddSourceModel catalog = model.withSelectedTab(AddSourceTab.CATALOG);
        ViewSpec view = presenter.present(catalog, false, 320, 240);

        assertEquals("add_source", view.screenId());
        assertEquals(1, view.tabGroups().size());
        assertEquals(
                List.of("add.tab.catalog", "add.tab.file"),
                view.tabGroups().get(0).tabs().stream().map(ViewSpec.Tab::id).toList());
        assertTrue(view.tabGroups().get(0).tabs().get(0).selected());
        assertEquals("add.catalog.search", view.focusRequest().orElseThrow().widgetId());
        assertEquals(1L, view.focusRequest().orElseThrow().token());
        assertTrue(view.panels().stream().anyMatch(panel ->
                panel.id().startsWith("add.catalog.skin:")
                        && panel.style() == ViewSpec.Panel.Style.VANILLA_LIST));

        AddSourceModel collapsed = catalog.withCollectionCollapsed("minecraft", true);
        assertTrue(collapsed.collectionCollapsed("minecraft"));
        assertTrue(collapsed.collapsedCollectionIds().contains("minecraft"));
        ViewSpec collapsedView = presenter.present(collapsed, false, 320, 240);
        assertTrue(collapsedView.previews().isEmpty());
        assertTrue(
                collapsedView.texts().stream().noneMatch(text -> text.id().equals("add.catalog.empty")),
                "collapsing a non-empty collection must not claim that search has no matches");
        assertEquals(
                ViewSpec.WidgetKind.COLLECTION_HEADER,
                collapsedView.widget("add.catalog.collection:minecraft").orElseThrow().kind());
    }

    @Test
    void catalogBulkDisclosureUsesAllNonemptyCollectionsBeforeFilteringAndPreservesOtherState() {
        SkinCatalogSource.CollectionDescriptor minecraft = collection(
                "minecraft", skin("steve", "Steve", SkinModel.CLASSIC));
        SkinCatalogSource.CollectionDescriptor event = collection(
                "event", skin("fox", "Fox", SkinModel.SLIM));
        SkinCatalogSource.CollectionDescriptor empty = collection("empty");
        AddSourceModel model = AddSourceModel.open(
                        new AccountUiPreferences(
                                AccountUiPreferences.CURRENT_SCHEMA_VERSION,
                                TestFixtures.ACCOUNT_ID,
                                AddSourceTab.CATALOG,
                                Set.of("future:unknown")),
                        List.of(minecraft, event, empty))
                .withQuery("Steve")
                .cycleFilter()
                .withScrollOffset(42);

        AddSourceModel collapsed = model.withAvailableCollectionsCollapsed(true);
        assertEquals(Set.of("minecraft", "event", "future:unknown"),
                collapsed.collapsedCollectionIds());
        assertEquals(Set.of("minecraft", "event"), collapsed.availableCollectionIds());
        assertTrue(collapsed.anyAvailableCollectionCollapsed());
        assertEquals(model.query(), collapsed.query());
        assertEquals(model.filter(), collapsed.filter());
        assertEquals(model.scrollOffset(), collapsed.scrollOffset());

        AddSourceModel expanded = collapsed.withAvailableCollectionsCollapsed(false);
        assertEquals(Set.of("future:unknown"), expanded.collapsedCollectionIds());
        assertFalse(expanded.anyAvailableCollectionCollapsed());
    }

    @Test
    void catalogDisclosureIsTheLastHeaderControlWithStableIconAndAccessibleCopy() {
        AddSourceModel model = openCatalog(collection(
                "minecraft", skin("steve", "Steve", SkinModel.CLASSIC)));
        ViewSpec view = presenter.present(model, false, 240, 240);
        ViewSpec.Widget search = view.widget("add.catalog.search").orElseThrow();
        ViewSpec.Widget filter = view.widget("add.catalog.filter").orElseThrow();
        ViewSpec.Widget disclosure = view.widget("add.catalog.disclosure").orElseThrow();

        assertEquals(List.of(
                        "add.catalog.search", "add.catalog.filter", "add.catalog.disclosure"),
                view.widgets().stream().limit(3).map(ViewSpec.Widget::id).toList());
        assertEquals(ViewSpec.WidgetKind.ICON_BUTTON, disclosure.kind());
        assertEquals(new Bounds(204, 31, 20, 20), disclosure.bounds());
        assertEquals(Optional.of(GuiIcon.ACTION_COLLAPSE_ALL), disclosure.icon());
        assertEquals(UiMessage.info("nclskins.collection.collapse_all"), disclosure.label());
        assertEquals(Optional.of(disclosure.label()), disclosure.hint());
        assertEquals(6, filter.bounds().x() - search.bounds().right());
        assertEquals(6, disclosure.bounds().x() - filter.bounds().right());

        ViewSpec.Widget expanded = presenter.present(
                        model.withAvailableCollectionsCollapsed(true), false, 240, 240)
                .widget("add.catalog.disclosure")
                .orElseThrow();
        assertEquals(Optional.of(GuiIcon.ACTION_EXPAND_ALL), expanded.icon());
        assertEquals(UiMessage.info("nclskins.collection.expand_all"), expanded.label());
    }

    @Test
    void catalogCardsMatchCompactGalleryStructureAcrossSupportedWidths() {
        SkinCatalogSource.CollectionDescriptor collection = collection(
                "minecraft",
                IntStream.range(0, 9)
                        .mapToObj(index -> skin(
                                "skin-" + index,
                                "Skin " + index,
                                SkinModel.CLASSIC,
                                SkinModel.SLIM))
                        .toArray(SkinCatalogSource.SkinDescriptor[]::new));
        AddSourceModel model = openCatalog(collection);

        ViewSpec narrow = presenter.present(model, false, 320, 240);
        assertCompactCatalogLayout(
                narrow,
                4,
                new Bounds(16, 58, 290, 16),
                new Bounds(16, 78, 68, 98),
                new Bounds(20, 85, 60, 10),
                new Bounds(21, 98, 58, 73));


        ViewSpec scaledDefault = presenter.present(model, false, 427, 240);
        assertCompactCatalogLayout(
                scaledDefault,
                5,
                new Bounds(16, 58, 397, 16),
                new Bounds(16, 78, 74, 98),
                new Bounds(20, 85, 66, 10),
                new Bounds(21, 98, 64, 73));

        ViewSpec canonical = presenter.present(model, false, 854, 480);
        assertCompactCatalogLayout(
                canonical,
                9,
                new Bounds(16, 58, 824, 16),
                new Bounds(16, 78, 69, 109),
                new Bounds(20, 85, 61, 10),
                new Bounds(21, 98, 59, 84));
        assertEquals(
                9,
                canonical.previews().size(),
                "the complete Mojang collection fits one row at 854 px");

        ViewSpec wide = presenter.present(model, false, 1600, 720);
        assertCompactCatalogLayout(
                wide,
                9,
                new Bounds(16, 58, 1570, 16),
                new Bounds(16, 78, 69, 109),
                new Bounds(20, 85, 61, 10),
                new Bounds(21, 98, 59, 84));
    }

    @Test
    void personalSkinCardGeometryIsStableAcrossAdaptiveHeights() {
        String personalHash = hash('p');
        AddSourceModel model = openCatalog(personalCollection(
                skin(personalHash, "Personal hero", SkinModel.CLASSIC)));

        for (int[] size : new int[][] {{320, 186}, {320, 221}, {320, 251}}) {
            ViewSpec view = presenter.present(model, false, size[0], size[1]);
            String prefix = "add.catalog.skin:" + PersonalSkinCatalog.COLLECTION_ID + ":" + personalHash;
            Bounds card = view.widget(prefix).orElseThrow().bounds();
            int expectedHeight = Math.min(132, Math.max(72, size[1] - 119));
            assertEquals(new Bounds(16, 78, 68, expectedHeight), card);
            assertEquals(new Bounds(20, 85, 60, 10), view.texts().stream()
                    .filter(text -> text.id().equals(prefix + ".name"))
                    .findFirst().orElseThrow().bounds());
            assertEquals(new Bounds(21, 98, 58, expectedHeight - 48), view.previews().stream()
                    .filter(preview -> preview.id().equals(prefix + ".preview"))
                    .findFirst().orElseThrow().bounds());
            ViewSpec.Widget rename = view.widget(
                    "add.catalog.rename:" + PersonalSkinCatalog.COLLECTION_ID + ":" + personalHash)
                    .orElseThrow();
            ViewSpec.Widget delete = view.widget(
                    "add.catalog.delete:" + PersonalSkinCatalog.COLLECTION_ID + ":" + personalHash)
                    .orElseThrow();
            CatalogCardGeometry.ActionPair legacyRenameActions = CatalogCardGeometry.renameActions(card);
            assertNotEquals(rename.bounds(), legacyRenameActions.left());
            assertNotEquals(delete.bounds(), legacyRenameActions.right());
            assertEquals(new Bounds(18, card.bottom() - 22, 31, 20), rename.bounds());
            assertEquals(new Bounds(51, card.bottom() - 22, 31, 20), delete.bounds());

            ViewSpec renamed = presenter.present(
                    model,
                    false,
                    Optional.empty(),
                    size[0],
                    size[1],
                    Optional.of(new AddSourcePresenter.PersonalSkinRename(
                            PersonalSkinCatalog.COLLECTION_ID, personalHash, "Renamed hero")));
            assertEquals(new Bounds(19, 81, 62, 20), renamed.widget("add.catalog.rename.name")
                    .orElseThrow().bounds());
            ViewSpec.Widget save = renamed.widget("add.catalog.rename.save").orElseThrow();
            ViewSpec.Widget cancel = renamed.widget("add.catalog.rename.cancel").orElseThrow();
            assertEquals(rename.bounds(), save.bounds());
            assertEquals(delete.bounds(), cancel.bounds());
            assertEquals(ViewSpec.WidgetKind.ICON_BUTTON, save.kind());
            assertEquals(ViewSpec.WidgetKind.ICON_BUTTON, cancel.kind());
            assertEquals(Optional.of(NativeGuiIcon.ACCEPT), save.icon());
            assertEquals(Optional.of(NativeGuiIcon.REJECT), cancel.icon());
            assertEquals("nclskins.your_skins.rename_save", save.label().key());
            assertEquals("gui.cancel", cancel.label().key());
            assertTrue(save.enabled());
            assertTrue(cancel.enabled());

            ViewSpec emptyRename = presenter.present(
                    model,
                    false,
                    Optional.empty(),
                    size[0],
                    size[1],
                    Optional.of(new AddSourcePresenter.PersonalSkinRename(
                            PersonalSkinCatalog.COLLECTION_ID, personalHash, "   ")));
            assertFalse(emptyRename.widget("add.catalog.rename.save").orElseThrow().enabled());
            assertTrue(emptyRename.widget("add.catalog.rename.cancel").orElseThrow().enabled());
        }
    }

    @Test
    void collectionHeaderIsThinTransparentIntentWithoutAButtonLikeFakeLine() {
        SkinCatalogSource.CollectionDescriptor collection = collection(
                "minecraft", skin("steve", "Steve", SkinModel.CLASSIC, SkinModel.SLIM));
        AddSourceModel expanded = openCatalog(collection);

        ViewSpec.Widget expandedHeader = presenter.present(expanded, false, 854, 480)
                .widget("add.catalog.collection:minecraft")
                .orElseThrow();
        assertEquals(ViewSpec.WidgetKind.COLLECTION_HEADER, expandedHeader.kind());
        assertEquals(new Bounds(16, 58, 824, 16), expandedHeader.bounds());
        assertEquals("▼ Minecraft", expandedHeader.label().key());
        assertTrue(expandedHeader.label().literal());
        assertFalse(expandedHeader.label().key().contains("─"));

        ViewSpec.Widget collapsedHeader = presenter
                .present(expanded.withCollectionCollapsed("minecraft", true), false, 854, 480)
                .widget("add.catalog.collection:minecraft")
                .orElseThrow();
        assertEquals("▶ Minecraft", collapsedHeader.label().key());
        assertEquals(new Bounds(16, 58, 824, 16), collapsedHeader.bounds());
    }

    @Test
    void catalogMetadataUsesTooltipsOnlyOverTheRenderedNames() {
        SkinCatalogSource.SkinDescriptor skin = new SkinCatalogSource.SkinDescriptor(
                "steve",
                "Steve",
                Optional.of("Skin description"),
                Optional.of("Skin authors"),
                List.of(SkinModel.CLASSIC));
        SkinCatalogSource.CollectionDescriptor collection = new SkinCatalogSource.CollectionDescriptor(
                "minecraft",
                "Minecraft",
                Optional.of("Collection description"),
                Optional.of("Collection authors"),
                List.of(skin));
        ViewSpec view = presenter.present(openCatalog(collection), false, 854, 480);
        Bounds viewport = view.clipRegions().stream()
                .filter(region -> region.id().equals("add.catalog.viewport"))
                .findFirst()
                .orElseThrow()
                .bounds();

        ViewSpec.Widget header = view.widget("add.catalog.collection:minecraft").orElseThrow();
        assertFalse(header.collectionHeaderHasTrailingInfo());
        assertTrue(view.widgets().stream().noneMatch(widget ->
                widget.kind() == ViewSpec.WidgetKind.INFO_BUTTON));
        ViewSpec.TooltipRegion collectionTooltip = view.tooltipRegions().stream()
                .filter(region -> region.id().equals("add.catalog.tooltip:collection:minecraft"))
                .findFirst()
                .orElseThrow();
        ViewSpec.TooltipRegion skinTooltip = view.tooltipRegions().stream()
                .filter(region -> region.id().equals("add.catalog.tooltip:skin:minecraft:steve"))
                .findFirst()
                .orElseThrow();
        Bounds skinName = view.texts().stream()
                .filter(text -> text.id().equals("add.catalog.skin:minecraft:steve.name"))
                .findFirst()
                .orElseThrow()
                .bounds();
        assertEquals(skinName, skinTooltip.textBounds());
        assertEquals(new Bounds(skinName.x() + (skinName.width() - 30) / 2,
                skinName.y(), 30, skinName.height()), skinTooltip.hitBounds(30));
        assertEquals(Optional.of(viewport), view.clipFor(collectionTooltip.id()));
        assertEquals(Optional.of(viewport), view.clipFor(skinTooltip.id()));
        assertEquals(
                "Collection description\nCollection authors",
                collectionTooltip.tooltip().key());
        assertEquals("Skin description\nSkin authors", skinTooltip.tooltip().key());
    }

    @Test
    void catalogHidesAmbientSessionStatusButKeepsPersonalDeleteFailure() {
        AddSourceModel model = openCatalog(MinecraftSkinCatalog.collections().get(0));

        ViewSpec offline = presenter.present(
                model,
                false,
                Optional.of(UiMessage.error("nclskins.session.offline")),
                320,
                240);
        ViewSpec deleteFailure = presenter.present(
                model,
                false,
                Optional.of(UiMessage.error("nclskins.your_skins.delete_failed")),
                320,
                240);

        assertTrue(offline.texts().stream().noneMatch(text ->
                text.id().equals("add.catalog.status")));
        assertEquals(
                "nclskins.your_skins.delete_failed",
                deleteFailure.texts().stream()
                        .filter(text -> text.id().equals("add.catalog.status"))
                        .findFirst()
                        .orElseThrow()
                        .message()
                        .key());
    }

    @Test
    void catalogCardIsADedicatedVisibleNativeActionNamedForNarration() {
        SkinCatalogSource.CollectionDescriptor collection = collection(
                "minecraft", skin("steve", "Steve", SkinModel.CLASSIC, SkinModel.SLIM));
        ViewSpec view = presenter.present(openCatalog(collection), false, 320, 240);

        ViewSpec.Widget card = view.widget("add.catalog.skin:minecraft:steve").orElseThrow();
        assertEquals(ViewSpec.WidgetKind.CATALOG_CARD, card.kind());
        assertTrue(card.visible(), "native hosts need the card in focus traversal");
        assertTrue(card.enabled());
        assertTrue(card.label().literal());
        assertEquals("Steve", card.label().key());
        assertEquals(
                view.panels().stream()
                        .filter(panel -> panel.id().equals(card.id()))
                        .findFirst()
                        .orElseThrow()
                        .bounds(),
                card.bounds());
    }

    @Test
    void catalogUsesCompactSevenPixelGapsBelowTabsAndSearchControls() {
        SkinCatalogSource.CollectionDescriptor collection = collection(
                "minecraft", skin("steve", "Steve", SkinModel.CLASSIC, SkinModel.SLIM));
        ViewSpec view = presenter.present(openCatalog(collection), false, 854, 480);

        Bounds tabs = view.tabGroups().get(0).bounds();
        Bounds search = view.widget("add.catalog.search").orElseThrow().bounds();
        Bounds filter = view.widget("add.catalog.filter").orElseThrow().bounds();
        Bounds header = view.widget("add.catalog.collection:minecraft").orElseThrow().bounds();

        assertEquals(7, search.y() - tabs.bottom());
        assertEquals(search.y(), filter.y());
        assertEquals(7, header.y() - search.bottom());
    }

    @Test
    void catalogKeepsStaticVisibleOnlyPreviewsAndVerticalScroll() {
        SkinCatalogSource.CollectionDescriptor collection = collection(
                "minecraft",
                IntStream.range(0, 40)
                        .mapToObj(index -> skin(
                                "skin-" + index,
                                "Skin " + index,
                                SkinModel.CLASSIC,
                                SkinModel.SLIM))
                        .toArray(SkinCatalogSource.SkinDescriptor[]::new));
        AddSourceModel model = openCatalog(collection);

        ViewSpec narrow = presenter.present(model, false, 320, 240);
        assertEquals(4, distinctPreviewColumns(narrow));
        assertFalse(narrow.previews().isEmpty());

        ViewSpec canonical = presenter.present(model, false, 854, 480);
        assertEquals(11, distinctPreviewColumns(canonical));
        assertTrue(canonical.previews().size() <= distinctPreviewColumns(canonical) * 4);
        assertTrue(canonical.previews().stream().allMatch(preview -> preview.catalogImage().isPresent()));
        assertTrue(canonical.previews().stream().allMatch(preview -> preview.yawDegrees() == -20.0F));
        assertTrue(canonical.previews().stream().allMatch(preview -> preview.pitchDegrees() == 0.0F));
        assertTrue(canonical.previews().stream().allMatch(preview -> preview.capeMode() == PreviewRenderer.CapeMode.OFF));
        List<ViewSpec.NavigationNode> catalogNodes = narrow.navigationNodes().stream()
                .filter(node -> node.id().startsWith("add.catalog.skin:"))
                .toList();
        long materializedCards = narrow.widgets().stream()
                .filter(widget -> widget.id().startsWith("add.catalog.skin:"))
                .count();
        assertEquals(40, catalogNodes.size());
        assertTrue(materializedCards < catalogNodes.size());
        assertTrue(narrow.previews().size() < catalogNodes.size());
        assertTrue(catalogNodes.stream().allMatch(node ->
                node.pattern() == ViewSpec.NavigationPattern.GRID
                        && node.surfaceId().equals(Optional.of("add.catalog"))));
        Bounds clip = canonical.clipRegions().stream()
                .filter(region -> region.id().equals("add.catalog.viewport"))
                .findFirst()
                .orElseThrow()
                .bounds();
        assertEquals(447, clip.bottom(), "catalog content ends exactly at the native footer top");
        assertTrue(canonical.previews().stream().allMatch(preview -> intersects(preview.bounds(), clip)));

        ViewSpec legacyChrome = presenter.present(
                model,
                false,
                Optional.empty(),
                854,
                480,
                Optional.empty(),
                new ViewChromeMetrics(38));
        Bounds legacyClip = legacyChrome.clipRegions().stream()
                .filter(region -> region.id().equals("add.catalog.viewport"))
                .findFirst()
                .orElseThrow()
                .bounds();
        assertEquals(442, legacyClip.bottom(),
                "a taller target-native footer must move the catalog viewport and clip together");
        assertEquals(legacyClip,
                legacyChrome.scrollSurface("add.catalog").orElseThrow().viewport());
        assertTrue(legacyChrome.previews().stream()
                .allMatch(preview -> intersects(preview.bounds(), legacyClip)));

        ViewSpec.Scrollbar scrollbar = canonical.scrollbar().orElseThrow();
        assertEquals(ViewSpec.Scrollbar.Orientation.VERTICAL, scrollbar.orientation());
        assertTrue(scrollbar.maximum() > 0);
        ViewSpec.ScrollSurface scrollSurface = canonical.scrollSurface("add.catalog").orElseThrow();
        assertEquals(clip, scrollSurface.viewport());
        assertEquals(ViewSpec.Scrollbar.Orientation.VERTICAL, scrollSurface.orientation());
        assertEquals(0.0, scrollSurface.offsetPixels());
        assertEquals(scrollbar.maximum(), scrollSurface.maximumPixels());
        ViewSpec.Text firstName = canonical.texts().stream()
                .filter(text -> text.id().endsWith(".name"))
                .findFirst()
                .orElseThrow();
        assertTrue(firstName.marqueeActivation().orElseThrow().focusWidgetIds().stream()
                .anyMatch(id -> id.startsWith("add.catalog.skin:")));
        int nextOffset = (int) Math.min(scrollSurface.maximumPixels(), 32);
        assertTrue(nextOffset > 0);
        ViewSpec afterNext = presenter.present(model.withScrollOffset(nextOffset), false, 320, 240);
        assertFalse(afterNext.previews().isEmpty());
        assertTrue(afterNext.previews().get(0).bounds().y() < narrow.previews().get(0).bounds().y());

        AddSourceModel scrolled = model.withScrollOffset(scrollbar.maximum());
        ViewSpec atEnd = presenter.present(scrolled, false, 854, 480);
        assertEquals(
                scrollbar.maximum(),
                atEnd.scrollSurface("add.catalog").orElseThrow().offsetPixels());
        assertFalse(atEnd.previews().isEmpty());
        assertTrue(
                atEnd.previews().get(0).bounds().y() < canonical.previews().get(0).bounds().y(),
                "pixel scrolling must move a still-visible boundary card instead of paging it away");
    }

    @Test
    void directionalNavigationReachesCollapsedHeadersBelowExpandedCollection() {
        SkinCatalogSource.CollectionDescriptor expanded = orderedCollection(
                "expanded", 0,
                IntStream.range(0, 40)
                        .mapToObj(index -> skin(
                                "skin-" + index,
                                "Skin " + index,
                                SkinModel.CLASSIC))
                        .toArray(SkinCatalogSource.SkinDescriptor[]::new));
        SkinCatalogSource.CollectionDescriptor collapsedA = orderedCollection(
                "collapsed-a", 1, skin("a", "A", SkinModel.CLASSIC));
        SkinCatalogSource.CollectionDescriptor collapsedB = orderedCollection(
                "collapsed-b", 2, skin("b", "B", SkinModel.CLASSIC));
        AddSourceModel model = openCatalog(List.of(expanded, collapsedA, collapsedB))
                .withCollectionCollapsed("collapsed-a", true)
                .withCollectionCollapsed("collapsed-b", true);

        ViewSpec view = presenter.present(model, false, 320, 240);
        ViewSpec.NavigationNode lastCard = view.navigationNodes().stream()
                .filter(node -> node.id().startsWith("add.catalog.skin:expanded:"))
                .max(java.util.Comparator.comparingInt(node -> node.bounds().y()))
                .orElseThrow();
        ViewSpec.NavigationNode firstHeader = view.navigationNode(
                "add.catalog.collection:collapsed-a").orElseThrow();
        ViewSpec.NavigationNode secondHeader = view.navigationNode(
                "add.catalog.collection:collapsed-b").orElseThrow();

        assertTrue(view.widget(firstHeader.id()).isEmpty(), "offscreen header is logical only");
        assertEquals(
                firstHeader.id(),
                ViewNavigationPolicy.target(view, lastCard.id(), ViewSpec.NavigationCommand.DOWN)
                        .orElseThrow().id());
        assertTrue(ViewNavigationPolicy.ensureVisibleOffset(view, firstHeader).isPresent());
        assertEquals(
                secondHeader.id(),
                ViewNavigationPolicy.target(view, firstHeader.id(), ViewSpec.NavigationCommand.DOWN)
                        .orElseThrow().id());
        assertEquals(Optional.of(firstHeader.id()), firstHeader.activationActionId());
    }

    @Test
    void partiallyVisibleCatalogHeadersCardsAndNameTooltipsRemainClippedAndInteractive() {
        List<SkinCatalogSource.SkinDescriptor> skins = IntStream.range(0, 20)
                .mapToObj(index -> new SkinCatalogSource.SkinDescriptor(
                        "skin-" + index,
                        "Skin " + index,
                        index == 0 ? Optional.of("Skin description") : Optional.empty(),
                        Optional.empty(),
                        List.of(SkinModel.CLASSIC)))
                .toList();
        SkinCatalogSource.CollectionDescriptor collection = new SkinCatalogSource.CollectionDescriptor(
                "minecraft",
                "Minecraft",
                Optional.of("Collection description"),
                Optional.empty(),
                skins);
        AddSourceModel model = openCatalog(collection);

        ViewSpec partialHeader = presenter.present(model.withScrollOffset(2), false, 320, 240);
        Bounds viewport = partialHeader.clipRegions().stream()
                .filter(region -> region.id().equals("add.catalog.viewport"))
                .findFirst()
                .orElseThrow()
                .bounds();
        for (String id : List.of("add.catalog.collection:minecraft")) {
            ViewSpec.Widget widget = partialHeader.widget(id).orElseThrow();
            assertTrue(widget.bounds().y() < viewport.y());
            assertTrue(intersects(widget.bounds(), viewport));
            assertEquals(Optional.of(viewport), partialHeader.clipFor(id));
        }
        ViewSpec.TooltipRegion headerTooltip = partialHeader.tooltipRegions().stream()
                .filter(region -> region.id().equals("add.catalog.tooltip:collection:minecraft"))
                .findFirst().orElseThrow();
        assertEquals(Optional.of(viewport), partialHeader.clipFor(headerTooltip.id()));

        ViewSpec headerSliver = presenter.present(model.withScrollOffset(15), false, 320, 240);
        assertTrue(headerSliver.widget("add.catalog.collection:minecraft").isPresent());
        assertTrue(headerSliver.tooltipRegions().stream().anyMatch(region ->
                region.id().equals("add.catalog.tooltip:collection:minecraft")));

        ViewSpec partialCard = presenter.present(model.withScrollOffset(24), false, 320, 240);
        for (String id : List.of("add.catalog.skin:minecraft:skin-0")) {
            ViewSpec.Widget widget = partialCard.widget(id).orElseThrow();
            assertTrue(widget.bounds().y() < viewport.y());
            assertTrue(intersects(widget.bounds(), viewport));
            assertEquals(Optional.of(viewport), partialCard.clipFor(id));
        }
        ViewSpec.TooltipRegion skinTooltip = partialCard.tooltipRegions().stream()
                .filter(region -> region.id().equals("add.catalog.tooltip:skin:minecraft:skin-0"))
                .findFirst().orElseThrow();
        assertEquals(Optional.of(viewport), partialCard.clipFor(skinTooltip.id()));

        ViewSpec cardSliver = presenter.present(model.withScrollOffset(37), false, 320, 240);
        assertTrue(cardSliver.widget("add.catalog.skin:minecraft:skin-0").isPresent());
        assertTrue(cardSliver.tooltipRegions().stream().noneMatch(region ->
                region.id().equals("add.catalog.tooltip:skin:minecraft:skin-0")));
    }

    @Test
    void catalogReflowsWithoutOverflowAcrossVanillaGuiScaleViewports() {
        SkinCatalogSource.CollectionDescriptor collection = collection(
                "minecraft",
                IntStream.range(0, 20)
                        .mapToObj(index -> skin(
                                "skin-" + index,
                                "Skin " + index,
                                SkinModel.CLASSIC,
                                SkinModel.SLIM))
                        .toArray(SkinCatalogSource.SkinDescriptor[]::new));
        AddSourceModel model = openCatalog(collection);


        List<Bounds> scaledViewports = List.of(
                new Bounds(0, 0, 320, 240),
                new Bounds(0, 0, 427, 240),
                new Bounds(0, 0, 640, 360),
                new Bounds(0, 0, 1280, 720));

        long previousColumns = 0;
        for (Bounds viewport : scaledViewports) {
            ViewSpec initial = presenter.present(model, false, viewport.width(), viewport.height());
            assertViewFitsViewport(initial);
            assertFalse(initial.previews().isEmpty());

            long columns = distinctPreviewColumns(initial);
            assertTrue(columns >= previousColumns, "wider scaled viewports must not lose columns");
            previousColumns = columns;

            int offset = 0;
            while (true) {
                ViewSpec scrolled = presenter.present(
                        model.withScrollOffset(offset), false, viewport.width(), viewport.height());
                assertViewFitsViewport(scrolled);
                assertFalse(scrolled.previews().isEmpty(), "every pixel offset must expose clipped card content");

                int next = (int) Math.min(scrolled.scrollSurface("add.catalog").orElseThrow().maximumPixels(), offset + 32);
                if (next == offset) {
                    assertEquals(
                            scrolled.scrollSurface("add.catalog").orElseThrow().maximumPixels(),
                            offset);
                    break;
                }
                assertTrue(next > offset, "pixel scrolling must advance monotonically");
                offset = next;
            }
        }
    }

    @Test
    void resourceCollectionsFollowMenuOrderResolveVanillaTextLazilyAndKeepMinecraftLast() {
        AtomicReference<Map<String, String>> language = new AtomicReference<>(Map.of(
                "nclskins.alpha.name", "Zulu",
                "nclskins.beta.name", "Beta translated",
                "nclskins.unknown_a.name", "Omega",
                "nclskins.unknown_b.name", "Alpha",
                "nclskins.alpha.skin.one.name", "Second",
                "nclskins.alpha.skin.two.name", "First"));
        TextResolver resolver = message -> language.get().getOrDefault(message.key(), message.key());
        var alpha = localizedCollection("alpha", "pack-a", 0, "one", "two");
        var beta = localizedCollection("beta", "pack-b", 1, "skin");
        var unknownA = localizedCollection("unknown_a", "folder-a", -1, "skin");
        var unknownB = localizedCollection("unknown_b", "folder-b", -1, "skin");
        AddSourceModel model = AddSourceModel.open(
                new AccountUiPreferences(
                        AccountUiPreferences.CURRENT_SCHEMA_VERSION,
                        TestFixtures.ACCOUNT_ID,
                        AddSourceTab.CATALOG,
                        Set.of()),
                List.of(MinecraftSkinCatalog.collections().get(0), unknownA, beta, unknownB, alpha),
                SkinVariant.CLASSIC,
                resolver);

        assertEquals(
                List.of("alpha", "beta", "unknown_b", "unknown_a", "minecraft"),
                model.collections().stream()
                        .map(SkinCatalogSource.CollectionDescriptor::id)
                        .toList());
        assertEquals(List.of("two", "one"), model.visibleSkins(alpha).stream()
                .map(SkinCatalogSource.SkinDescriptor::id)
                .toList());
        assertEquals(
                List.of("two"),
                model.withQuery("fir").visibleSkins(alpha).stream()
                        .map(SkinCatalogSource.SkinDescriptor::id)
                        .toList());

        language.set(Map.of(
                "nclskins.alpha.name", "Alpha translated",
                "nclskins.beta.name", "Beta translated",
                "nclskins.unknown_a.name", "Alpha",
                "nclskins.unknown_b.name", "Omega",
                "nclskins.alpha.skin.one.name", "First",
                "nclskins.alpha.skin.two.name", "Second"));

        assertEquals(
                List.of("alpha", "beta", "unknown_a", "unknown_b", "minecraft"),
                model.collections().stream()
                        .map(SkinCatalogSource.CollectionDescriptor::id)
                        .toList());
        assertEquals(List.of("one", "two"), model.visibleSkins(alpha).stream()
                .map(SkinCatalogSource.SkinDescriptor::id)
                .toList());
        assertEquals("Alpha translated", model.collectionName(alpha));
        assertEquals("Standard", model.collectionName(MinecraftSkinCatalog.collections().get(0)));
    }

    @Test
    void personalCollectionIsAlwaysFirstAndOnlyItsCardsExposeDeleteActions() {
        String personalHash = hash('a');
        SkinCatalogSource.CollectionDescriptor personal = personalCollection(
                skin(personalHash, "Local hero", SkinModel.CLASSIC, SkinModel.SLIM));
        SkinCatalogSource.CollectionDescriptor resource = localizedCollection(
                "alpha", "pack-a", 0, "external");
        AddSourceModel model = AddSourceModel.open(
                new AccountUiPreferences(
                        AccountUiPreferences.CURRENT_SCHEMA_VERSION,
                        TestFixtures.ACCOUNT_ID,
                        AddSourceTab.CATALOG,
                        Set.of()),
                List.of(MinecraftSkinCatalog.collections().get(0), resource, personal));

        assertEquals(
                List.of(PersonalSkinCatalog.COLLECTION_ID, "alpha", "minecraft"),
                model.collections().stream()
                        .map(SkinCatalogSource.CollectionDescriptor::id)
                        .toList());

        ViewSpec view = presenter.present(model, false, 1600, 720);
        ViewSpec.Widget delete = view.widget("add.catalog.delete:"
                + PersonalSkinCatalog.COLLECTION_ID + ":" + personalHash).orElseThrow();
        assertEquals(ViewSpec.WidgetKind.ICON_BUTTON, delete.kind());
        assertEquals("nclskins.your_skins.delete", delete.label().key());
        assertEquals(List.of(), delete.label().arguments());
        assertTrue(delete.visible());
        assertTrue(delete.enabled());
        ViewSpec.Widget rename = view.widget("add.catalog.rename:"
                + PersonalSkinCatalog.COLLECTION_ID + ":" + personalHash).orElseThrow();
        assertEquals(ViewSpec.WidgetKind.ICON_BUTTON, rename.kind());
        assertEquals(Optional.of(GuiIcon.ACTION_RENAME), rename.icon());
        assertEquals(Optional.of(GuiIcon.ACTION_DELETE), delete.icon());
        assertTrue(view.widget("add.catalog.skin:alpha:external").isPresent());
        assertTrue(view.widget("add.catalog.delete:external").isEmpty());

        Bounds card = view.widget("add.catalog.skin:"
                        + PersonalSkinCatalog.COLLECTION_ID
                        + ":"
                        + personalHash)
                .orElseThrow()
                .bounds();
        assertTrue(card.contains(delete.bounds().x(), delete.bounds().y()));
        assertTrue(card.contains(delete.bounds().right() - 1, delete.bounds().bottom() - 1));
        assertEquals(2, rename.bounds().x() - card.x());
        assertEquals(2, delete.bounds().x() - rename.bounds().right());
        assertEquals(2, card.right() - delete.bounds().right());
    }

    @Test
    void personalRenameDisablesActionsOnOtherCatalogCards() {
        String firstHash = hash('a');
        String secondHash = hash('b');
        AddSourceModel model = openCatalog(List.of(personalCollection(
                skin(firstHash, "First", SkinModel.CLASSIC),
                skin(secondHash, "Second", SkinModel.CLASSIC))));

        ViewSpec view = presenter.present(
                model,
                false,
                Optional.empty(),
                854,
                480,
                Optional.of(new AddSourcePresenter.PersonalSkinRename(
                        PersonalSkinCatalog.COLLECTION_ID, firstHash, "First")));

        assertTrue(view.widget("add.catalog.rename.name").orElseThrow().enabled());
        assertFalse(view.widget("add.catalog.rename:"
                + PersonalSkinCatalog.COLLECTION_ID + ':' + secondHash).orElseThrow().enabled());
        assertFalse(view.widget("add.catalog.delete:"
                + PersonalSkinCatalog.COLLECTION_ID + ':' + secondHash).orElseThrow().enabled());
        assertTrue(view.navigationNodes().stream()
                .filter(node -> node.id().startsWith("add.catalog.skin:"))
                .allMatch(node -> !node.enabled()));
    }

    @Test
    void partiallyVisiblePersonalRenameOverlayUsesTheCatalogViewportClip() {
        String personalHash = hash('c');
        SkinCatalogSource.CollectionDescriptor personal = personalCollection(
                skin(personalHash, "Local hero", SkinModel.CLASSIC));
        AddSourceModel model = openCatalog(List.of(
                personal, MinecraftSkinCatalog.collections().get(0)));

        ViewSpec view = presenter.present(
                model.withScrollOffset(24),
                false,
                Optional.empty(),
                320,
                240,
                Optional.of(new AddSourcePresenter.PersonalSkinRename(
                        PersonalSkinCatalog.COLLECTION_ID, personalHash, "Renamed hero")));
        Bounds viewport = view.clipRegions().stream()
                .filter(region -> region.id().equals("add.catalog.viewport"))
                .findFirst()
                .orElseThrow()
                .bounds();

        for (String id : List.of(
                "add.catalog.rename.name",
                "add.catalog.rename.save",
                "add.catalog.rename.cancel")) {
            ViewSpec.Widget overlay = view.widget(id).orElseThrow();
            assertEquals(Optional.of(viewport), view.clipFor(id));
        }
        ViewSpec.Widget renameField = view.widget("add.catalog.rename.name").orElseThrow();
        assertTrue(renameField.bounds().y() < viewport.y());
        assertTrue(renameField.selectAllOnFocusAcquire());
        assertEquals(Optional.of("add.catalog.rename.save"), renameField.submitActionId());
        String nameId = "add.catalog.skin:"
                + PersonalSkinCatalog.COLLECTION_ID
                + ":"
                + personalHash
                + ".name";
        assertTrue(view.texts().stream().noneMatch(text -> text.id().equals(nameId)));
        assertTrue(view.tooltipRegions().stream().noneMatch(region ->
                region.id().endsWith(":" + personalHash)));

        ViewSpec topActionsClipped = presenter.present(
                model.withScrollOffset(43),
                false,
                Optional.empty(),
                320,
                240,
                Optional.of(new AddSourcePresenter.PersonalSkinRename(
                        PersonalSkinCatalog.COLLECTION_ID, personalHash, "Renamed hero")));
        assertTrue(topActionsClipped.widget("add.catalog.skin:"
                        + PersonalSkinCatalog.COLLECTION_ID
                        + ":"
                        + personalHash)
                .isPresent());
        assertTrue(topActionsClipped.widget("add.catalog.rename:"
                + PersonalSkinCatalog.COLLECTION_ID + ":" + personalHash).isEmpty());
        assertTrue(topActionsClipped.widget("add.catalog.delete:"
                + PersonalSkinCatalog.COLLECTION_ID + ":" + personalHash).isEmpty());
        assertTrue(topActionsClipped.widget("add.catalog.rename.name").isEmpty());
        for (String id : List.of("add.catalog.rename.save", "add.catalog.rename.cancel")) {
            ViewSpec.Widget overlay = topActionsClipped.widget(id).orElseThrow();
            assertTrue(intersects(overlay.bounds(), viewport));
            assertEquals(Optional.of(viewport), topActionsClipped.clipFor(id));
        }
    }

    @Test
    void partiallyVisiblePersonalDeleteConfirmationUsesTheCatalogViewportClip() {
        String topHash = hash('d');
        SkinCatalogSource.SkinDescriptor topSkin =
                skin(topHash, "Top hero", SkinModel.CLASSIC);
        SkinCatalogSource.CollectionDescriptor topCollection = personalCollection(topSkin);
        AddSourceModel topModel = openCatalog(List.of(
                topCollection, MinecraftSkinCatalog.collections().get(0)))
                .withScrollOffset(120);
        ViewSpec topActionView = presenter.present(topModel, false, 320, 240);
        Bounds viewport = topActionView.clipRegions().stream()
                .filter(region -> region.id().equals("add.catalog.viewport"))
                .findFirst()
                .orElseThrow()
                .bounds();
        String topActionId = "add.catalog.delete:"
                + PersonalSkinCatalog.COLLECTION_ID + ":" + topHash;
        ViewSpec.Widget topAction = topActionView.widget(topActionId).orElseThrow();
        assertTrue(topAction.bounds().y() < viewport.y());
        assertEquals(Optional.of(viewport), topActionView.clipFor(topActionId));

        ViewSpec topConfirmation = presenter.present(
                topModel.requestPersonalSkinDeletion(topCollection, topSkin), false, 320, 240);
        for (String id : List.of("add.catalog.delete.confirm", "add.catalog.delete.cancel")) {
            ViewSpec.Widget overlay = topConfirmation.widget(id).orElseThrow();
            assertTrue(overlay.bounds().y() < viewport.y());
            assertTrue(intersects(overlay.bounds(), viewport));
            assertEquals(Optional.of(viewport), topConfirmation.clipFor(id));
        }

        List<SkinCatalogSource.SkinDescriptor> bottomSkins = IntStream.range(0, 5)
                .mapToObj(index -> skin(
                        hash((char) ('1' + index)),
                        "Bottom hero " + index,
                        SkinModel.CLASSIC))
                .toList();
        SkinCatalogSource.CollectionDescriptor bottomCollection = personalCollection(
                bottomSkins.toArray(SkinCatalogSource.SkinDescriptor[]::new));
        SkinCatalogSource.SkinDescriptor bottomSkin = bottomSkins.get(4);
        AddSourceModel bottomModel = openCatalog(List.of(
                bottomCollection, MinecraftSkinCatalog.collections().get(0)))
                .withScrollOffset(105);
        String bottomActionId = "add.catalog.delete:"
                + PersonalSkinCatalog.COLLECTION_ID + ":" + bottomSkin.id();
        ViewSpec bottomActionView = presenter.present(bottomModel, false, 320, 240);
        ViewSpec.Widget bottomAction = bottomActionView.widget(bottomActionId).orElseThrow();
        assertTrue(bottomAction.bounds().bottom() > viewport.bottom());
        assertEquals(Optional.of(viewport), bottomActionView.clipFor(bottomActionId));

        ViewSpec bottomConfirmation = presenter.present(
                bottomModel.requestPersonalSkinDeletion(bottomCollection, bottomSkin), false, 320, 240);
        for (String id : List.of("add.catalog.delete.confirm", "add.catalog.delete.cancel")) {
            ViewSpec.Widget overlay = bottomConfirmation.widget(id).orElseThrow();
            assertTrue(overlay.bounds().bottom() > viewport.bottom());
            assertTrue(intersects(overlay.bounds(), viewport));
            assertEquals(Optional.of(viewport), bottomConfirmation.clipFor(id));
        }
    }

    @Test
    void shallowWideCatalogKeepsTheCollectionGridLeftAligned() {
        String personalHash = hash('z');
        AddSourceModel model = openCatalog(personalCollection(
                skin(personalHash, "Wide hero", SkinModel.CLASSIC)));

        ViewSpec catalog = presenter.present(model, false, 1600, 120);

        assertEquals(16, catalog.widget("add.catalog.skin:"
                + PersonalSkinCatalog.COLLECTION_ID + ":" + personalHash)
                .orElseThrow()
                .bounds()
                .x());
    }

    @Test
    void personalDeleteConfirmationPreservesTransientCatalogStateAndRestoresDeleteFocus() {
        String personalHash = hash('b');
        SkinCatalogSource.SkinDescriptor personalSkin =
                skin(personalHash, "Your hero", SkinModel.CLASSIC, SkinModel.SLIM);
        SkinCatalogSource.CollectionDescriptor personal = personalCollection(personalSkin);
        AddSourceModel unscrolled = AddSourceModel.open(
                        new AccountUiPreferences(
                                AccountUiPreferences.CURRENT_SCHEMA_VERSION,
                                TestFixtures.ACCOUNT_ID,
                                AddSourceTab.CATALOG,
                                Set.of(MinecraftSkinCatalog.COLLECTION_ID)),
                        List.of(personal, MinecraftSkinCatalog.collections().get(0)))
                .withQuery("hero")
                .cycleFilter();
        int preservedScroll = (int) presenter.present(unscrolled.withScrollOffset(5), false, 320, 240)
                .scrollSurface("add.catalog").orElseThrow().offsetPixels();
        assertEquals(5, preservedScroll, "arbitrary in-range pixel offsets must not snap to rows");
        AddSourceModel catalog = unscrolled.withScrollOffset(preservedScroll);
        assertTrue(presenter.present(catalog, false, 320, 240)
                .widget("add.catalog.delete:"
                        + PersonalSkinCatalog.COLLECTION_ID + ":" + personalHash)
                .isPresent());

        AddSourceModel confirmation = catalog.requestPersonalSkinDeletion(personal, personalSkin);
        ViewSpec confirmationView = presenter.present(confirmation, false, 320, 240);

        assertEquals("add_source", confirmationView.screenId());
        assertFalse(confirmationView.tabGroups().isEmpty());
        assertFalse(confirmationView.previews().isEmpty());
        assertEquals(catalog.query(), confirmation.query());
        assertEquals(catalog.filter(), confirmation.filter());
        assertTrue(confirmationView.widget("add.catalog.delete.confirm").orElseThrow().enabled());
        assertTrue(confirmationView.widget("add.catalog.delete.cancel").orElseThrow().enabled());
        assertEquals(ViewSpec.WidgetKind.ICON_BUTTON,
                confirmationView.widget("add.catalog.delete.confirm").orElseThrow().kind());
        assertEquals(ViewSpec.WidgetKind.ICON_BUTTON,
                confirmationView.widget("add.catalog.delete.cancel").orElseThrow().kind());
        assertEquals("nclskins.your_skins.delete_confirm",
                confirmationView.widget("add.catalog.delete.confirm").orElseThrow().label().key());
        assertEquals("gui.cancel",
                confirmationView.widget("add.catalog.delete.cancel").orElseThrow().label().key());
        assertEquals(Optional.of(NativeGuiIcon.ACCEPT),
                confirmationView.widget("add.catalog.delete.confirm").orElseThrow().icon());
        assertEquals(Optional.of(NativeGuiIcon.REJECT),
                confirmationView.widget("add.catalog.delete.cancel").orElseThrow().icon());
        ViewSpec catalogView = presenter.present(catalog, false, 320, 240);
        assertEquals(
                catalogView.widget("add.catalog.rename:" + PersonalSkinCatalog.COLLECTION_ID + ":" + personalHash)
                        .orElseThrow().bounds(),
                confirmationView.widget("add.catalog.delete.confirm").orElseThrow().bounds());
        assertEquals(
                catalogView.widget("add.catalog.delete:" + PersonalSkinCatalog.COLLECTION_ID + ":" + personalHash)
                        .orElseThrow().bounds(),
                confirmationView.widget("add.catalog.delete.cancel").orElseThrow().bounds());
        assertEquals(
                Optional.of("add.catalog.delete.confirm"),
                ViewNavigationPolicy.activationAction(
                        confirmationView, "add.catalog.delete.confirm"));
        assertEquals(
                "add.catalog.delete.cancel",
                confirmationView.focusRequest().orElseThrow().widgetId());
        assertTrue(confirmation.focusToken().orElseThrow() > catalog.focusToken().orElseThrow());
        assertTrue(confirmationView.widget("add.catalog.rename:"
                + PersonalSkinCatalog.COLLECTION_ID + ":" + personalHash).isEmpty());
        assertTrue(confirmationView.widget("add.catalog.delete:"
                + PersonalSkinCatalog.COLLECTION_ID + ":" + personalHash).isEmpty());

        ViewSpec busyView = presenter.present(confirmation, true, 320, 240);
        assertFalse(busyView.widget("add.catalog.delete.confirm").orElseThrow().enabled());
        assertFalse(busyView.widget("add.catalog.delete.cancel").orElseThrow().enabled());

        AddSourceModel cancelled = confirmation.cancelPersonalSkinDeletion();
        ViewSpec restored = presenter.present(cancelled, false, 320, 240);
        assertTrue(cancelled.personalSkinDeletion().isEmpty());
        assertEquals(catalog.query(), cancelled.query());
        assertEquals(catalog.filter(), cancelled.filter());
        assertEquals(catalog.collapsedCollectionIds(), cancelled.collapsedCollectionIds());
        assertEquals(catalog.scrollOffset(), cancelled.scrollOffset());
        assertEquals("add_source", restored.screenId());
        assertEquals(
                "add.catalog.delete:" + PersonalSkinCatalog.COLLECTION_ID + ":" + personalHash,
                restored.focusRequest().orElseThrow().widgetId());
        assertTrue(cancelled.focusToken().orElseThrow() > confirmation.focusToken().orElseThrow());
        assertTrue(restored.widget("add.catalog.delete:"
                + PersonalSkinCatalog.COLLECTION_ID + ":" + personalHash).isPresent());
    }

    @Test
    void confirmedPersonalDeleteFocusesNextThenPreviousAndFallsBackToAHeader() {
        String firstHash = hash('1');
        String middleHash = hash('2');
        String lastHash = hash('3');
        SkinCatalogSource.SkinDescriptor first =
                skin(firstHash, "First", SkinModel.CLASSIC);
        SkinCatalogSource.SkinDescriptor middle =
                skin(middleHash, "Middle", SkinModel.CLASSIC);
        SkinCatalogSource.SkinDescriptor last =
                skin(lastHash, "Last", SkinModel.CLASSIC);
        SkinCatalogSource.CollectionDescriptor personal = personalCollection(first, middle, last);
        SkinCatalogSource.CollectionDescriptor resource = localizedCollection(
                "alpha", "pack-a", 0, "external");
        AddSourceModel model = AddSourceModel.open(
                new AccountUiPreferences(
                        AccountUiPreferences.CURRENT_SCHEMA_VERSION,
                        TestFixtures.ACCOUNT_ID,
                        AddSourceTab.CATALOG,
                        Set.of()),
                List.of(resource, personal));

        AddSourceModel removedMiddle = model.requestPersonalSkinDeletion(personal, middle)
                .removeConfirmedPersonalSkin();
        assertEquals(
                "add.catalog.skin:" + PersonalSkinCatalog.COLLECTION_ID + ":" + lastHash,
                removedMiddle.focusWidgetId().orElseThrow(),
                "the item that shifts into the removed slot is the next focus target");

        AddSourceModel removedLast = model.requestPersonalSkinDeletion(personal, last)
                .removeConfirmedPersonalSkin();
        assertEquals(
                "add.catalog.skin:" + PersonalSkinCatalog.COLLECTION_ID + ":" + middleHash,
                removedLast.focusWidgetId().orElseThrow(),
                "the previous card is used when there is no next card");

        SkinCatalogSource.CollectionDescriptor singlePersonal = personalCollection(first);
        AddSourceModel removedOnly = AddSourceModel.open(
                        new AccountUiPreferences(
                                AccountUiPreferences.CURRENT_SCHEMA_VERSION,
                                TestFixtures.ACCOUNT_ID,
                                AddSourceTab.CATALOG,
                                Set.of()),
                        List.of(resource, singlePersonal))
                .requestPersonalSkinDeletion(singlePersonal, first)
                .removeConfirmedPersonalSkin();
        assertEquals(
                "add.catalog.collection:alpha",
                removedOnly.focusWidgetId().orElseThrow(),
                "removing the collection's final card falls back to the next collection header");
        assertEquals(0, removedOnly.scrollOffset());
        assertTrue(removedOnly.collections().stream()
                .noneMatch(collection -> collection.order().kind()
                        == CatalogCollectionOrder.Kind.PERSONAL));
    }

    @Test
    void missingCatalogTranslationUsesHumanizedFallbackOnlyAfterLanguageResolution() {
        TextResolver missing = message -> message.key();
        TextResolver translated = message -> "nclskins.event.skin.red_fox.name".equals(message.key())
                ? "Red fox localized"
                : message.key();
        CatalogText text = CatalogText.skinName("event", "red_fox");

        assertEquals("Red Fox", missing.resolve(text));
        assertEquals("Red fox localized", translated.resolve(text));
    }

    private static void assertCompactCatalogLayout(
            ViewSpec view,
            int expectedColumns,
            Bounds expectedHeader,
            Bounds expectedCard,
            Bounds expectedTitle,
            Bounds expectedPreview) {
        String prefix = "add.catalog.skin:minecraft:skin-0";
        ViewSpec.Panel card = view.panels().stream()
                .filter(panel -> panel.id().equals(prefix))
                .findFirst()
                .orElseThrow();
        ViewSpec.Text title = view.texts().stream()
                .filter(text -> text.id().equals(prefix + ".name"))
                .findFirst()
                .orElseThrow();
        ViewSpec.Preview preview = view.previews().stream()
                .filter(candidate -> candidate.id().equals(prefix + ".preview"))
                .findFirst()
                .orElseThrow();
        ViewSpec.Widget hitTarget = view.widget(prefix).orElseThrow();
        ViewSpec.Widget header = view.widget("add.catalog.collection:minecraft").orElseThrow();

        assertEquals(expectedColumns, distinctPreviewColumns(view));
        assertEquals(expectedHeader, header.bounds());
        assertEquals(expectedCard, card.bounds());
        assertEquals(expectedCard, hitTarget.bounds());
        assertEquals(ViewSpec.WidgetKind.CATALOG_CARD, hitTarget.kind());
        assertTrue(
                hitTarget.visible(),
                "the transparent native card action must participate in focus traversal");
        assertEquals(expectedTitle, title.bounds());
        assertEquals(expectedPreview, preview.bounds());
        assertTrue(title.bounds().bottom() <= preview.bounds().y(), "the title belongs above the model");
        assertEquals(1, view.texts().stream().filter(text -> text.id().startsWith(prefix)).count());
        assertEquals(1, view.widgets().stream().filter(widget -> widget.id().startsWith(prefix)).count());
    }

    private static void assertViewFitsViewport(ViewSpec view) {
        view.panels().forEach(panel -> assertFitsOrClipped(view, panel.bounds(), panel.id()));
        view.texts().forEach(text -> assertFitsOrClipped(view, text.bounds(), text.id()));
        view.widgets().forEach(widget -> assertFitsOrClipped(view, widget.bounds(), widget.id()));
        view.previews().forEach(preview -> assertFitsOrClipped(view, preview.bounds(), preview.id()));
        view.tabGroups().forEach(group -> assertFits(view, group.bounds(), group.id()));
        view.scrollbar().ifPresent(scrollbar -> {
            assertFits(view, scrollbar.track(), "scrollbar.track");
            assertFits(view, scrollbar.thumb(), "scrollbar.thumb");
        });
    }

    private static void assertFits(ViewSpec view, Bounds bounds, String id) {
        assertTrue(bounds.x() >= 0, id + " starts left of the scaled viewport");
        assertTrue(bounds.y() >= 0, id + " starts above the scaled viewport");
        assertTrue(bounds.right() <= view.width(), id + " overflows the scaled viewport width");
        assertTrue(bounds.bottom() <= view.height(), id + " overflows the scaled viewport height");
    }

    private static void assertFitsOrClipped(ViewSpec view, Bounds bounds, String id) {
        Optional<Bounds> clip = view.clipFor(id);
        if (clip.isEmpty()) {
            assertFits(view, bounds, id);
            return;
        }
        assertTrue(intersects(bounds, clip.orElseThrow()), id + " must intersect its clip viewport");
        assertFits(view, clip.orElseThrow(), id + ".clip");
    }

    private static boolean intersects(Bounds left, Bounds right) {
        return left.right() > right.x()
                && left.x() < right.right()
                && left.bottom() > right.y()
                && left.y() < right.bottom();
    }

    private static AddSourceModel openCatalog(SkinCatalogSource.CollectionDescriptor collection) {
        return openCatalog(List.of(collection));
    }

    private static AddSourceModel openCatalog(
            List<SkinCatalogSource.CollectionDescriptor> collections) {
        return AddSourceModel.open(
                new AccountUiPreferences(
                        AccountUiPreferences.CURRENT_SCHEMA_VERSION,
                        TestFixtures.ACCOUNT_ID,
                        AddSourceTab.CATALOG,
                        Set.of()),
                collections);
    }

    private static void assertInfoButton(ViewSpec view, String id, Bounds viewport) {
        ViewSpec.Widget info = view.widget(id).orElseThrow();
        assertEquals(ViewSpec.WidgetKind.INFO_BUTTON, info.kind());
        assertEquals(14, info.bounds().width());
        assertEquals(14, info.bounds().height());
        assertTrue(info.icon().isEmpty());
        assertTrue(info.hint().isPresent());
        assertEquals(Optional.of(viewport), view.clipFor(id));
    }

    private static SkinCatalogSource.CollectionDescriptor collection(
            String id, SkinCatalogSource.SkinDescriptor... skins) {
        return new SkinCatalogSource.CollectionDescriptor(
                id,
                "Minecraft",
                Optional.empty(),
                Optional.empty(),
                List.of(skins));
    }

    private static SkinCatalogSource.CollectionDescriptor orderedCollection(
            String id, int sourceOrder, SkinCatalogSource.SkinDescriptor... skins) {
        return new SkinCatalogSource.CollectionDescriptor(
                id,
                CatalogText.literal("Minecraft"),
                Optional.empty(),
                Optional.empty(),
                List.of(skins),
                CatalogCollectionOrder.resourcePack(id, sourceOrder));
    }

    private static SkinCatalogSource.SkinDescriptor skin(String id, String name, SkinModel... models) {
        return new SkinCatalogSource.SkinDescriptor(
                id,
                name,
                Optional.empty(),
                Optional.empty(),
                List.of(models));
    }

    private static SkinCatalogSource.CollectionDescriptor localizedCollection(
            String id, String packId, int menuRank, String... skinIds) {
        List<SkinCatalogSource.SkinDescriptor> skins = java.util.Arrays.stream(skinIds)
                .map(skinId -> new SkinCatalogSource.SkinDescriptor(
                        skinId,
                        CatalogText.skinName(id, skinId),
                        Optional.empty(),
                        Optional.empty(),
                        List.of(SkinModel.CLASSIC)))
                .toList();
        CatalogCollectionOrder order = menuRank < 0
                ? CatalogCollectionOrder.unknownResourcePack(packId)
                : CatalogCollectionOrder.resourcePack(packId, menuRank);
        return new SkinCatalogSource.CollectionDescriptor(
                id,
                CatalogText.collectionName(id),
                Optional.empty(),
                Optional.empty(),
                skins,
                order);
    }

    private static SkinCatalogSource.CollectionDescriptor personalCollection(
            SkinCatalogSource.SkinDescriptor... skins) {
        return new SkinCatalogSource.CollectionDescriptor(
                PersonalSkinCatalog.COLLECTION_ID,
                CatalogText.translated("nclskins.your_skins.name", "Your skins"),
                Optional.empty(),
                Optional.empty(),
                List.of(skins),
                CatalogCollectionOrder.personal(PersonalSkinCatalog.SOURCE_ID));
    }

    private static String hash(char value) {
        return String.valueOf(value).repeat(64);
    }

    private static List<String> names(List<SkinCatalogSource.SkinDescriptor> skins) {
        return skins.stream().map(SkinCatalogSource.SkinDescriptor::name).toList();
    }

    private static long distinctPreviewColumns(ViewSpec view) {
        return view.previews().stream().map(preview -> preview.bounds().x()).distinct().count();
    }
}

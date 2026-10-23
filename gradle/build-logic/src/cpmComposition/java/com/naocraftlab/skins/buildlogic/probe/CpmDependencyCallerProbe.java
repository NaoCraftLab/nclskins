package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.*;
import com.naocraftlab.skins.compat.cpm.CpmModelFileAdapter;
import com.naocraftlab.skins.core.api.*;
import com.naocraftlab.skins.core.compatibility.SkinExtensionEnvironment;
import com.naocraftlab.skins.core.importing.*;
import com.naocraftlab.skins.core.model.*;
import com.naocraftlab.skins.core.png.PngValidator;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

public final class CpmDependencyCallerProbe {
    private final ArrayDeque<Runnable> workers = new ArrayDeque<>(), clients = new ArrayDeque<>();
    private final CpmModelFileAdapter adapter = new CpmModelFileAdapter();
    private final UUID account = new UUID(0, 1);
    private final byte[] template;
    private final Path source, base;
    private final CatalogImportFlow flow;
    private long generation, epoch = 1, resources = 1, world = 1, nanos;
    private boolean busy, staleResponse, unsafeResponse, oversizedResponse, invalidBody, expiredResponse;
    private int requests, status = 200;
    private EditorDraftTransfer result;
    private UiMessage feedback = UiMessage.info("nclskins.external_import.choose_source");
    private Throwable failure;

    private CpmDependencyCallerProbe(Path fixtures, Path output) throws Exception {
        this(fixtures, output, 1);
    }

    private CpmDependencyCallerProbe(Path fixtures, Path output, int count) throws Exception {
        this(fixtures, output, count, "p:synthetic");
    }

    private CpmDependencyCallerProbe(Path fixtures, Path output, int count, String link) throws Exception {
        Files.createDirectories(output);
        source = output.resolve("owned-source.cpmproject");
        try (var zip = new java.util.zip.ZipFile(fixtures.resolve("template.cpmproject").toFile());
                var out = new java.util.zip.ZipOutputStream(Files.newOutputStream(source))) {
            for (var entries = zip.entries(); entries.hasMoreElements();) {
                var entry = entries.nextElement();
                byte[] content;
                try (var stream = zip.getInputStream(entry)) { content = stream.readAllBytes(); }
                if (entry.getName().equals("templates.json") && (count != 1 || !link.equals("p:synthetic"))) {
                    var document = com.google.gson.JsonParser.parseString(new String(content, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                    var first = document.getAsJsonArray("templates").get(0).getAsJsonObject();
                    var templates = new com.google.gson.JsonArray();
                    for (int index = 0; index < count; index++) {
                        var copy = first.deepCopy();
                        copy.addProperty("link", link + (index == 0 ? "" : index));
                        templates.add(copy);
                    }
                    document.add("templates", templates);
                    content = document.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                }
                out.putNextEntry(new java.util.zip.ZipEntry(entry.getName())); out.write(content); out.closeEntry();
            }
        }
        base = fixtures.getParent().resolve("model-conversion/slim-base.png");
        template = Files.readAllBytes(fixtures.resolve("template.json"));
        var images = sourceImages(output.resolve("url-cache"), Files.readAllBytes(source));
        var service = new PublicSkinImportService(Optional.empty(), images, (c,s,v) -> { throw new AssertionError("No catalog fetch"); },
                Optional.of(CpmDependencyTransportProbe.source((uri, timeout, maximum) -> {
                    requests++;
                    check(uri.toString().equals("https://paste.tom5454.com/raw/synthetic" + (requests == 1 ? "" : requests - 1)), "Exact native URL producer");
                    check(timeout.compareTo(Duration.ofSeconds(30)) <= 0 && maximum == 128 * 1024 * 1024 - (requests - 1) * template.length, "Transport budgets");
                    if (oversizedResponse) throw new PublicHttpsImageFetcher.OversizedImageException();
                    if (staleResponse) epoch++;
                    if (expiredResponse) nanos = Duration.ofSeconds(120).toNanos();
                    return new PublicHttpsImageFetcher.Response(status, invalidBody ? new byte[]{0} : template, Map.of());
                }, () -> unsafeResponse, () -> nanos)), () -> nanos);
        var imports = (ImportOperations) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ImportOperations.class},
                (p,m,a) -> switch (m.getName()) {
                    case "acquireFile" -> service.acquireFile((Path)a[0], Optional.of(adapter), CpmModelFileConversion.Reason.CPM_UNAVAILABLE);
                    case "acquireUrl" -> service.acquireUrl((String)a[0], Optional.of(adapter), CpmModelFileConversion.Reason.CPM_UNAVAILABLE);
                    default -> throw new AssertionError("Unexpected import effect " + m.getName());
                });
        var picker = new FilePicker() {
            public CompletableFuture<Optional<Path>> chooseSkinPng() { return CompletableFuture.completedFuture(Optional.of(base)); }
            public CompletableFuture<Optional<Path>> chooseSkinImportFile(boolean allow) { return CompletableFuture.completedFuture(Optional.of(source)); }
            public CompletableFuture<Optional<Path>> chooseDirectory() { throw new AssertionError("No directory"); }
        };
        String hash = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(base)));
        var bundled = new SkinAsset(new UUID(0, 2), "Bundled fixture", hash, SkinVariant.CLASSIC,
                SkinSource.VANILLA_DEFAULT, Instant.EPOCH, Instant.EPOCH);
        var accountState = new AccountState(AccountState.CURRENT_SCHEMA_VERSION, account, List.of(bundled), List.of(), List.of(), Instant.EPOCH);
        CatalogImportFlow.Context context = (CatalogImportFlow.Context) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{CatalogImportFlow.Context.class}, (p,m,a) -> switch (m.getName()) {
                    case "captureImportScope" -> new CatalogImportFlow.ImportScope(account, epoch, resources, world);
                    case "currentImportScope", "currentPublicationScope" -> a[0] != null && ((CatalogImportFlow.ImportScope)a[0]).accountEpoch() == epoch
                            && ((CatalogImportFlow.ImportScope)a[0]).resourceEpoch() == resources
                            && ((CatalogImportFlow.ImportScope)a[0]).worldEpoch() == world;
                    case "captureEditorSaveScope" -> new LibraryEditorPort.EditorSaveScope(account, epoch, () -> true);
                    case "filePicker" -> picker;
                    case "importOperations" -> imports;
                    case "modelFileConversion" -> Optional.of(adapter);
                    case "cpmPresent" -> true;
                    case "worker" -> (Executor)workers::add;
                    case "onClient" -> { clients.add((Runnable)a[0]); yield null; }
                    case "busy" -> busy;
                    case "editor", "pendingPresetName" -> null;
                    case "disposed", "addSourceRoot" -> false;
                    case "beginScreenOperation" -> { busy = true; yield new ScreenOperationTicket(++generation); }
                    case "current" -> ((ScreenOperationTicket)a[0]).generation() == generation;
                    case "finishScreenOperation" -> { busy = false; yield null; }
                    case "cancelScreenOperation" -> { generation++; busy = false; yield null; }
                    case "textResolver" -> (TextResolver)UiMessage::key;
                    case "viewportHeight" -> 480;
                    case "preferredCapeMode" -> PreviewRenderer.CapeMode.CAPE;
                    case "account" -> accountState;
                    case "uiPreferences" -> AccountUiPreferences.defaults(account).withSelectedAddSourceTab(AddSourceTab.FILE);
                    case "editorProfile" -> Optional.empty();
                    case "editorOwnedCapes" -> List.of();
                    case "createEditor" -> PresetEditorModel.open(accountState, Optional.empty(), Optional.empty(), Optional.empty(), UiMessage::key, 480, PreviewRenderer.CapeMode.CAPE);
                    case "applyPendingPresetName" -> a[0];
                    case "acceptDraft" -> { result = (EditorDraftTransfer)a[0]; yield null; }
                    case "unwrap" -> unwrap((Throwable)a[0]);
                    case "fileImportFailure" -> { failure = unwrap((Throwable)a[0]); yield UiMessage.error("nclskins.error.png"); }
                    case "publicImportFailureKey" -> {
                        failure = unwrap((Throwable)a[0]);
                        var mapper = ClientRuntime.class.getDeclaredMethod("publicImportFailureKey", Throwable.class, boolean.class);
                        mapper.setAccessible(true); yield mapper.invoke(null, a[0], false);
                    }
                    case "status" -> feedback;
                    case "showFeedback" -> { feedback = (UiMessage)a[0]; yield null; }
                    case "publish", "diagnose", "clearRuntimeFocus" -> null;
                    default -> throw new AssertionError("Forbidden caller effect " + m.getName());
                });
        flow = new CatalogImportFlow(context);
        flow.openEmptyCatalog(AccountUiPreferences.defaults(account).withSelectedAddSourceTab(AddSourceTab.FILE),
                SkinVariant.SLIM, SkinExtensionEnvironment.unknown(0), false);
    }

    private static PublicSkinImageSource sourceImages(Path root, byte[] source) throws Exception {
        var imports = CpmUrlIngressFixture.imports(root, source);
        var images = PublicSkinImportService.class.getDeclaredField("images");
        images.setAccessible(true);
        return (PublicSkinImageSource) images.get(imports);
    }

    public static void fidelity(Path fixtures, Path output) throws Exception {
        for (var variant : SkinVariant.values()) for (boolean url : List.of(false, true)) {
            Path root = output.resolve(variant.name().toLowerCase(Locale.ROOT) + (url ? "-url" : "-file"));
            Files.createDirectories(root);
            Path source = root.resolve("source.cpmproject"), base = root.resolve("base.png");
            try (var zip = new java.util.zip.ZipFile(fixtures.resolve("template.cpmproject").toFile());
                    var out = new java.util.zip.ZipOutputStream(Files.newOutputStream(source))) {
                for (var entries = zip.entries(); entries.hasMoreElements();) {
                    var entry = entries.nextElement();
                    byte[] content;
                    try (var stream = zip.getInputStream(entry)) { content = stream.readAllBytes(); }
                    if (entry.getName().equals("config.json")) {
                        var config = com.google.gson.JsonParser.parseString(new String(content, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                        config.addProperty("skinType", variant == SkinVariant.SLIM ? "slim" : "default");
                        content = config.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    }
                    out.putNextEntry(new java.util.zip.ZipEntry(entry.getName())); out.write(content); out.closeEntry();
                }
            }
            byte[] bundled = Files.readAllBytes(fixtures.resolveSibling("model-conversion").resolve(variant == SkinVariant.SLIM ? "slim-base.png" : "classic-base.png"));
            Files.write(base, bundled);
            byte[] template = Files.readAllBytes(fixtures.resolve("template.json"));
            int[] dependencyRequests = {0};
            boolean[] available = {true};
            int sourceRequests = CpmUrlIngressFixture.requests;
            var images = sourceImages(root.resolve("url-cache"), Files.readAllBytes(source));
            var imports = new PublicSkinImportService(Optional.empty(), images, (c,s,v) -> { throw new AssertionError("No catalog fetch"); },
                    Optional.of(CpmDependencyTransportProbe.source((uri, timeout, maximum) -> {
                        check(available[0], "Dependency unavailable after explicit acquisition");
                        check(++dependencyRequests[0] == 1 && uri.toString().equals("https://paste.tom5454.com/raw/synthetic"), "Exactly one confirmed dependency terminal");
                        return new PublicHttpsImageFetcher.Response(200, template, Map.of());
                    }, () -> false, System::nanoTime)), System::nanoTime);
            Path saved = root.resolve("saved");
            String hash = CpmCallerHarness.saveThroughRuntime(new CpmModelFileAdapter(), bundled, source, base, saved, variant,
                    url ? "url-dependency" : "dependency", imports);
            check(dependencyRequests[0] == 1 && CpmUrlIngressFixture.requests - sourceRequests == (url ? 1 : 0),
                    "Save/close never refetch source/dependency or publish");
            var storage = new com.naocraftlab.skins.core.storage.NclSkinsStorage(saved, new PngValidator(), Clock.systemUTC());
            byte[] selected = storage.readAsset(hash);
            com.naocraftlab.skins.buildlogic.probe.CpmProjectDependencyProbe.verifyCarrier(selected, bundled, fixtures, variant);
            Files.write(root.resolve("selected.png"), selected);
            available[0] = false;
            Files.delete(base);
            check(!Files.exists(source) && !Files.exists(base), "Source and base removed before fresh reopen");
            var command = new ArrayList<String>();
            command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            command.add("-Djava.awt.headless=true");
            command.add("-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"));
            command.add("-cp"); command.add(System.getProperty("java.class.path"));
            command.add("com.naocraftlab.skins.buildlogic.probe.CpmProjectDependencyProbe");
            command.add("reopen"); command.add(root.toString()); command.add(variant.name());
            Files.writeString(root.resolve("reopen-command.json"), new com.google.gson.Gson().toJson(command));
            var child = new ProcessBuilder(command).inheritIO().start();
            if (!child.waitFor(60, TimeUnit.SECONDS)) {
                child.destroyForcibly(); child.waitFor();
                throw new AssertionError("Fresh reopen is bounded");
            }
            Files.writeString(root.resolve("reopen-exit.txt"), Integer.toString(child.exitValue()));
            check(child.exitValue() == 0, "Fresh-process first Apply passes exact carrier/type assertions");
            check(dependencyRequests[0] == 1 && CpmUrlIngressFixture.requests - sourceRequests == (url ? 1 : 0), "No implicit refetch after Save/reopen");
            System.out.println("PASS acquired dependency exact native carrier → actual Save → source/base/dependency-free fresh first Apply " + variant + (url ? " public URL" : " file"));
        }
    }

    private static Throwable unwrap(Throwable failure) {
        while (failure.getCause() != null) failure = failure.getCause();
        return failure;
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private void drain() {
        while (!workers.isEmpty() || !clients.isEmpty()) {
            while (!workers.isEmpty()) workers.remove().run();
            while (!clients.isEmpty()) clients.remove().run();
        }
    }
    private long prompt(boolean url) throws Exception {
        if (url) { flow.editUrlInput("https://example.com/project"); flow.loadRemoteImport(false); }
        else flow.chooseAddSourcePng();
        drain();
        check(failure == null, "Admission does not fail: " + (failure == null ? "none" : failure.getClass().getSimpleName()));
        check(flow.addSource().importContext().dependency().isPresent(), "Native dependency reaches real caller prompt");
        check(requests == 0 && result == null && !busy, "Discovery does not fetch or publish partial editor");
        var view = new AddSourcePresenter().present(flow.addSource(), false, Optional.of(feedback), 240, 240);
        check(view.texts().stream().anyMatch(t -> t.message().key().equals("nclskins.add.cpm_dependency_warning")), "Visible explained warning");
        long revision = flow.addSource().importContext().dependency().orElseThrow().revision();
        check(view.widgets().stream().anyMatch(w -> w.id().equals("add.cpm.dependency.confirm." + revision) && w.enabled()), "Revision-bound visible confirm");
        flow.dispatchCatalogImportAction("add.cpm.dependency.confirm." + (revision - 1), false, InteractionOrigin.PROGRAMMATIC);
        flow.dispatchCatalogImportAction("add.tab.catalog", false, InteractionOrigin.PROGRAMMATIC);
        flow.selectAddSourceTab(AddSourceTab.CATALOG); flow.chooseAddSourcePng(); flow.loadRemoteImport(false);
        drain();
        check(requests == 0 && flow.addSource().importContext().dependency().isPresent(), "Stale confirmation and tab do not bypass prompt");
        Files.delete(source);
        return revision;
    }
    private void nativeFidelity() throws Exception {
        var stateField = CatalogImportFlow.class.getDeclaredField("state"); stateField.setAccessible(true);
        Object state = stateField.get(flow);
        var modelField = state.getClass().getDeclaredField("model"); modelField.setAccessible(true);
        Object model = modelField.get(state);
        nativeFidelity(model);
    }

    static void nativeFidelity(Object model) throws Exception {
        var projectField = model.getClass().getDeclaredField("project"); projectField.setAccessible(true);
        var editor = (com.tom.cpm.shared.editor.Editor) projectField.get(model);
        var loaded = editor.templates.get(0); loaded.applyToModel();
        var cube = loaded.getCubes().get(0).getCube();
        check(cube.rgb == 0xff51a2e3 && cube.u == 18 && cube.v == 24 && cube.texSize == 2,
                "Actual acquired native replay retains template args and UV");
        check(loaded.getTemplateTexture().getImage().getRGB(63, 62) == 0xff1234ab,
                "Actual acquired native replay retains authored template texture");
    }

    private void confirm(long revision) { flow.dispatchCatalogImportAction("add.cpm.dependency.confirm." + revision, false, InteractionOrigin.PROGRAMMATIC); }

    public static void run(Path fixtures, Path output) throws Exception {
        for (boolean url : List.of(false, true)) {
            var caller = new CpmDependencyCallerProbe(fixtures, output.resolve(url ? "url" : "file"));
            long revision = caller.prompt(url);
            caller.confirm(revision); caller.confirm(revision); caller.drain();
            check(caller.requests == 1 && caller.failure == null, "One explicit request and native replay after original source deletion");
            caller.nativeFidelity();
            if (caller.result == null) { caller.flow.selectAddSourceTab(AddSourceTab.FILE); caller.flow.chooseAddSourcePng(); caller.drain(); }
            check(caller.result != null && caller.failure == null, "Native dependency import produces ordinary creation draft: "
                    + (caller.failure instanceof CpmModelFileConversion.Failure typed ? typed.reason()
                    : caller.failure == null ? caller.flow.addSource().importContext() : caller.failure.getClass().getSimpleName()));
            new PngValidator().projectImport(caller.result.draft().png().orElseThrow().bytes());
            caller.flow.closeScreens(); caller.drain();
        }
        var denied = new CpmDependencyCallerProbe(fixtures, output.resolve("denied"));
        long deniedRevision = denied.prompt(false);
        denied.flow.dispatchCatalogImportAction("add.cpm.dependency.cancel." + deniedRevision, false, InteractionOrigin.PROGRAMMATIC);
        denied.confirm(deniedRevision); denied.drain();
        check(denied.requests == 0 && denied.result == null && denied.flow.addSource().importContext().dependency().isEmpty(), "Decline restores parent with zero request");
        denied.flow.closeScreens(); denied.drain();
        var stale = new CpmDependencyCallerProbe(fixtures, output.resolve("stale-before"));
        long staleRevision = stale.prompt(false); stale.epoch++; stale.confirm(staleRevision); stale.drain();
        check(stale.requests == 0 && stale.result == null, "Stale epoch before confirm has zero request");
        stale.flow.closeScreens(); stale.drain();
        var late = new CpmDependencyCallerProbe(fixtures, output.resolve("stale-response"));
        long lateRevision = late.prompt(false); late.staleResponse = true; late.confirm(lateRevision); late.drain();
        check(late.requests == 1 && late.result == null, "Late response never publishes editor");
        late.flow.closeScreens(); late.drain();
        var expired = new CpmDependencyCallerProbe(fixtures, output.resolve("expired"));
        long expiredRevision = expired.prompt(false); expired.nanos = Duration.ofSeconds(120).toNanos(); expired.confirm(expiredRevision); expired.drain();
        check(expired.requests == 0 && expired.failure instanceof PublicSkinImportException && expired.result == null && !expired.busy, "Operation deadline includes consent wait");
        expired.flow.closeScreens(); expired.drain();
        var rejected = new CpmDependencyCallerProbe(fixtures, output.resolve("site-rejected"));
        long rejectedRevision = rejected.prompt(false); rejected.status = 403; rejected.confirm(rejectedRevision); rejected.drain();
        check(rejected.requests == 1 && rejected.failure instanceof PublicSkinImportException typed && typed.code() == PublicSkinImportException.Code.SITE_BLOCKED && rejected.result == null && !rejected.busy, "Site denial has no fallback/replay/editor");
        rejected.flow.closeScreens(); rejected.drain();
        for (boolean unsafe : List.of(true, false)) {
            var blocked = new CpmDependencyCallerProbe(fixtures, output.resolve(unsafe ? "unsafe" : "oversized"));
            long revision = blocked.prompt(false); blocked.unsafeResponse = unsafe; blocked.oversizedResponse = !unsafe;
            blocked.confirm(revision); blocked.drain();
            check((unsafe ? blocked.failure instanceof PublicSkinImportException typed && typed.code() == PublicSkinImportException.Code.UNSAFE_URL
                    : blocked.feedback.key().equals("nclskins.add.cpm_dependency_limit"))
                    && blocked.result == null && blocked.requests == (unsafe ? 0 : 1), "Existing transport rejection is typed and terminal");
            blocked.flow.closeScreens(); blocked.drain();
        }
        for (int code : List.of(302, 404, 429)) {
            var response = new CpmDependencyCallerProbe(fixtures, output.resolve("status-" + code));
            long revision = response.prompt(false); response.status = code; response.confirm(revision); response.drain();
            String key = code == 302 ? "nclskins.add_source.url_redirect_rejected"
                    : code == 429 ? "nclskins.add_source.url_rate_limited" : "nclskins.add_source.url_network_failure";
            check(response.requests == 1 && response.result == null && response.feedback.key().equals(key),
                    "HTTP status uses actual public failure mapper without redirect/retry");
            response.flow.closeScreens(); response.drain();
        }
        var invalid = new CpmDependencyCallerProbe(fixtures, output.resolve("invalid-native"));
        long invalidRevision = invalid.prompt(false); invalid.invalidBody = true; invalid.confirm(invalidRevision); invalid.drain();
        check(invalid.requests == 1 && invalid.result == null && invalid.failure instanceof CpmModelFileConversion.Failure typed
                && typed.reason() == CpmModelFileConversion.Reason.INVALID_MODEL, "Native parser rejects invalid downloaded template without partial editor");
        invalid.flow.closeScreens(); invalid.drain();
        var timedOut = new CpmDependencyCallerProbe(fixtures, output.resolve("late-deadline"));
        long timedRevision = timedOut.prompt(false); timedOut.expiredResponse = true; timedOut.confirm(timedRevision); timedOut.drain();
        check(timedOut.requests == 1 && timedOut.result == null && timedOut.feedback.key().equals("nclskins.add_source.url_network_failure"),
                "Late response deadline rejects bytes before native replay");
        timedOut.flow.closeScreens(); timedOut.drain();
        var sequence = new CpmDependencyCallerProbe(fixtures, output.resolve("two"), 2);
        long first = sequence.prompt(false); sequence.confirm(first); sequence.drain();
        check(sequence.requests == 1 && sequence.flow.addSource().importContext().dependency().isPresent(), "Next native link requires another prompt, not recursive network");
        long second = sequence.flow.addSource().importContext().dependency().orElseThrow().revision();
        sequence.confirm(first); sequence.drain(); check(sequence.requests == 1, "Old consent cannot authorize next link");
        sequence.confirm(second); sequence.drain(); sequence.nativeFidelity();
        check(sequence.requests == 2 && sequence.failure == null && sequence.flow.addSource().importContext().dependency().isEmpty(), "Owned dependencies replay without refetch");
        sequence.flow.closeScreens(); sequence.drain();
        var inclusive = new CpmDependencyCallerProbe(fixtures, output.resolve("sixteen"), 16);
        long inclusiveRevision = inclusive.prompt(false);
        for (int request = 0; request < 16; request++) {
            inclusive.confirm(inclusiveRevision); inclusive.drain();
            if (request < 15) inclusiveRevision = inclusive.flow.addSource().importContext().dependency().orElseThrow().revision();
        }
        check(inclusive.requests == 16 && inclusive.failure == null && inclusive.flow.addSource().importContext().dependency().isEmpty(),
                "Exactly sixteen dependencies can finish native admission");
        inclusive.nativeFidelity(); inclusive.flow.closeScreens(); inclusive.drain();
        var bounded = new CpmDependencyCallerProbe(fixtures, output.resolve("attempts"), 17);
        long revision = bounded.prompt(false);
        for (int request = 0; request < 16; request++) {
            bounded.confirm(revision); bounded.drain();
            if (request < 15) revision = bounded.flow.addSource().importContext().dependency().orElseThrow().revision();
        }
        check(bounded.requests == 16 && bounded.feedback.key().equals("nclskins.add.cpm_dependency_limit") && bounded.result == null,
                "Sixteen confirmed requests exhaust operation budget without seventeenth request");
        bounded.flow.closeScreens(); bounded.drain();
        var queued = new CpmDependencyCallerProbe(fixtures, output.resolve("cancel-queued"));
        long queuedRevision = queued.prompt(false); queued.confirm(queuedRevision);
        check(queued.busy && queued.flow.canCancelPendingBase(), "Busy dependency request can be cancelled through existing Escape contract");
        queued.flow.cancelAddSource(); queued.drain();
        check(queued.requests == 0 && queued.result == null && !queued.busy, "Cancelled queued request never reaches transport");
        queued.flow.closeScreens(); queued.drain();
        for (String kind : List.of("resources", "world")) {
            var changed = new CpmDependencyCallerProbe(fixtures, output.resolve(kind));
            long changedRevision = changed.prompt(false); changed.confirm(changedRevision);
            if (kind.equals("resources")) changed.resources++; else changed.world++;
            changed.drain();
            check(changed.requests == 0 && changed.result == null, "Queued request checks " + kind + " epoch before transport");
            changed.flow.closeScreens(); changed.drain();
        }
        for (String link : List.of("local:synthetic", "p:synthetic#fragment")) {
            var unsupported = new CpmDependencyCallerProbe(fixtures, output.resolve(link.startsWith("local") ? "local" : "fragment"), 1, link);
            unsupported.flow.chooseAddSourcePng(); unsupported.drain();
            check(unsupported.failure instanceof CpmModelFileConversion.Failure typed
                    && typed.reason() == CpmModelFileConversion.Reason.PORTABILITY_UNSUPPORTED
                    && unsupported.requests == 0 && unsupported.result == null, "Unsafe/unsupported native link stops before prompt/network");
            unsupported.flow.closeScreens(); unsupported.drain();
        }
        var fallbackFlag = com.tom.cpm.shared.loaders.PasteResourceLoader.class.getDeclaredField("useFallback");
        fallbackFlag.setAccessible(true); boolean previous = fallbackFlag.getBoolean(null);
        try {
            fallbackFlag.setBoolean(null, true);
            var fallback = new CpmDependencyCallerProbe(fixtures, output.resolve("paste-fallback"));
            fallback.flow.chooseAddSourcePng(); fallback.drain();
            check(fallback.failure instanceof CpmModelFileConversion.Failure typed
                    && typed.reason() == CpmModelFileConversion.Reason.PORTABILITY_UNSUPPORTED && fallback.requests == 0,
                    "Native mutable Paste fallback never authorizes fallback host");
            fallback.flow.closeScreens(); fallback.drain();
        } finally { fallbackFlag.setBoolean(null, previous); }
        System.out.println("PASS dependency production caller file/URL consent/native fidelity, decline, account/resource/world staleness, deadline, unsafe/site/body/attempt budgets, sequential consent, queued cancel and native unsupported/fallback refusal");
    }
}

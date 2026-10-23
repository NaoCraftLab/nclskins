package com.naocraftlab.skins.buildlogic.probe;

import com.naocraftlab.skins.client.CurrentPlayerAppearanceSource.PlayerAppearance;
import com.naocraftlab.skins.client.PreviewAccessories;
import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.client.TextureRegistry;
import com.naocraftlab.skins.compat.cpm.CpmModelFileAdapter;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.diagnostics.DiagnosticSink;
import com.naocraftlab.skins.diagnostics.DiagnosticSinks;
import com.naocraftlab.skins.runtime.ClientRuntime;
import com.naocraftlab.skins.runtime.CpmCallerHarness;
import com.naocraftlab.skins.runtime.PreviewAssetCache;
import com.naocraftlab.skins.runtime.ViewSpec;
import com.naocraftlab.skins.runtime.UiMessage;
import com.naocraftlab.skins.runtime.TextResolver;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class CpmOwnedPreviewDispatchProbe {
    private static boolean active;
    private static int observed;
    private static Path nativeConfiguration;
    private static boolean nativeResources;
    private static int nativeDispatches;

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && (args[0].equals("caption-only") || args[0].equals("text-only"))) {
            previewCaptionConsumers();
            if (args[0].equals("text-only")) quotaConsumers(Path.of("."));
            return;
        }
        CpmPendingTextureProbe.run();
        Path fixtures = Path.of(args[0]), output = Path.of(args[1]);
        nativeConfiguration = output.resolve("preview-config.json");
        nativeResources = args.length > 2 && args[2].equals("resources");
        CpmModelConversionProbe.main(new String[]{fixtures.toString(), output.resolve("host").toString(), "environment-only"});
        previewCaptionConsumers();
        quotaConsumers(fixtures);
        javax.imageio.ImageIO.setUseCache(false);
        byte[] current = Files.readAllBytes(fixtures.resolve("classic-base.png"));
        var image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(current));
        image.setRGB(9, 9, 0xff13579b);
        var bytes = new java.io.ByteArrayOutputStream();
        check(javax.imageio.ImageIO.write(image, "png", bytes), "Synthetic current A encoded");
        current = bytes.toByteArray();
        observed = 0;
        nativeDispatches = 0;
        active = true;
        try {
            for (SkinVariant variant : SkinVariant.values()) {
                String prefix = variant == SkinVariant.SLIM ? "slim" : "classic";
                Path model = fixtures.resolve(prefix + "-embedded.cpmmodel");
                if (nativeResources) {
                    model = output.resolve(prefix + "-resource.cpmmodel");
                    CpmBehaviorProbe.writeResourceModel(model, variant == SkinVariant.SLIM
                            ? com.tom.cpm.shared.model.SkinType.SLIM : com.tom.cpm.shared.model.SkinType.DEFAULT);
                }
                CpmCallerHarness.saveThroughRuntime(new CpmModelFileAdapter(), current,
                        model, fixtures.resolve(prefix + "-base.png"),
                        output.resolve(prefix), variant, "preview-dispatch");
            }
            check(observed == SkinVariant.values().length,
                    "Production import callback must execute once per variant; missing shared-harness hook is not PASS");
        } finally {
            active = false;
        }
        if (nativeResources) {
            check(nativeDispatches == SkinVariant.values().length, "each selected type must reach actual native resource policy/resolve/frame binding");
            System.out.println("PASS native model writer -> production import -> compiled loaded dispatch -> actual scoped resource policy/resolve/frame binding, both types; synthetic host/GPU, NOT game-rendered acceptance");
        } else System.out.println("PASS production import -> compiled screen synchronization/dispatch -> exact registered carrier and model; synthetic GPU/borrowed host terminals, NOT native worldless/live rendering or CPM policy acceptance");
    }

    public static void observe(ClientRuntime runtime, byte[] current, byte[] base, SkinVariant variant) throws Exception {
        if (!active) return;
        ViewSpec view = runtime.view(854, 480, 0, 0);
        check(view.previews().size() == 1, "One production Editor preview");
        ViewSpec.Preview preview = view.previews().get(0);
        byte[] converted = runtime.snapshot().editor().orElseThrow().png().orElseThrow().bytes();
        check(!Arrays.equals(current, base) && !Arrays.equals(base, converted) && !Arrays.equals(current, converted),
                "A != B != C before native dispatch");
        check(preview.ownedPng() && preview.variant() == variant && preview.skin().optionalAssetId().isEmpty(),
                "Real creation draft retains owned selected pair over account-default reference");
        String owner = CpmPreviewScreenSlice.availableOwner();
        for (PreviewAccessories policy : List.of(
                PreviewAccessories.unavailable(PreviewRenderer.LiveReason.WORLD_REQUIRED),
                new PreviewAccessories(null, 0, PreviewAccessories.Availability.BLOCKED, false, false, PreviewRenderer.LiveReason.AVAILABLE),
                new PreviewAccessories(java.util.UUID.fromString("00000000-0000-0000-0000-000000000007"), 1,
                        PreviewAccessories.Availability.CONFIRMED, false, false, PreviewRenderer.LiveReason.AVAILABLE))) {
            run(owner, runtime, view, preview, current, converted, null, variant, true, false, policy, "converted");
            run(owner, runtime, view, preview, current, converted, null, variant, true, true, policy, "pending converted");
            run(owner, runtime, view, preview, current, base, base, variant, true, false, policy, "ordinary-owned dispatch sibling");
            run(owner, runtime, view, preview.withOwnedPng(false), current, current, null, variant, false, false, policy, "pure-account-default dispatch sibling");
        }
        check(Arrays.equals(converted, runtime.snapshot().editor().orElseThrow().png().orElseThrow().bytes()),
                "Screen submission cannot replace selected carrier");
        if (variant == SkinVariant.CLASSIC) accessoryReleaseConsumers(owner, runtime, view, preview);
        observed++;
    }

    private static void run(String owner, ClientRuntime runtime, ViewSpec original, ViewSpec.Preview preview,
            byte[] current, byte[] expected, byte[] ordinary, SkinVariant variant, boolean loaded, boolean pending,
            PreviewAccessories policy, String label) throws Exception {
        ViewSpec view = copyView(original, preview);
        var terminal = new RenderTerminal();
        terminal.policy = policy;
        var registry = new Registry();
        var boundary = new RuntimeBoundary(runtime, ordinary, variant, current);
        boundary.pending = pending;
        terminal.boundary = boundary;
        Class<?> screenType = CpmPreviewScreenSlice.load(owner);
        Object screen = screenType.getConstructor().newInstance();
        try (var skins = new PreviewAssetCache<Object>(registry, TextureRegistry.TextureKind.PLAYER_SKIN, DiagnosticSinks.discarding());
             var capes = new PreviewAssetCache<Object>(registry, TextureRegistry.TextureKind.IMAGE, DiagnosticSinks.discarding())) {
            for (var field : screenType.getDeclaredFields()) {
                field.setAccessible(true);
                Object value = switch (field.getName()) {
                    case "runtime" -> boundary;
                    case "skinTextures" -> skins;
                    case "capeTextures" -> capes;
                    case "editorRenderer" -> terminal;
                    case "textureRegistry" -> registry;
                    case "capabilities" -> new Capabilities() {
                        public PreviewRenderer<Graphics> createEditorPreviewRenderer() { return terminal; }
                        public PreviewRenderer<Graphics> createSimplePreviewRenderer() { return terminal; }
                    };
                    default -> field.getType() == Map.class ? new HashMap<>() : null;
                };
                if (value != null) field.set(screen, value);
            }
            boolean immediate = owner.endsWith("NclSkinsImmediateScreen");
            String synchronize = immediate ? "synchronizePreviews" : owner.contains("submission") ? "synchronizePreviewAssets" : "syncPreviewAssets";
            invoke(screenType, screen, synchronize, new Class<?>[]{ViewSpec.class}, view);
            String render = immediate ? "renderPreview" : owner.contains("submission") ? "renderPreviews" : "drawPreviews";
            invoke(screenType, screen, render, new Class<?>[]{Graphics.class, immediate ? ViewSpec.Preview.class : ViewSpec.class},
                    new Graphics(), immediate ? preview : view);
            check(boundary.bodyRegistrations > 0 && terminal.preparations > 0, label + ": all hosts register actual supplier before preparation/no-texture exit");
            check(boundary.lastSource != null, label + ": actual host supplies a live primitive query");
            if (pending) {
                check(boundary.loads == 1 && boundary.borrowed == 0 && registry.registered.isEmpty()
                        && terminal.requests.isEmpty(), label + ": pending C never falls back to borrowed A");
                check(boundary.lastRegistration != null && boundary.releases > 0, label + ": no-texture exit retires its own registration");
                checkBodyReleased(runtime);
                return;
            }
            check(terminal.requests.size() == 1, label + ": actual screen submits exactly once");
            var request = terminal.requests.get(0);
            if (loaded) {
                check(boundary.loads == 1 && boundary.borrowed == 0 && registry.registered.size() == 1,
                        label + ": actual synchronization requests owned bytes, dispatch does not borrow A");
                check(Arrays.equals(expected, registry.registered.get(request.appearance().skin())),
                        label + ": submitted texture owns exact expected PNG, not raw B/current A");
                check(request.appearance().model() == SkinModel.valueOf(variant.name()), label + ": selected native model");
                if (nativeResources && label.equals("converted") && policy.availability() == PreviewAccessories.Availability.CONFIRMED) {
                    CpmBehaviorProbe.inspectDispatchedAppearance(nativeConfiguration.toFile(),
                            registry.registered.get(request.appearance().skin()), request.appearance());
                    nativeDispatches++;
                }
            } else {
                check(boundary.loads == 0 && registry.registered.isEmpty() && boundary.borrowed == 1,
                        label + ": accountDefault does not request/register owned bytes");
                check(request.appearance().skin().equals(boundary.current.skin()) && request.appearance().model() == boundary.current.model(),
                        label + ": borrowed current skin and opposite model reach renderer together");
                check(Arrays.equals(expected, boundary.currentBytes), label + ": current A bytes remain the borrowed source");
            }
            check(request.intent() == preview.intent() && request.appearance().outerLayerVisibility().equals(preview.outerLayerVisibility()),
                    label + ": intent and layer controls survive dispatch");
            check(request.appearance().nativeAccessories() == preview.nativeAccessories(), label + ": native accessory policy is not forced");
            var source = boundary.lastSource;
            var registration = boundary.lastRegistration;
            check(source.get() == PreviewRenderer.DisplayKind.UNCONFIRMED, label + ": submission alone is not displayed ordinary");
            terminal.ordinary = true;
            check(source.get() == PreviewRenderer.DisplayKind.ORDINARY, label + ": confirmed ordinary display reaches shared query");
            terminal.displayed = true;
            check(source.get() == PreviewRenderer.DisplayKind.CPM, label + ": compiled host uses shared current displayed-body query");
            if (label.equals("converted")) {
                runtime.reconcilePreviewBody(registration);
                var hidden = runtime.view(854, 480, 0, 0);
                check(hidden.widgets().stream().noneMatch(widget -> widget.id().startsWith("editor.outer_layer."))
                        && hidden.navigationNodes().stream().noneMatch(node -> node.id().startsWith("editor.outer_layer.")),
                        "Actual compiled host registration reaches runtime visibility and navigation");
                var draft = runtime.snapshot().editor().orElseThrow().saveRequest();
                for (String group : List.of("head", "body", "legs")) {
                    runtime.dispatchWidget("editor.outer_layer." + group);
                    runtime.dispatchWidget("editor.outer_layer." + group, true);
                }
                var unchanged = runtime.snapshot().editor().orElseThrow().saveRequest();
                check(draft.outerLayerVisibility().equals(unchanged.outerLayerVisibility())
                        && draft.variant() == unchanged.variant() && draft.name().equals(unchanged.name())
                        && Arrays.equals(draft.pngBytes().orElseThrow(), unchanged.pngBytes().orElseThrow()),
                        "Host-current hidden dispatch cannot change draft mask or carrier");
            }
            terminal.current = false;
            check(source.get() == PreviewRenderer.DisplayKind.UNCONFIRMED, label + ": compiled host does not capture a READY boolean snapshot");
            terminal.current = true;
            invoke(screenType, screen, render, new Class<?>[]{Graphics.class, immediate ? ViewSpec.Preview.class : ViewSpec.class},
                    new Graphics(), immediate ? preview : view);
            check(source == boundary.lastSource && registration == boundary.lastRegistration,
                    label + ": renderer query and current registration remain stable across frames");
            var replacement = runtime.observePreviewBody(preview, () -> PreviewRenderer.DisplayKind.UNCONFIRMED);
            invoke(screenType, screen, immediate ? "releasePreviews" : "releasePreviewBody", new Class<?>[0]);
            check(boundary.releases > 0, label + ": compiled release retires its own registration");
            registration.accept(PreviewRenderer.BodyStatus.READY);
            var capability = ClientRuntime.class.getDeclaredField("bodyCapability");
            capability.setAccessible(true);
            check(capability.get(runtime) != null, label + ": old host release cannot retire a new registration");
            runtime.invalidatePreviewBody(replacement);
            checkBodyReleased(runtime);
            if (owner.contains("extraction")) {
                screenType.getField("editorRenderer").set(screen, null);
                invoke(screenType, screen, "releasePreviewBody", new Class<?>[0]);
            }
            terminal.close();
            check(source.get() == PreviewRenderer.DisplayKind.UNCONFIRMED, label + ": closed renderer cannot report a displayed body");
        }
    }

    private static void accessoryReleaseConsumers(String owner, ClientRuntime runtime, ViewSpec view, ViewSpec.Preview preview) throws Exception {
        Class<?> type = CpmPreviewScreenSlice.load(owner);
        boolean immediate = owner.endsWith("NclSkinsImmediateScreen");
        String synchronize = immediate ? "synchronizePreviews" : owner.contains("submission") ? "synchronizePreviewAssets" : "syncPreviewAssets";
        String render = immediate ? "renderPreview" : owner.contains("submission") ? "renderPreviews" : "drawPreviews";
        String release = immediate ? "releasePreviews" : "releasePreviewAccessories";
        var callbackField = ClientRuntime.class.getDeclaredField("accessoryCallback");
        callbackField.setAccessible(true);
        var supplierField = ClientRuntime.class.getDeclaredField("accessoryCapability");
        supplierField.setAccessible(true);
        for (String scenario : List.of("old-owner", "current", "slot-replacement", "reentrant")) {
            var terminal = new RenderTerminal();
            terminal.policy = new PreviewAccessories(new java.util.UUID(0, 742), 1,
                    PreviewAccessories.Availability.FAILED, false, false, PreviewRenderer.LiveReason.AVAILABLE);
            var registry = new Registry();
            var boundary = new RuntimeBoundary(runtime, null, SkinVariant.CLASSIC, new byte[0]);
            terminal.boundary = boundary;
            var nextTerminal = new RenderTerminal();
            nextTerminal.policy = terminal.policy;
            var nextRegistry = new Registry();
            var nextBoundary = new RuntimeBoundary(runtime, null, SkinVariant.CLASSIC, new byte[0]);
            nextTerminal.boundary = nextBoundary;
            try (var skins = new PreviewAssetCache<Object>(registry, TextureRegistry.TextureKind.PLAYER_SKIN, DiagnosticSinks.discarding());
                 var capes = new PreviewAssetCache<Object>(registry, TextureRegistry.TextureKind.IMAGE, DiagnosticSinks.discarding());
                 var nextSkins = new PreviewAssetCache<Object>(nextRegistry, TextureRegistry.TextureKind.PLAYER_SKIN, DiagnosticSinks.discarding());
                 var nextCapes = new PreviewAssetCache<Object>(nextRegistry, TextureRegistry.TextureKind.IMAGE, DiagnosticSinks.discarding())) {
                Object screen = accessoryScreen(type, boundary, terminal, registry, skins, capes);
                Object nextScreen = accessoryScreen(type, nextBoundary, nextTerminal, nextRegistry, nextSkins, nextCapes);
                invoke(type, screen, synchronize, new Class<?>[]{ViewSpec.class}, view);
                invoke(type, screen, render, new Class<?>[]{Graphics.class, immediate ? ViewSpec.Preview.class : ViewSpec.class},
                        new Graphics(), immediate ? preview : view);
                var owned = boundary.lastAccessoryRegistration;
                check(owned != null && callbackField.get(runtime) == owned, scenario + ": compiled host owns real runtime registration");
                check(terminal.accessoryListener == owned, scenario + ": renderer observes its host registration");
                var host = immediate ? ((Map<?, ?>) type.getField("previewSlots").get(screen)).get(preview.id()) : screen;
                var local = host.getClass().getDeclaredField("accessoryObserver");
                local.setAccessible(true);
                var replacement = new java.util.concurrent.atomic.AtomicReference<Consumer<PreviewAccessories>>();
                var cleanup = new java.util.concurrent.atomic.AtomicBoolean();
                boundary.beforeAccessoryRelease = registration -> {
                    try {
                        check(registration == owned, scenario + ": release passes captured old identity, not latest runtime callback");
                        check(local.get(host) == null && terminal.accessoryListener != owned,
                                scenario + ": clear local reference and detach listener before runtime publication");
                    } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
                };
                var publications = new java.util.concurrent.atomic.AtomicInteger();
                try (var subscription = runtime.subscribe(ignored -> {
                    publications.incrementAndGet();
                    if (scenario.equals("reentrant") && cleanup.getAndSet(false)) {
                        try {
                            check(local.get(host) == null && terminal.accessoryListener != owned,
                                    "Reentrant publication sees already detached old owner");
                        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
                        replacement.set(runtime.observePreviewAccessories(preview, () -> terminal.policy));
                    }
                })) {
                    cleanup.set(false);
                    if (scenario.equals("old-owner")) {
                        invoke(type, nextScreen, synchronize, new Class<?>[]{ViewSpec.class}, view);
                        invoke(type, nextScreen, render, new Class<?>[]{Graphics.class, immediate ? ViewSpec.Preview.class : ViewSpec.class},
                                new Graphics(), immediate ? preview : view);
                        replacement.set(nextBoundary.lastAccessoryRegistration);
                        check(replacement.get() != owned && nextTerminal.accessoryListener == replacement.get(),
                                "New compiled host has its own distinct registration and renderer listener");
                    }
                    int before = publications.get();
                    cleanup.set(scenario.equals("reentrant"));
                    if (scenario.equals("slot-replacement")) {
                        var components = ViewSpec.class.getRecordComponents();
                        var values = new Object[components.length];
                        var types = new Class<?>[components.length];
                        for (int i = 0; i < components.length; i++) {
                            types[i] = components[i].getType();
                            values[i] = switch (components[i].getName()) {
                                case "screenId" -> immediate ? "gallery" : view.screenId();
                                case "previews" -> immediate ? view.previews() : List.of();
                                default -> components[i].getAccessor().invoke(view);
                            };
                        }
                        var empty = ViewSpec.class.getDeclaredConstructor(types).newInstance(values);
                        invoke(type, screen, immediate ? synchronize : render,
                                immediate ? new Class<?>[]{ViewSpec.class} : new Class<?>[]{Graphics.class, ViewSpec.class},
                                immediate ? new Object[]{empty} : new Object[]{new Graphics(), empty});
                        if (immediate) check(((Map<?, ?>) type.getField("previewSlots").get(screen)).get(preview.id()) != host,
                                "Actual slot replacement closes the old holder before installing the new role");
                    } else invoke(type, screen, release, new Class<?>[0]);
                    check(boundary.accessoryReleases > 0, scenario + ": actual compiled cleanup executed owned facade");
                    if (scenario.equals("reentrant")) check(replacement.get() != null, "Current release must actually execute the reentrant subscriber");
                    if (replacement.get() != null) {
                        check(callbackField.get(runtime) == replacement.get(), scenario + ": cleanup preserves replacement");
                        if (scenario.equals("old-owner")) check(publications.get() == before, "Stale cleanup cannot publish");
                    } else check(callbackField.get(runtime) == null && supplierField.get(runtime) == null, scenario + ": current cleanup releases supplier");
                    boundary.beforeAccessoryRelease = null;
                    int after = publications.get();
                    invoke(type, screen, release, new Class<?>[0]);
                    owned.accept(terminal.policy);
                    check(publications.get() == after, scenario + ": repeated cleanup and old callback cannot publish");
                    check(callbackField.get(runtime) == replacement.get(), scenario + ": old callback cannot resurrect or clear current");
                    if (scenario.equals("old-owner")) {
                        check(nextTerminal.accessoryListener == replacement.get(), "Old host cleanup cannot detach the new renderer listener");
                        invoke(type, nextScreen, release, new Class<?>[0]);
                        check(callbackField.get(runtime) == null && supplierField.get(runtime) == null,
                                "New compiled owner's current release clears its own registration");
                    } else if (replacement.get() != null) runtime.invalidatePreviewAccessories(replacement.get());
                }
                terminal.close();
            }
            System.out.println("PASS compiled owned accessory " + scenario + " for " + owner + "; real common runtime, synthetic renderer terminals");
        }
    }

    private static Object accessoryScreen(Class<?> type, RuntimeBoundary boundary, RenderTerminal terminal,
            Registry registry, PreviewAssetCache<Object> skins, PreviewAssetCache<Object> capes) throws Exception {
        Object screen = type.getConstructor().newInstance();
        for (var field : type.getDeclaredFields()) {
            Object value = switch (field.getName()) {
                case "runtime" -> boundary;
                case "skinTextures" -> skins;
                case "capeTextures" -> capes;
                case "editorRenderer" -> terminal;
                case "textureRegistry" -> registry;
                case "capabilities" -> new Capabilities() {
                    public PreviewRenderer<Graphics> createEditorPreviewRenderer() { return terminal; }
                    public PreviewRenderer<Graphics> createSimplePreviewRenderer() { return terminal; }
                };
                default -> field.getType() == Map.class ? new HashMap<>() : null;
            };
            if (value != null) field.set(screen, value);
        }
        return screen;
    }

    private static void checkBodyReleased(ClientRuntime runtime) throws Exception {
        var capability = ClientRuntime.class.getDeclaredField("bodyCapability");
        capability.setAccessible(true);
        check(capability.get(runtime) == null, "Retired runtime must not retain the renderer supplier");
    }

    private static ViewSpec copyView(ViewSpec view, ViewSpec.Preview preview) throws Exception {
        var components = ViewSpec.class.getRecordComponents();
        Class<?>[] types = new Class<?>[components.length];
        Object[] values = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
            values[i] = switch (components[i].getName()) {
                case "previews" -> List.of(preview);
                case "backEquipmentPreviews", "iconDecorations" -> List.of();
                default -> components[i].getAccessor().invoke(view);
            };
        }
        return ViewSpec.class.getDeclaredConstructor(types).newInstance(values);
    }

    private static void invoke(Class<?> type, Object target, String name, Class<?>[] parameters, Object... arguments) throws Exception {
        var method = type.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        try { method.invoke(target, arguments); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Error error) throw error;
            if (failure.getCause() instanceof Exception exception) throw exception;
            throw failure;
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static Map<String, String> dictionary(java.io.InputStream input) throws Exception {
        if (input == null) return Map.of();
        try (input) {
            var object = com.google.gson.JsonParser.parseString(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            var result = new HashMap<String, String>();
            object.entrySet().forEach(entry -> result.put(entry.getKey(), entry.getValue().getAsString()));
            return result;
        }
    }

    private static void quotaConsumers(Path fixtures) throws Exception {
        Path repo = fixtures.toAbsolutePath();
        while (!Files.isRegularFile(repo.resolve("gradle/targets.json"))) repo = repo.getParent();
        var catalog = com.google.gson.JsonParser.parseString(Files.readString(repo.resolve("gradle/targets.json")))
                .getAsJsonObject().getAsJsonObject("mod").getAsJsonObject("localization").getAsJsonArray("sourceLocales");
        String owner = CpmPreviewScreenSlice.availableOwner();
        Class<?> type = CpmPreviewScreenSlice.load(owner, true);
        var keys = List.of("button.cpm.open_editor", "button.cpm.edit", "button.cpm.edit.pastes");
        var english = dictionary(CpmOwnedPreviewDispatchProbe.class.getResourceAsStream("/assets/cpm/lang/en_us.json"));
        check(keys.stream().allMatch(english::containsKey), "Genuine pinned CPM English menu resources");
        int projections = 0;
        var labels = new HashMap<String, String>();
        var measured = new ArrayList<UiMessage>();
        var font = new FontTerminal();
        TextResolver resolver = TextResolver.withCatalogTranslations(TextResolver.withLayout(
                message -> Text.translatable(message.key(), message.arguments().toArray()).getString(),
                (message, width) -> {
                    measured.add(message);
                    return font.split(Text.translatable(message.key(), message.arguments().toArray()), width).size() * font.lineHeight;
                }), (key, fallback) -> labels.getOrDefault(key, fallback));
        var project = CpmCallerHarness.quotaViews(resolver);
        for (var localeValue : catalog) {
            String locale = localeValue.getAsString();
            var language = dictionary(Files.newInputStream(repo.resolve("compat/resources/canonical/src/main/resources/assets/nclskins/lang/" + locale + ".json")));
            labels.clear(); labels.putAll(english);
            labels.putAll(dictionary(CpmOwnedPreviewDispatchProbe.class.getResourceAsStream("/assets/cpm/lang/" + locale + ".json")));
            if (locale.equals("de_de")) check(keys.stream().allMatch(key -> labels.get(key).equals(english.get(key))), "German uses genuine CPM English fallback");
            var genuine = new HashMap<>(labels);
            for (int scenario = 0; scenario < 17; scenario++) {
                labels.clear(); labels.putAll(genuine);
                if (scenario >= 1 && scenario <= 9) {
                    String key = keys.get((scenario - 1) / 3);
                    labels.put(key, switch ((scenario - 1) % 3) { case 0 -> ""; case 1 -> " \t"; default -> key; });
                } else if (scenario == 10) labels.clear();
                else if (scenario == 11) keys.forEach(key -> labels.put(key, "Long current translated menu label ".repeat(8)));
                else if (scenario == 12) { labels.clear(); labels.putAll(english); }
                else if (scenario >= 14) labels.remove(keys.get(scenario - 14));
                Text.language = language;
                for (int[] size : List.of(new int[]{180, 120}, new int[]{854, 160})) {
                    measured.clear();
                    var view = project.apply(size[0], size[1]);
                    var status = view.texts().stream().filter(text -> text.id().equals("add.import.status")).findFirst().orElseThrow();
                    var quotaMeasurements = measured.stream().filter(message -> message.key().startsWith("nclskins.add.cpm_publication_count_quota")).toList();
                    check(!quotaMeasurements.isEmpty() && quotaMeasurements.stream().allMatch(status.message()::equals),
                            "Every measurement consumes the identical projected UiMessage");
                    check(status.message().arguments().size() == (scenario >= 1 && scenario <= 10 || scenario >= 14 ? 0 : 3), "Full/fallback argument contract");
                    String expected = resolver.resolve(status.message());
                    var viewport = view.scrollSurfaces().get(0).viewport();
                    check(status.bounds().bottom() <= viewport.bottom() && view.widget("add.cancel").orElseThrow().bounds().y() >= viewport.bottom(),
                            "Complete wrapped quota text stays above the persistent footer");
                    check(!expected.contains("button.cpm.") && !expected.contains("%1$s"), "No raw foreign keys or templates");
                    var boundary = new RuntimeBoundary(null, null, SkinVariant.CLASSIC, new byte[0]);
                    boundary.presented = view;
                    Object screen = type.getConstructor().newInstance();
                    var host = (ScreenTerminal) screen;
                    host.font = font;
                    host.minecraft = new ClientTerminal();
                    host.minecraft.screen = host;
                    for (var field : type.getDeclaredFields()) {
                        field.setAccessible(true);
                        switch (field.getName()) {
                            case "runtime" -> field.set(screen, boundary);
                            case "currentView", "view" -> field.set(screen, view);
                            case "initialized", "activeScreen" -> field.setBoolean(screen, true);
                            case "capabilities" -> field.set(screen, new Capabilities() {
                                public PreviewRenderer<Graphics> createEditorPreviewRenderer() { throw new AssertionError("No renderer"); }
                                public PreviewRenderer<Graphics> createSimplePreviewRenderer() { throw new AssertionError("No renderer"); }
                            });
                        }
                    }
                    var graphics = new Graphics();
                    invoke(type, screen, owner.contains("extraction") ? "drawText" : "renderText",
                            new Class<?>[]{Graphics.class, ViewSpec.class, ViewSpec.Text.class, int.class, int.class}, graphics, view, status, 0, 0);
                    check(String.join("", graphics.drawn).equals(expected), "Target-compiled draw consumes the complete measured projected message");
                    var narration = (TextValue) type.getMethod("getNarrationMessage").invoke(screen);
                    check(narration.getString().equals("Screen\n" + expected), "Target-compiled native Screen narration consumes the identical projected message");
                    String inactiveField = owner.endsWith("NclSkinsImmediateScreen") ? "removed" : "activeScreen";
                    var inactive = type.getField(inactiveField);
                    inactive.setBoolean(screen, inactiveField.equals("removed"));
                    check(((TextValue) type.getMethod("getNarrationMessage").invoke(screen)).getString().equals("Screen"),
                            "Inactive/removed host cannot narrate stale Import feedback");
                    check(view.widgets().stream().noneMatch(widget -> widget.id().startsWith("add.cpm.publication.") || widget.id().equals("add.import.status.info")), "No new glyph/tooltip/retry targets");
                    projections++;
                }
            }
        }
        System.out.println("PASS " + projections + " actual compiled quota measurement/draw/Screen-narration projections for " + owner + "; genuine CPM resources, synthetic font/graphics/language terminals, NOT GPU/spoken narration");
    }

    private static void previewCaptionConsumers() throws Exception {
        String owner = CpmPreviewScreenSlice.availableOwner();
        Class<?> type = CpmPreviewScreenSlice.load(owner, true);
        Text.language = dictionary(Files.newInputStream(Path.of("compat/resources/canonical/src/main/resources/assets/nclskins/lang/ru_ru.json")));
        TextResolver resolver = message -> Text.translatable(message.key(), message.arguments().toArray()).getString();
        var missingNarration = new ArrayList<String>();
        for (String context : List.of("preset_editor", "providers")) {
            var failed = CpmCallerHarness.previewFailureView(context, resolver, true);
            var ordinary = CpmCallerHarness.previewFailureView(context, resolver, false);
            var caption = failed.texts().stream().filter(text -> text.id().equals("editor.preview.error")).findFirst().orElseThrow();
            String expected = resolver.resolve(caption.message());
            check(expected.equals("Не удалось загрузить модель CPM"), "Exact resolved current caption");
            var boundary = new RuntimeBoundary(null, null, SkinVariant.CLASSIC, new byte[0]);
            boundary.presented = failed;
            Object screen = type.getConstructor().newInstance();
            var host = (ScreenTerminal) screen;
            host.font = new FontTerminal();
            host.minecraft = new ClientTerminal();
            host.minecraft.screen = host;
            for (var field : type.getDeclaredFields()) {
                field.setAccessible(true);
                switch (field.getName()) {
                    case "runtime" -> field.set(screen, boundary);
                    case "currentView", "view" -> field.set(screen, failed);
                    case "initialized", "activeScreen" -> field.setBoolean(screen, true);
                    case "capabilities" -> field.set(screen, new Capabilities() {
                        public PreviewRenderer<Graphics> createEditorPreviewRenderer() { throw new AssertionError("No renderer"); }
                        public PreviewRenderer<Graphics> createSimplePreviewRenderer() { throw new AssertionError("No renderer"); }
                    });
                }
            }
            var graphics = new Graphics();
            invoke(type, screen, owner.contains("extraction") ? "drawText" : "renderText",
                    new Class<?>[]{Graphics.class, ViewSpec.class, ViewSpec.Text.class, int.class, int.class}, graphics, failed, caption, 0, 0);
            check(String.join("", graphics.drawn).equals(expected), "Caption is drawn completely");
            if (!((TextValue) type.getMethod("getNarrationMessage").invoke(screen)).getString().equals("Screen\n" + expected))
                missingNarration.add(context);
            boundary.presented = ordinary;
            check(((TextValue) type.getMethod("getNarrationMessage").invoke(screen)).getString().equals("Screen"), "Source replacement removes caption despite cached paint view");
            boundary.presented = failed;
            host.minecraft.screen = new ScreenTerminal();
            check(((TextValue) type.getMethod("getNarrationMessage").invoke(screen)).getString().equals("Screen"), "Unrelated current screen rejects caption");
            host.minecraft.screen = host;
            boundary.screenClosed = true;
            check(((TextValue) type.getMethod("getNarrationMessage").invoke(screen)).getString().equals("Screen"), "Closed runtime rejects caption");
            boundary.screenClosed = false;
            String inactiveName = owner.endsWith("NclSkinsImmediateScreen") ? "removed" : "activeScreen";
            var inactive = type.getField(inactiveName);
            inactive.setBoolean(screen, inactiveName.equals("removed"));
            check(((TextValue) type.getMethod("getNarrationMessage").invoke(screen)).getString().equals("Screen"), "Removed host rejects caption");
            inactive.setBoolean(screen, !inactiveName.equals("removed"));
            if (!owner.endsWith("NclSkinsImmediateScreen")) {
                var rebuilding = type.getField("rebuilding");
                rebuilding.setBoolean(screen, true);
                check(((TextValue) type.getMethod("getNarrationMessage").invoke(screen)).getString().equals("Screen"), "Rebuilding host rejects caption");
                rebuilding.setBoolean(screen, false);
            }
            var link = type.getField("showingAccountLink");
            link.setBoolean(screen, true);
            check(((TextValue) type.getMethod("getNarrationMessage").invoke(screen)).getString().equals("Screen"), "Account-link host rejects caption");
        }
        check(missingNarration.isEmpty(), "Drawn Text alone is not narration: " + missingNarration);
        System.out.println("PASS compiled current caption draw/getNarrationMessage for Editor and Providers: " + owner + "; source/close/current-screen/removed/rebuilding/account-link guards, synthetic terminals, NOT GPU/spoken narration");
    }

    public interface TextValue {
        String getString();
        Text copy();
        static Text literal(String value) { return Text.literal(value); }
        static Text translatable(String key, Object... arguments) { return Text.translatable(key, arguments); }
    }

    public static final class Text implements TextValue {
        static Map<String, String> language = Map.of();
        private String value;
        private Text(String value) { this.value = value; }
        public static Text literal(String value) { return new Text(value); }
        public static Text translatable(String key, Object... arguments) {
            String value = language.getOrDefault(key, key);
            return new Text(arguments.length == 0 ? value : String.format(java.util.Locale.ROOT, value, arguments));
        }
        public String getString() { return value; }
        public Text copy() { return new Text(value); }
        public Text append(String tail) { value += tail; return this; }
        public Text append(TextValue tail) { return append(tail.getString()); }
    }

    public static final class FontTerminal {
        public int lineHeight = 9;
        public List<TextValue> split(TextValue text, int width) {
            var lines = new ArrayList<TextValue>();
            int chars = Math.max(1, width / 6);
            String value = text.getString();
            for (int start = 0; start < value.length(); start += chars) lines.add(Text.literal(value.substring(start, Math.min(value.length(), start + chars))));
            return lines;
        }
        public int width(TextValue text) { return text.getString().length() * 6; }
        public String plainSubstrByWidth(String text, int width) { return text.substring(0, Math.min(text.length(), Math.max(0, width / 6))); }
    }

    public static class ScreenTerminal {
        public FontTerminal font;
        public ClientTerminal minecraft;
        public int width = 180, height = 120;
        public TextValue getNarrationMessage() { return Text.literal("Screen"); }
    }

    public static final class ClientTerminal { public ScreenTerminal screen; }
    public static final class GuiTerminal {
        public static ScreenTerminal currentScreen(ClientTerminal client) { return client.screen; }
    }

    public static final class Graphics {
        final List<String> drawn = new ArrayList<>();
        public void enableScissor(int left, int top, int right, int bottom) {}
        public void disableScissor() {}
        public int drawStringResult(FontTerminal font, TextValue text, int x, int y, int color, boolean shadow) { drawn.add(text.getString()); return 0; }
        public void drawString(FontTerminal font, TextValue text, int x, int y, int color) { drawn.add(text.getString()); }
        public void text(FontTerminal font, TextValue text, int x, int y, int color) { drawn.add(text.getString()); }
    }

    public interface Capabilities {
        PreviewRenderer<Graphics> createEditorPreviewRenderer();
        PreviewRenderer<Graphics> createSimplePreviewRenderer();
        default com.naocraftlab.skins.runtime.ViewChromeMetrics viewChromeMetrics() { return com.naocraftlab.skins.runtime.ViewChromeMetrics.STANDARD; }
    }

    public static final class RenderTerminal implements PreviewRenderer<Graphics>, AutoCloseable {
        final List<PreviewRequest> requests = new ArrayList<>();
        PreviewAccessories policy = PreviewAccessories.unavailable(PreviewRenderer.LiveReason.LIVE_UNAVAILABLE);
        Consumer<PreviewAccessories> accessoryListener;
        public void observeAccessories(Consumer<PreviewAccessories> observer) { accessoryListener = observer; }
        public PreviewAccessories accessories(PreviewRequest request) { return policy; }
        public RenderTerminal() {}
        public RenderTerminal(DiagnosticSink diagnostics) {}
        RuntimeBoundary boundary;
        int preparations;
        boolean displayed, ordinary, current = true, closed;
        public com.naocraftlab.skins.client.PreviewModelFrames.Body body() {
            return new com.naocraftlab.skins.client.PreviewModelFrames.Body(displayed ? BodyStatus.READY : BodyStatus.ORDINARY,
                    () -> (displayed || ordinary) && current && !closed);
        }
        public void prepareBody(String source, boolean expected) {
            check(boundary.bodyRegistrations > 0, "Actual host registers body supplier before prepareBody");
            preparations++;
        }
        public void render(Graphics graphics, PreviewRequest request) { requests.add(request); }
        public void close() { closed = true; }
    }

    public static final class Registry implements TextureRegistry {
        final Map<TextureHandle, byte[]> registered = new HashMap<>();
        public TextureHandle register(TextureKind kind, String hash, Path path) { throw new AssertionError("No path reread"); }
        public TextureHandle register(TextureKind kind, String hash, byte[] bytes) {
            check(kind == TextureKind.PLAYER_SKIN, "Only selected body texture registered");
            var handle = new TextureHandle("fixture:selected-" + hash, 64, 64);
            registered.put(handle, bytes.clone());
            return handle;
        }
        public void release(TextureHandle handle) {}
        public void close() {}
    }

    public static final class RuntimeBoundary {
        private final ClientRuntime runtime;
        private final byte[] ordinary;
        final PlayerAppearance current;
        final byte[] currentBytes;
        int loads, borrowed, bodyRegistrations, releases;
        Supplier<PreviewRenderer.DisplayKind> lastSource;
        Consumer<PreviewRenderer.BodyStatus> lastRegistration;
        Consumer<PreviewAccessories> lastAccessoryRegistration;
        Consumer<Consumer<PreviewAccessories>> beforeAccessoryRelease;
        int accessoryReleases;
        boolean pending, screenClosed;
        ViewSpec presented;
        public boolean closed() { return screenClosed || runtime != null && runtime.closed(); }
        public ViewSpec view(int width, int height, int x, int y) { return presented; }
        public ViewSpec view(int width, int height, int x, int y, com.naocraftlab.skins.runtime.ViewChromeMetrics chrome) { return presented; }
        public RuntimeBoundary(ClientRuntime runtime, byte[] ordinary, SkinVariant selected, byte[] currentBytes) {
            this.currentBytes = currentBytes.clone();
            this.runtime = runtime;
            this.ordinary = ordinary;
            current = new PlayerAppearance(new TextureRegistry.TextureHandle("fixture:current-A", 64, 64),
                    selected == SkinVariant.SLIM ? SkinModel.CLASSIC : SkinModel.SLIM, Optional.empty());
        }
        public CompletableFuture<Optional<byte[]>> loadSkinPreview(ViewSpec.Preview preview) {
            loads++;
            if (pending) return new CompletableFuture<>();
            return ordinary == null ? runtime.loadSkinPreview(preview) : CompletableFuture.completedFuture(Optional.of(ordinary.clone()));
        }
        public Optional<PlayerAppearance> previewPlayerAppearance(ViewSpec.Preview preview) {
            borrowed++;
            return Optional.of(current);
        }
        public void reportSkinPreviewFailure(ViewSpec.Preview preview) { throw new AssertionError("Owned registration failure"); }
        public String previewCapeSurfaceKey(ViewSpec.Preview preview) { return runtime.previewCapeSurfaceKey(preview); }
        public String previewBodySource(ViewSpec.Preview preview) { return runtime.previewBodySource(preview); }
        public boolean waitsForCpmBody(ViewSpec.Preview preview) { return runtime.waitsForCpmBody(preview); }
        public Consumer<PreviewRenderer.BodyStatus> observePreviewBody(ViewSpec.Preview preview,
                Supplier<PreviewRenderer.DisplayKind> source) {
            bodyRegistrations++;
            lastSource = source;
            return lastRegistration = runtime.observePreviewBody(preview, source);
        }
        public void invalidatePreviewBody(Consumer<PreviewRenderer.BodyStatus> registration) {
            releases++;
            runtime.invalidatePreviewBody(registration);
        }
        public void reconcilePreviewBody(Consumer<PreviewRenderer.BodyStatus> registration) {
            runtime.reconcilePreviewBody(registration);
        }
        public void invalidatePreviewAccessories(Consumer<PreviewAccessories> registration) {
            accessoryReleases++;
            if (beforeAccessoryRelease != null) beforeAccessoryRelease.accept(registration);
            runtime.invalidatePreviewAccessories(registration);
        }
        public Consumer<PreviewAccessories> observePreviewAccessories(ViewSpec.Preview preview, Supplier<PreviewAccessories> source) {
            return lastAccessoryRegistration = runtime.observePreviewAccessories(preview, source);
        }
    }
}

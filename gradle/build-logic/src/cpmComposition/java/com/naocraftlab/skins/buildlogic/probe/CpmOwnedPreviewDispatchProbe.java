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
        if (args.length==2 && args[0].equals("buttons-only")) {
            buttonFocusConsumers();
            return;
        }
        if (args.length==2 && args[0].equals("focus-only")) {
            focusConsumers(Files.readAllBytes(Path.of(args[1]).resolve("classic-base.png")));
            return;
        }
        if (args.length==2 && args[0].equals("ui-only")) {
            previewCaptionConsumers();
            byte[] skin=Files.readAllBytes(Path.of(args[1]).resolve("classic-base.png"));
            inputConsumers(skin);
            focusConsumers(skin);
            buttonFocusConsumers();
            rootOverlayConsumer(skin);
            thumbnailConsumers();
            return;
        }
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
        inputConsumers(current);
        focusConsumers(current);
        buttonFocusConsumers();
        rootOverlayConsumer(current);
        thumbnailConsumers();
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
            if (label.equals("ordinary-owned dispatch sibling") && !immediate) {
                try (var subject = new com.naocraftlab.skins.runtime.GalleryNativePassFixture(request.appearance(),ordinary)) {
                    screenType.getField("editorRenderer").set(screen, terminal);
                    var joined = new RuntimeBoundary(subject.runtime,ordinary,variant,current);
                    terminal.boundary = joined;
                    screenType.getField("runtime").set(screen,joined);
                    var gallery = subject.runtime.view(854,480,0,0);
                    var body = gallery.previews().stream().filter(p -> p.id().equals("gallery.preview")).findFirst().orElseThrow();
                    check(!com.naocraftlab.skins.runtime.PointerRouting.interactivePreview(gallery,853,100)
                            && com.naocraftlab.skins.runtime.PointerRouting.interactivePreview(gallery,
                                    body.anchorBounds().x()+body.anchorBounds().width()/2.0,
                                    body.anchorBounds().y()+body.anchorBounds().height()/2.0),
                            "Fullscreen paint retains anchor-only input; compiled physical negative is exercised by inputConsumers");
                    invoke(screenType,screen,synchronize,new Class<?>[]{ViewSpec.class},gallery);
                    var paint = new Graphics();
                    invoke(screenType,screen,render,new Class<?>[]{Graphics.class,ViewSpec.class},paint,gallery);
                    check(paint.previewIntents.equals(List.of(PreviewRenderer.PreviewIntent.EDITOR_DRAFT)),
                            "Gallery body phase must not paint static cards before workspace: "+paint.previewIntents);
                    check(paint.scissors.isEmpty() || Arrays.equals(paint.scissors.get(0),new int[]{body.bounds().x(),body.bounds().y(),body.bounds().right(),body.bounds().bottom()}),
                            "Gallery full-window body draw must not inherit left input/scroll clip: "+paint.scissors.stream().map(Arrays::toString).toList());
                    int registrations = joined.bodyRegistrations, releases = joined.releases, preparations = terminal.preparations;
                    invoke(screenType,screen,"renderStaticPreviews",new Class<?>[]{Graphics.class,ViewSpec.class},paint,gallery);
                    long cards = gallery.previews().stream().filter(p -> !p.id().equals("gallery.preview")).count();
                    check(paint.previewIntents.size()==1+cards && paint.previewIntents.subList(1,paint.previewIntents.size()).stream()
                            .allMatch(intent -> intent==PreviewRenderer.PreviewIntent.ASSET_THUMBNAIL),
                            "Static phase paints each actual Gallery card once after body/workspace");
                    check(registrations==joined.bodyRegistrations && releases==joined.releases && preparations==terminal.preparations,
                            "Static phase must not retire or reprepare owned observer");
                    subject.runtime.closeScreen();
                    var cancelled = new Graphics();
                    invoke(screenType,screen,render,new Class<?>[]{Graphics.class,ViewSpec.class},cancelled,gallery);
                    check(cancelled.previewIntents.isEmpty() && terminal.preparations==preparations,
                            "Retired Gallery capture cannot fall through ordinary prepareBody");
                    invoke(screenType,screen,"releasePreviewAccessories",new Class<?>[0]);
                    invoke(screenType,screen,"releasePreviewBody",new Class<?>[0]);
                }
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
        ClientTerminal.instance.lastInputType=net.minecraft.client.InputType.KEYBOARD_TAB;
        String owner = CpmPreviewScreenSlice.availableOwner();
        Class<?> type = CpmPreviewScreenSlice.load(owner, true);
        galleryPaintConsumers(type, owner);
        if (!owner.endsWith("NclSkinsImmediateScreen")) surfaceConsumers(owner);
        marqueeConsumers(type,owner);
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

    private static void marqueeConsumers(Class<?> type,String owner) throws Exception {
        Object screen=type.getConstructor().newInstance();
        ((ScreenTerminal)screen).font=new FontTerminal();
        for(var field:type.getDeclaredFields()) if(field.getType()==Map.class) field.set(screen,new HashMap<String,WidgetTerminal>());
        var bounds=new com.naocraftlab.skins.runtime.Bounds(14,103,30,10);
        var activation=new ViewSpec.MarqueeActivation(new com.naocraftlab.skins.runtime.Bounds(10,20,50,96),List.of("owner"),
                Optional.of(new com.naocraftlab.skins.runtime.Bounds(10,103,34,10)));
        var text=new ViewSpec.Text("name",bounds,UiMessage.literal("Very long fixture name",UiMessage.Severity.INFO),
                ViewSpec.Text.Alignment.LEFT,Optional.of(activation));
        var view=new ViewSpec("gallery",UiMessage.literal("Gallery",UiMessage.Severity.INFO),200,200,List.of(),List.of(text),List.of(),List.of(),Optional.empty());
        var graphics=new Graphics();
        String method=owner.contains("extraction") ? "drawText" : "renderText";
        invoke(type,screen,method,new Class<?>[]{Graphics.class,ViewSpec.class,ViewSpec.Text.class,int.class,int.class},graphics,view,text,15,105);
        check(graphics.scissors.stream().anyMatch(a -> Arrays.equals(a,new int[]{10,103,44,113})),
                "Moving name clips from card edge to arrow edge, not padded text origin: "+graphics.scissors.stream().map(Arrays::toString).toList());
        check(graphics.textPoints.size()==1 && graphics.textPoints.get(0)[0]<=14,"Moving draw origin retains its padded anchor");
        graphics.scissors.clear();graphics.textPoints.clear();graphics.drawn.clear();
        invoke(type,screen,method,new Class<?>[]{Graphics.class,ViewSpec.class,ViewSpec.Text.class,int.class,int.class},graphics,view,text,190,190);
        check(graphics.scissors.isEmpty() && graphics.textPoints.get(0)[0]==14,"Idle name stays padded without moving clip");
        var shortText=new ViewSpec.Text("name",bounds,UiMessage.literal("Hi",UiMessage.Severity.INFO),ViewSpec.Text.Alignment.LEFT,Optional.of(activation));
        graphics.scissors.clear();graphics.textPoints.clear();
        invoke(type,screen,method,new Class<?>[]{Graphics.class,ViewSpec.class,ViewSpec.Text.class,int.class,int.class},graphics,view,shortText,15,105);
        check(graphics.scissors.isEmpty() && graphics.textPoints.get(0)[0]==14,"Short active text does not marquee");
        var clippedView = new ViewSpec("gallery",view.title(),200,200,List.of(),List.of(text),List.of(),List.of(),Optional.empty(),List.of(),Optional.empty(),
                List.of(new ViewSpec.ClipRegion("viewport",new com.naocraftlab.skins.runtime.Bounds(12,104,28,8),List.of("name"))),
                List.of(),List.of(),List.of(),List.of(),List.of(),List.of());
        graphics.scissors.clear();
        invoke(type,screen,method,new Class<?>[]{Graphics.class,ViewSpec.class,ViewSpec.Text.class,int.class,int.class},graphics,clippedView,text,15,105);
        check(graphics.scissors.stream().anyMatch(a -> Arrays.equals(a,new int[]{12,104,40,112})),"Moving clip intersects card viewport edge");
        var defaultText = new ViewSpec.Text("name",bounds,text.message(),ViewSpec.Text.Alignment.LEFT,
                Optional.of(new ViewSpec.MarqueeActivation(activation.hoverBounds(),List.of("owner"))));
        graphics.scissors.clear();
        invoke(type,screen,method,new Class<?>[]{Graphics.class,ViewSpec.class,ViewSpec.Text.class,int.class,int.class},graphics,view,defaultText,15,105);
        check(graphics.scissors.stream().anyMatch(a -> Arrays.equals(a,new int[]{14,103,44,113})),"Existing default Catalog/Import clip stays padded");
        for (var viewport : List.of(
                new com.naocraftlab.skins.runtime.Bounds(10,20,50,60),
                new com.naocraftlab.skins.runtime.Bounds(10,20,50,83),
                new com.naocraftlab.skins.runtime.Bounds(44,20,16,96))) {
            var hiddenView = new ViewSpec("gallery",view.title(),200,200,List.of(),List.of(text),List.of(),List.of(),Optional.empty(),List.of(),Optional.empty(),
                    List.of(new ViewSpec.ClipRegion("viewport",viewport,List.of("name"))),List.of());
            int mouseX=viewport.x()+1, mouseY=viewport.y()+1;
            check(com.naocraftlab.skins.runtime.MarqueeRouting.active(hiddenView,text,mouseX,mouseY,ignored -> false),
                    "Visible card hover activates its offscreen name");
            graphics.scissors.clear();graphics.textPoints.clear();graphics.drawn.clear();
            invoke(type,screen,method,new Class<?>[]{Graphics.class,ViewSpec.class,ViewSpec.Text.class,int.class,int.class},graphics,hiddenView,text,mouseX,mouseY);
            check(graphics.drawn.isEmpty() && graphics.textPoints.isEmpty() && graphics.scissors.isEmpty(),
                    "Hidden or edge-touching hovered name emits no text/scissor: "+viewport);
            invoke(type,screen,method,new Class<?>[]{Graphics.class,ViewSpec.class,ViewSpec.Text.class,int.class,int.class},graphics,clippedView,text,15,105);
            check(graphics.textPoints.size()==1 && graphics.drawn.size()==1 && graphics.scissors.size()==1
                    && Arrays.equals(graphics.scissors.get(0),new int[]{12,104,40,112}),
                    "Visible partial marquee re-enters normally after empty hover clip");
        }
        var focused = new WidgetTerminal(true);
        var linkedWidgets = new HashMap<String,WidgetTerminal>();
        linkedWidgets.put("owner",focused);
        type.getField(owner.contains("submission") ? "widgets" : "nativeWidgets").set(screen,linkedWidgets);
        for (var viewport : List.of(
                new com.naocraftlab.skins.runtime.Bounds(10,20,50,60),
                new com.naocraftlab.skins.runtime.Bounds(60,20,20,96),
                new com.naocraftlab.skins.runtime.Bounds(-20,20,20,96),
                new com.naocraftlab.skins.runtime.Bounds(10,120,50,20),
                new com.naocraftlab.skins.runtime.Bounds(44,20,20,96),
                new com.naocraftlab.skins.runtime.Bounds(-10,20,20,96),
                new com.naocraftlab.skins.runtime.Bounds(10,20,50,83),
                new com.naocraftlab.skins.runtime.Bounds(10,113,50,20))) {
            var hiddenView = new ViewSpec("gallery",view.title(),200,200,List.of(),List.of(text),List.of(),List.of(),Optional.empty(),List.of(),Optional.empty(),
                    List.of(new ViewSpec.ClipRegion("viewport",viewport,List.of("name"))),List.of());
            graphics.scissors.clear();graphics.textPoints.clear();graphics.drawn.clear();
            invoke(type,screen,method,new Class<?>[]{Graphics.class,ViewSpec.class,ViewSpec.Text.class,int.class,int.class},graphics,hiddenView,text,190,190);
            check(graphics.drawn.isEmpty() && graphics.textPoints.isEmpty() && graphics.scissors.isEmpty(),
                    "Linked native focus with hidden/edge name emits no text/scissor: "+viewport);
        }
        graphics.scissors.clear();graphics.textPoints.clear();graphics.drawn.clear();
        invoke(type,screen,method,new Class<?>[]{Graphics.class,ViewSpec.class,ViewSpec.Text.class,int.class,int.class},graphics,clippedView,text,190,190);
        check(graphics.textPoints.size()==1 && graphics.drawn.size()==1 && graphics.scissors.size()==1
                && Arrays.equals(graphics.scissors.get(0),new int[]{12,104,40,112}),
                "Linked native focus activates visible partial marquee after empty clip");
        focused.setFocused(false);
        graphics.scissors.clear();graphics.textPoints.clear();graphics.drawn.clear();
        invoke(type,screen,method,new Class<?>[]{Graphics.class,ViewSpec.class,ViewSpec.Text.class,int.class,int.class},graphics,view,text,190,190);
        check(graphics.scissors.isEmpty() && graphics.textPoints.size()==1 && graphics.textPoints.get(0)[0]==14,
                "Releasing linked focus restores idle padded text after skip/re-entry");
        System.out.println("PASS compiled native moving/idle/short/default/partial marquee; hovered and linked-native-focus offscreen/edge zero text/scissor; visible re-entry: "+owner);
    }

    private static void galleryStripConsumers(Class<?> type,String owner,com.naocraftlab.skins.runtime.GalleryNativePassFixture fixture,Object screen) throws Exception {
        for(String query:List.of("","B")) {
        fixture.runtime.dispatchText("gallery.search",query);
        for(int width:List.of(120,240,854)) for(int height:List.of(150,180,480)) for(double offset:List.of(0.0,37.5)) {
            fixture.runtime.view(width,height,0,0);
            fixture.runtime.nativeScrollPositionChanged("gallery.cards",offset);
            var view=fixture.runtime.view(width,height,0,0);
            var cards=view.widgets().stream().filter(w->w.id().startsWith("gallery.card.")).toList();
            check(!cards.isEmpty(),"Real Gallery produces cards at bounded size");
            if(width==854 && height==480 && offset==0) check(cards.size()==(query.isEmpty()?2:1),"Real runtime query produces exact singleton/multiple-card topology: query="+query+" cards="+cards.size());
            if(width==854 && query.isEmpty()) check(cards.get(0).bounds().y()==cards.get(1).bounds().y(),"Actual two adjacent runtime cards, not hand-built panels");
            var graphics=new Graphics();
            invoke(type,screen,"renderSurfaceOverlays",new Class<?>[]{Graphics.class,ViewSpec.class},graphics,view);
            for(var card:cards) {
                var b=card.bounds();String id=card.id().replace("gallery.card.","gallery.preset.")+".namebar";
                var panel=view.panels().stream().filter(p->p.id().equals(id)).findFirst().orElseThrow();
                check(panel.bounds().bottom()==b.bottom() && panel.bounds().y()==b.bottom()-16,"F5 real producer namebar reaches card bottom");
                var clip=com.naocraftlab.skins.runtime.ViewHostPolicy.paintClip(view,id).orElseThrow();
                if(panel.bounds().bottom()<=clip.y() || panel.bounds().y()>=clip.bottom()) continue;
                int index=-1;
                for(int i=0;i<graphics.fills.size();i++) if(Arrays.equals(graphics.fills.get(i),new int[]{b.x(),b.bottom()-16,b.right(),b.bottom(),0x6D000000})) {check(index<0,"Unique exact normal namebar fill");index=i;}
                check(index>=0,"Actual compiled fill reaches exact bottom of same runtime card: "+owner);
                check(Arrays.equals(graphics.fillClips.get(index),new int[]{clip.x(),clip.y(),clip.right(),clip.bottom()}),"Exact fill carries its own unchanged scissor, including partial bottom clip");
            }
        }
        }
        fixture.runtime.dispatchText("gallery.search","");
        fixture.runtime.nativeScrollPositionChanged("gallery.cards",0);
        System.out.println("PASS real runtime singleton/query and multiple columns/widths/fractional scroll/partial clips -> compiled normal background fill reaches card.bottom: "+owner);
    }

    private static void galleryCaptionConsumers(Class<?> type, String owner) throws Exception {
        var appearance = new PreviewRenderer.PreviewAppearance(new TextureRegistry.TextureHandle("fixture:caption",64,64),
                SkinModel.CLASSIC,Optional.empty(),PreviewRenderer.CapeMode.OFF,true);
        try (var fixture = new com.naocraftlab.skins.runtime.GalleryNativePassFixture(appearance,
                Files.readAllBytes(Path.of("core/src/test/resources/cpm/classic-a.png")))) {
            Object screen=type.getConstructor().newInstance();
            ((ScreenTerminal)screen).font=new FontTerminal();
            for(var field:type.getDeclaredFields()) if(field.getType()==Map.class) field.set(screen,new HashMap<String,WidgetTerminal>());
            String prefix="gallery.preset.00000000-0000-0000-0000-000000000322.";
            galleryStripConsumers(type,owner,fixture,screen);
            for (String mode : List.of("normal","actions","delete")) {
                if (mode.equals("actions")) fixture.runtime.dispatchWidget(prefix+"more");
                if (mode.equals("delete")) fixture.runtime.dispatchWidget(prefix+"delete");
                var view=fixture.runtime.view(854,480,0,0);
                var title=view.texts().stream().filter(t -> t.id().equals(prefix+"name")).findFirst().orElseThrow();
                var card=view.widget("gallery.card.00000000-0000-0000-0000-000000000322").orElseThrow();
                var more=view.widget(prefix+"more").orElseThrow();
                int expected=mode.equals("normal") ? more.bounds().y()+4 : card.bounds().y()+5;
                check(title.bounds().y()==expected,"F4 source caption Y for "+mode);
                var longTitle=new ViewSpec.Text(title.id(),title.bounds(),UiMessage.literal("Very long synthetic caption that overflows its card",UiMessage.Severity.INFO),
                        title.alignment(),title.marqueeActivation());
                String method=owner.contains("extraction") ? "drawText" : "renderText";
                for (boolean hover : List.of(false,true)) {
                    var graphics=new Graphics();
                    var activation=title.marqueeActivation().orElseThrow().hoverBounds();
                    invoke(type,screen,method,new Class<?>[]{Graphics.class,ViewSpec.class,ViewSpec.Text.class,int.class,int.class},
                            graphics,view,longTitle,hover ? activation.x()+1 : -1,hover ? activation.y()+1 : -1);
                    check(graphics.textPoints.size()==1 && graphics.textPoints.get(0)[1]==expected,"F4 actual compiled idle/moving caption Y for "+mode);
                    graphics.textPoints.clear();graphics.drawn.clear();
                    invoke(type,screen,"renderSurfaceOverlays",new Class<?>[]{Graphics.class,ViewSpec.class},graphics,view);
                    int arrow=more.bounds().y()+Math.max(0,(more.bounds().height()-((ScreenTerminal)screen).font.lineHeight)/2)+2;
                    check(graphics.textPoints.stream().anyMatch(p -> p[1]==arrow),"F3 arrow Y remains unchanged for "+mode);
                }
            }
        }
        System.out.println("PASS F4 compiled normal/actions/delete caption -1 and unchanged F3 arrows: "+owner);
    }

    private static void surfaceConsumers(String owner) throws Exception {
        Class<?> type = CpmPreviewScreenSlice.loadSurface(owner);
        String method = owner.contains("submission") ? "renderFramePanels" : "drawFrameBackgrounds";
        var alpha = new ViewSpec.Panel("name",new com.naocraftlab.skins.runtime.Bounds(10,20,50,16),ViewSpec.Panel.Style.TRANSLUCENT_NAMEBAR);
        for (boolean world : List.of(false,true)) {
            ClientTerminal.instance.level = world ? new Object() : null;
            var view = new ViewSpec("gallery",UiMessage.literal("Gallery",UiMessage.Severity.INFO),200,200,
                    List.of(alpha),List.of(),List.of(),List.of(),Optional.empty());
            var graphics = new Graphics();
            type.getMethod(method,Graphics.class,ViewSpec.class).invoke(null,graphics,view);
            check(graphics.blits.isEmpty(),"Translucent namebar must not enter opaque native panel dispatch: "+graphics.blits);
            var header = new ViewSpec.Panel("header",alpha.bounds(),ViewSpec.Panel.Style.VANILLA_HEADER);
            view = new ViewSpec("gallery",view.title(),200,200,List.of(header),List.of(),List.of(),List.of(),Optional.empty());
            type.getMethod(method,Graphics.class,ViewSpec.class).invoke(null,graphics,view);
            check(graphics.blits.stream().anyMatch(x -> x.contains(world ? "inworld_menu_list_background" : "menu_list_background")),
                    "Native frame positive retains menu/world texture");
        }
        ClientTerminal.instance.level = null;
        System.out.println("PASS actual compiled native alpha exclusion/menu-world frame dispatch: "+owner);
    }

    private static void galleryPaintConsumers(Class<?> type, String owner) throws Exception {
        galleryCaptionConsumers(type, owner);
        var b = new com.naocraftlab.skins.runtime.Bounds(10,20,50,96);
        var bar = new ViewSpec.Widget("gallery.preset.fixture.more",ViewSpec.WidgetKind.TRANSPARENT_BUTTON,
                new com.naocraftlab.skins.runtime.Bounds(10,100,50,16),UiMessage.info("nclskins.gallery.actions"),
                Optional.of("up"),Optional.empty(),true,true,0);
        var card = new ViewSpec.Widget("gallery.card.fixture",ViewSpec.WidgetKind.CATALOG_CARD,b,
                UiMessage.literal("Fixture",UiMessage.Severity.INFO),Optional.of("selected_active"),Optional.empty(),true,true,0);
        var view = new ViewSpec("gallery",UiMessage.info("nclskins.gallery.title"),200,200,List.of(),List.of(),
                List.of(card,bar),List.of(),Optional.empty(),List.of(),Optional.empty());
        Object screen=type.getConstructor().newInstance();
        ((ScreenTerminal)screen).font=new FontTerminal();
        for(var field:type.getDeclaredFields()) {
            if(field.getType()==Map.class) field.set(screen,new HashMap<String,WidgetTerminal>());
        }
        var graphics=new Graphics();
        invoke(type,screen,"renderSurfaceOverlays",new Class<?>[]{Graphics.class,ViewSpec.class},graphics,view);
        check(graphics.drawn.equals(List.of("▲")),"Actual native transparent bar paints arrow, not narration label: "+graphics.drawn);
        var tooltipCard=new ViewSpec.Widget("gallery.inspect.CPM",ViewSpec.WidgetKind.CAPE_CARD,b,
                UiMessage.literal("CPM cape",UiMessage.Severity.INFO),Optional.empty(),Optional.of(UiMessage.literal("Native cape",UiMessage.Severity.INFO)),true,true,0);
        var tooltipView=new ViewSpec("gallery",view.title(),200,200,List.of(),List.of(),List.of(tooltipCard),List.of(),Optional.empty(),List.of(),Optional.empty(),
                List.of(new ViewSpec.ClipRegion("gallery.stage",new com.naocraftlab.skins.runtime.Bounds(10,30,50,50),List.of(tooltipCard.id()))),List.of());
        String tooltipMethod=owner.contains("extraction") ? "drawPassiveIndicatorTooltip" : "renderPassiveIndicatorTooltip";
        invoke(type,screen,tooltipMethod,new Class<?>[]{Graphics.class,ViewSpec.class,int.class,int.class},graphics,tooltipView,20,35);
        check(!graphics.tooltips.isEmpty(),"Actual native CPM hover emits tooltip");
        graphics.tooltips.clear();
        invoke(type,screen,tooltipMethod,new Class<?>[]{Graphics.class,ViewSpec.class,int.class,int.class},graphics,tooltipView,20,25);
        check(graphics.tooltips.isEmpty(),"Actual native CPM tooltip rejects outside lower clip");
        graphics.fills.clear();
        String field=owner.contains("submission") ? "widgets" : "nativeWidgets";
        var map=new HashMap<String,WidgetTerminal>();map.put(card.id(),new WidgetTerminal(true));
        type.getField(field).set(screen,map);
        invoke(type,screen,"renderStateFrames",new Class<?>[]{Graphics.class,ViewSpec.class},graphics,view);
        check(graphics.fills.stream().anyMatch(f -> f[4]==0xFF80C880),"Active green remains independently visible with selected draft cyan");
        check(graphics.fills.stream().anyMatch(f -> f[0]==11 && f[1]==21 && f[4]==0xFFFFFFFF),"Actual inner focus remains white");
        map.clear();
        var opened = new ViewSpec.Widget(card.id(),card.kind(),b,card.label(),Optional.of("menu"),Optional.empty(),false,true,0);
        var openedView = new ViewSpec("gallery",view.title(),200,200,List.of(),List.of(),List.of(opened),List.of(),Optional.empty(),List.of(),Optional.empty());
        graphics.fills.clear();
        invoke(type,screen,"renderStateFrames",new Class<?>[]{Graphics.class,ViewSpec.class},graphics,openedView);
        check(!graphics.fills.isEmpty(),"Unselected unfocused open menu retains a visible outer frame");
        check(graphics.fills.stream().noneMatch(f -> f[4]==0xFFFFFFFF),"Open menu does not fabricate keyboard focus");
        check(graphics.fills.stream().filter(f -> f[4]==0xFF80C8FF).allMatch(f -> f[0]==10 || f[1]==20 || f[2]==60 || f[3]==116),"Draft color stays on outer edge");
        for (String state : List.of("active","selected","selected_active","menu","")) for (boolean focused : List.of(false,true)) {
            var row = new ViewSpec.Widget(card.id(),card.kind(),b,card.label(),state.isEmpty()?Optional.empty():Optional.of(state),Optional.empty(),true,true,0);
            map.clear();map.put(row.id(),new WidgetTerminal(focused));graphics.fills.clear();
            var rowView = new ViewSpec("gallery",view.title(),200,200,List.of(),List.of(),List.of(row),List.of(),Optional.empty());
            invoke(type,screen,"renderStateFrames",new Class<?>[]{Graphics.class,ViewSpec.class},graphics,rowView);
            check(graphics.fills.stream().anyMatch(f -> f[4]==0xFF80C880)==state.contains("active"),"Native active segment: "+state);
            check(graphics.fills.stream().anyMatch(f -> f[4]==0xFF80C8FF)==state.contains("selected"),"Native draft segment: "+state);
            check(graphics.fills.stream().anyMatch(f -> f[4]==0xFFFFFFFF)==(focused&&!state.isEmpty()),"Native independent white focus: "+state);
            if(state.isEmpty()) check(graphics.fills.isEmpty(),"Default Catalog/Import frames remain native widget-owned");
            check(graphics.fills.stream().allMatch(f -> f[0]>=10 && f[1]>=20 && f[2]<=60 && f[3]<=116),"Frame bounds unchanged");
        }
        var cape=new ViewSpec.Widget("gallery.inspect.CPM",ViewSpec.WidgetKind.CAPE_CARD,b,card.label(),Optional.of("active"),Optional.empty(),true,true,0);
        var alpha=new ViewSpec.Panel("gallery.preset.fixture.namebar",new com.naocraftlab.skins.runtime.Bounds(12,22,46,92),ViewSpec.Panel.Style.TRANSLUCENT_NAMEBAR);
        var capeView=new ViewSpec("gallery",view.title(),200,200,List.of(alpha),List.of(),List.of(cape),List.of(),Optional.empty());
        graphics.fills.clear();graphics.drawn.clear();
        invoke(type,screen,"renderSurfaceOverlays",new Class<?>[]{Graphics.class,ViewSpec.class},graphics,capeView);
        check(graphics.fills.size()==1 && graphics.fills.get(0)[4]==0x6D000000,"Actual Gallery overlay uses baseline-relative alpha");
        String pointerX=owner.contains("submission")?"mouseX":"lastMouseX",pointerY=owner.contains("submission")?"mouseY":"lastMouseY";
        type.getField(pointerX).setInt(screen,20);type.getField(pointerY).setInt(screen,30);
        map.clear();map.put(cape.id(),new WidgetTerminal(true));graphics.fills.clear();
        invoke(type,screen,"renderStateFrames",new Class<?>[]{Graphics.class,ViewSpec.class},graphics,capeView);
        check(graphics.fills.get(0)[4]==0x18FFFFFF,"Actual cape foreground hover precedes frames after content");
        check(graphics.fills.stream().anyMatch(f->f[4]==0xFF80C880),"Native cape uses green preview frame");
        check(graphics.fills.stream().noneMatch(f->f[4]==0xFF80C8FF),"Native cape never adds draft blue");
        check(graphics.fills.stream().anyMatch(f->f[4]==0xFFFFFFFF),"Native cape preserves white keyboard focus");
        type.getField(pointerX).setInt(screen,190);type.getField(pointerY).setInt(screen,190);graphics.fills.clear();
        invoke(type,screen,"renderStateFrames",new Class<?>[]{Graphics.class,ViewSpec.class},graphics,capeView);
        check(graphics.fills.stream().noneMatch(f->f[4]==0x18FFFFFF),"Focus without pointer leaves no permanent hover fill");
        System.out.println("PASS compiled Gallery alpha/cape foreground hover/green-only/white-focus and transparent arrow paint: "+owner+"; synthetic graphics, NOT GPU");
    }

    private static void rootOverlayConsumer(byte[] skin) throws Exception {
        String owner=CpmPreviewScreenSlice.availableOwner();
        Class<?> type=CpmPreviewScreenSlice.loadRootPaint(owner);
        var appearance=new PreviewRenderer.PreviewAppearance(new TextureRegistry.TextureHandle("fixture:root",64,64),SkinModel.CLASSIC,Optional.empty(),PreviewRenderer.CapeMode.OFF,com.naocraftlab.skins.client.OuterLayerVisibility.allVisible(),true);
        try(var subject=new com.naocraftlab.skins.runtime.GalleryNativePassFixture(appearance,skin)) {
            Class<?> paintType=CpmPreviewScreenSlice.load(owner,true);
            Object paintHost=paintType.getConstructor().newInstance();
            var font=new FontTerminal(); ((ScreenTerminal)paintHost).font=font;
            for(var field:paintType.getDeclaredFields()) if(field.getType()==Map.class) field.set(paintHost,new HashMap<String,WidgetTerminal>());
            String textMethod=owner.contains("extraction") ? "drawText" : "renderText";
            for (int advance : List.of(5,6,7,12)) for (int width : List.of(240,320,427,854)) for (boolean expanded : List.of(false,true)) {
                String prefix="gallery.preset."+subject.preview().presetId().orElseThrow();
                var current=subject.runtime.view(width,480,0,0);
                boolean open=current.widget(prefix+".more").orElseThrow().value().filter("down"::equals).isPresent();
                if(open!=expanded) subject.runtime.dispatchWidget(prefix+".more");
                var produced=subject.runtime.view(width,480,0,0);
                var title=produced.texts().stream().filter(t->t.id().equals(prefix+".name")).findFirst().orElseThrow();
                var arrow=produced.widget(prefix+".more").orElseThrow();
                var card=title.marqueeActivation().orElseThrow().hoverBounds();
                font.advance=advance;
                int arrowLeft=card.right()-6-advance/2;
                int right=arrowLeft-(card.right()-arrowLeft-advance);
                var longTitle=new ViewSpec.Text(title.id(),title.bounds(),UiMessage.literal("Very long Gallery title",UiMessage.Severity.INFO),title.alignment(),title.marqueeActivation());
                var graphics=new Graphics();
                invoke(paintType,paintHost,textMethod,new Class<?>[]{Graphics.class,ViewSpec.class,ViewSpec.Text.class,int.class,int.class},graphics,produced,longTitle,card.x()+1,card.y()+1);
                check(graphics.scissors.stream().anyMatch(a->a[0]==card.x() && a[2]==right),"F3-05 real Gallery title native font-aware clip: advance="+advance+" right="+right+" actual="+graphics.scissors.stream().map(Arrays::toString).toList());
                graphics.drawn.clear();graphics.textPoints.clear();
                invoke(paintType,paintHost,"renderSurfaceOverlays",new Class<?>[]{Graphics.class,ViewSpec.class},graphics,produced);
                int at=graphics.drawn.indexOf(expanded ? "▼" : "▲");
                check(at>=0 && graphics.textPoints.get(at)[1]==arrow.bounds().y()+Math.max(0,(16-font.lineHeight)/2)+2,"F3-04 actual arrow moves down exactly two pixels");
            }
            font.advance=6;
            var view=subject.runtime.view(854,480,0,0);
            var action=view.widgets().stream().filter(w->w.visible() && w.id().startsWith("gallery.preset.") && w.id().endsWith(".edit")).findFirst().orElseThrow();
            var b=action.bounds(); var widget=new WidgetTerminal();widget.x=b.x();widget.y=b.y();widget.width=b.width();widget.height=b.height();
            Object host=type.getConstructor().newInstance();((ScreenTerminal)host).font=new FontTerminal();
            for(var field:type.getDeclaredFields()) {
                if(field.getType()==Map.class) {var map=new HashMap<String,WidgetTerminal>();map.put(action.id(),widget);field.set(host,map);}
                if(field.getType()==List.class) field.set(host,new ArrayList<>(List.of(widget)));
            }
            for(boolean hover:List.of(true,false)) {
                var graphics=new Graphics();
                int x=hover?b.x()+b.width()/2:-100,y=hover?b.y()+b.height()/2:-100;
                invoke(type,host,"nclskins$paintRoot",new Class<?>[]{Graphics.class,int.class,int.class,float.class,ViewSpec.class},graphics,x,y,0F,view);
                int overlay=-1,button=-1;
                for(int i=0;i<graphics.fills.size();i++) {
                    int[] fill=graphics.fills.get(i);
                    if(fill[4]==0x6D000000 && fill[0]<=b.x() && fill[2]>=b.right() && fill[1]<=b.y() && fill[3]>=b.bottom()) overlay=i;
                    if(fill[4]==(hover?0xFFEEEEEE:0xFF444444) && fill[0]==b.x() && fill[1]==b.y() && fill[2]==b.right() && fill[3]==b.bottom()) button=i;
                }
                check(overlay>=0 && button>overlay,"UI16 actual root expanded overlay must paint below native action button, hover="+hover);
                check(graphics.paintStrata.get(button)>=graphics.paintStrata.get(overlay),"UI16 native button cannot occupy an earlier stratum");
            }
            System.out.println("PASS UI16 actual compiled expanded-menu root phase -> actual overlay fill alpha/geometry -> native button paint terminal; hover/outside; "+owner+"; NOT game/GPU");
        }
    }

    private static void thumbnailConsumers() throws Exception {
        String owner=CpmPreviewScreenSlice.availableOwner();
        Class<?> type=CpmPreviewScreenSlice.load(owner);
        var cape=new java.awt.image.BufferedImage(64,32,java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var bytes=new java.io.ByteArrayOutputStream();
        check(javax.imageio.ImageIO.write(cape,"png",bytes),"Cape fixture encoded");
        TextureRegistry registry=new TextureRegistry() {
            public TextureHandle register(TextureKind kind,String hash,Path path) { throw new AssertionError("No path"); }
            public TextureHandle register(TextureKind kind,String hash,byte[] png) { check(kind==TextureKind.IMAGE,"Cape image");return new TextureHandle("fixture:cape",64,32); }
            public void release(TextureHandle handle) {}
            public void close() {}
        };
        try(var cache=new PreviewAssetCache<Object>(registry,TextureRegistry.TextureKind.IMAGE,DiagnosticSinks.discarding())) {
            for(var mode:com.naocraftlab.skins.client.BackEquipmentPreviewRenderer.Mode.values()) {
                for(boolean thumbnail:List.of(false,true)) {
                    var slot=new com.naocraftlab.skins.runtime.Bounds(10,20,32,32);
                    var preview=new ViewSpec.BackEquipmentPreview("equipment",slot,"fixture-cape",mode,true);
                    if(thumbnail) preview=preview.asProviderThumbnail();
                    var view=new ViewSpec("gallery",UiMessage.info("nclskins.title"),180,120,List.of(),List.of(),List.of(),List.of(),Optional.empty(),List.of(),Optional.empty(),
                            List.of(new ViewSpec.ClipRegion("clip",new com.naocraftlab.skins.runtime.Bounds(12,24,26,24),List.of("equipment"))),List.of(preview));
                    cache.request(com.naocraftlab.skins.runtime.ViewHostPolicy.cardCapeKey(preview),()->CompletableFuture.completedFuture(Optional.of(bytes.toByteArray())),()->{});
                    Object screen=type.getConstructor().newInstance();var renderer=new RenderTerminal();
                    for(var field:type.getDeclaredFields()) {
                        Object value=switch(field.getName()) {
                            case "capeTextures" -> cache;
                            case "backEquipmentRenderer" -> renderer;
                            case "capabilities" -> new Capabilities() {
                                public PreviewRenderer<Graphics> createEditorPreviewRenderer(){return renderer;}
                                public PreviewRenderer<Graphics> createSimplePreviewRenderer(){return renderer;}
                            };
                            default -> Map.class.isAssignableFrom(field.getType()) ? new HashMap<>() : null;
                        };
                        if(value!=null) field.set(screen,value);
                    }
                    var graphics=new Graphics();
                    String method=owner.endsWith("NclSkinsImmediateScreen") ? "renderBackEquipmentPreviews" : owner.contains("submission") ? "renderBackEquipment" : "drawBackEquipmentPreviews";
                    invoke(type,screen,method,new Class<?>[]{Graphics.class,ViewSpec.class},graphics,view);
                    if(thumbnail) check(graphics.capeCrops.size()==1 && Arrays.equals(graphics.capeCrops.get(0),new int[]{10,20,32,32,1,4,10,10,64,32}),
                            "Gallery thumbnail must use actual square Providers atlas crop, not portrait equipment renderer: "+owner);
                    else check(graphics.capeCrops.isEmpty() && graphics.equipmentRequests.size()==1
                            && graphics.equipmentRequests.get(0).mode()==mode,"Ordinary cape/elytra keep existing equipment renderer");
                    check(graphics.scissors.stream().anyMatch(b->Arrays.equals(b,new int[]{12,24,38,48})),"Thumbnail/equipment share viewport clip");
                }
            }
        }
        System.out.println("PASS compiled cape/elytra equipment and square Providers thumbnail crop/clip: "+owner+"; terminal graphics, NOT GPU");
    }

    private static void buttonFocusConsumers() throws Exception {
        String owner=CpmPreviewScreenSlice.availableOwner();
        if (!owner.contains("submission")) return;
        Class<?> type=CpmPreviewScreenSlice.loadFocus(owner);
        Object screen=type.getConstructor().newInstance();
        ((ScreenTerminal)screen).font=ClientTerminal.instance.font;
        Class<?> button=type.getClassLoader().loadClass(owner+"$ActionButton");
        var constructor=button.getDeclaredConstructor(type,String.class,com.naocraftlab.skins.runtime.Bounds.class,TextValue.class,
                ViewSpec.WidgetKind.class,Optional.class,boolean.class,boolean.class,boolean.class,Consumer.class);
        constructor.setAccessible(true);
        var render=button.getDeclaredMethod("renderContents",Graphics.class,int.class,int.class,float.class);render.setAccessible(true);
        for(var kind:List.of(ViewSpec.WidgetKind.BUTTON,ViewSpec.WidgetKind.ICON_BUTTON,ViewSpec.WidgetKind.ICON_ONLY_BUTTON,
                ViewSpec.WidgetKind.PASSIVE_INDICATOR,ViewSpec.WidgetKind.INFO_BUTTON,ViewSpec.WidgetKind.CATALOG_CARD,ViewSpec.WidgetKind.CAPE_CARD)) {
            int[] activations={0};
            var widget=(WidgetTerminal)constructor.newInstance(screen,"fixture.button",new com.naocraftlab.skins.runtime.Bounds(10,20,50,20),
                    Text.literal("Fixture"),kind,Optional.of(com.naocraftlab.skins.runtime.GuiIcon.STATUS_COMPATIBILITY_EXTENDED),false,false,false,
                    (Consumer<InputListener>)ignored->activations[0]++);
            ((ScreenTerminal)screen).setFocused(widget);
            ClientTerminal.instance.lastInputType=net.minecraft.client.InputType.MOUSE;
            widget.mouseClicked(new MouseEvent(15,25,0),false);
            check(activations[0]==1,"Actual native onClick -> actual ActionButton action once");
            for(boolean keyboard:List.of(false,true)) for(boolean hover:List.of(false,true)) {
                ClientTerminal.instance.lastInputType=keyboard ? net.minecraft.client.InputType.KEYBOARD_TAB : net.minecraft.client.InputType.MOUSE;
                type.getField("keyboardNavigation").setBoolean(screen,keyboard);
                widget.isHovered=hover;
                var graphics=new Graphics();render.invoke(widget,graphics,hover ? 15 : -100,hover ? 25 : -100,0F);
                if(kind==ViewSpec.WidgetKind.BUTTON || kind==ViewSpec.WidgetKind.ICON_BUTTON) {
                    check(graphics.blits.contains(keyboard || hover ? "widget/button_highlighted" : "widget/button"),
                            "Actual SDK renderDefaultSprite respects modality and genuine hover: "+kind+" keyboard="+keyboard+" hover="+hover+" sprites="+graphics.blits);
                }
                boolean hoverFrame=kind==ViewSpec.WidgetKind.ICON_ONLY_BUTTON || kind==ViewSpec.WidgetKind.PASSIVE_INDICATOR;
                boolean keyboardFrame=com.naocraftlab.skins.runtime.CatalogCardStyle.focusFrameSupported(kind) || hoverFrame;
                check(graphics.fills.stream().anyMatch(f->f[4]==0xFFFFFFFF)==(hoverFrame&&hover || keyboardFrame&&keyboard),
                        "Actual ActionButton focus decoration separates logical focus from pointer: "+kind+" keyboard="+keyboard+" hover="+hover);
                check(widget.isFocused(),"Paint never clears logical focus");
            }
        }
        var tab=new ViewSpec.Widget("preserved.tab",ViewSpec.WidgetKind.TAB_BUTTON,new com.naocraftlab.skins.runtime.Bounds(10,20,24,50),
                UiMessage.literal("Tab",UiMessage.Severity.INFO),Optional.empty(),Optional.empty(),true,true,0);
        var tabView=new ViewSpec("editor",UiMessage.literal("Editor",UiMessage.Severity.INFO),200,200,List.of(),List.of(),List.of(tab),List.of(),Optional.empty());
        var widgets=new HashMap<String,WidgetTerminal>();type.getField("widgets").set(screen,widgets);
        var logical=new WidgetTerminal(true);widgets.put(tab.id(),logical);
        for(boolean keyboard:List.of(false,true)) for(boolean hover:List.of(false,true)) {
            ClientTerminal.instance.lastInputType=keyboard?net.minecraft.client.InputType.KEYBOARD_TAB:net.minecraft.client.InputType.MOUSE;
            var graphics=new Graphics();invoke(type,screen,"renderTabHighlights",new Class<?>[]{Graphics.class,ViewSpec.class,int.class,int.class},graphics,tabView,hover?15:-100,hover?25:-100);
            check(graphics.fills.stream().anyMatch(f->f[4]==0xFFFFFFFF)==(keyboard||hover),"Actual preserved narrow tab overlay distinguishes pointer focus/keyboard/true hover");
            check(logical.isFocused(),"Tab paint preserves logical focus");
        }
        System.out.println("PASS preserved narrow native tab overlay; Gallery itself presents no tabs; synthetic view/control, NOT full tab/GPU");
        System.out.println("PASS actual compiled ActionButton -> exact SDK AbstractButton supplier/default sprite + WidgetSprites; pointer/keyboard/genuine hover/kinds; synthetic widget/device/graphics leaves, NOT GPU");
    }

    private static void siblingInnerFocusConsumers(Class<?> type,Object screen,String owner) throws Exception {
        if(owner.contains("submission")) return;
        boolean immediate=owner.endsWith("NclSkinsImmediateScreen");
        for(String name:List.of("CatalogCardWidget","CapeCardWidget","IconButtonWidget","PassiveIndicatorWidget")) {
            var button=type.getClassLoader().loadClass(owner+"$"+name);
            Class<?>[] signature;
            if(immediate) signature=switch(name) {
                case "CatalogCardWidget" -> new Class<?>[]{type,String.class,int.class,int.class,int.class,int.class,TextValue.class};
                case "CapeCardWidget" -> new Class<?>[]{type,String.class,int.class,int.class,int.class,int.class,TextValue.class,boolean.class,boolean.class};
                case "IconButtonWidget" -> new Class<?>[]{type,String.class,int.class,int.class,int.class,int.class,TextValue.class,com.naocraftlab.skins.runtime.WidgetIcon.class,boolean.class,boolean.class,boolean.class};
                default -> new Class<?>[]{type,int.class,int.class,int.class,int.class,TextValue.class,com.naocraftlab.skins.runtime.GuiIcon.class,Optional.class};
            };
            else signature=switch(name) {
                case "CatalogCardWidget" -> new Class<?>[]{com.naocraftlab.skins.runtime.Bounds.class,TextValue.class,Runnable.class};
                case "CapeCardWidget" -> new Class<?>[]{String.class,com.naocraftlab.skins.runtime.Bounds.class,TextValue.class,boolean.class,boolean.class,Runnable.class};
                case "IconButtonWidget" -> new Class<?>[]{type,String.class,com.naocraftlab.skins.runtime.Bounds.class,TextValue.class,com.naocraftlab.skins.runtime.WidgetIcon.class,boolean.class,boolean.class,boolean.class,Consumer.class};
                default -> new Class<?>[]{type,com.naocraftlab.skins.runtime.Bounds.class,TextValue.class,com.naocraftlab.skins.runtime.WidgetIcon.class,Optional.class};
            };
            var constructor=button.getDeclaredConstructor(signature);constructor.setAccessible(true);
            var bounds=new com.naocraftlab.skins.runtime.Bounds(10,20,50,24);
            var args=new ArrayList<Object>();
            for(Class<?> parameter:constructor.getParameterTypes()) {
                if(parameter==type) args.add(screen);
                else if(parameter==String.class) args.add("gallery.inner.fixture");
                else if(parameter==int.class) args.add(switch(args.stream().filter(Integer.class::isInstance).toList().size()){case 0->10;case 1->20;case 2->50;default->24;});
                else if(parameter==com.naocraftlab.skins.runtime.Bounds.class) args.add(bounds);
                else if(parameter==TextValue.class) args.add(Text.literal("Fixture"));
                else if(parameter==Runnable.class) args.add((Runnable)()->{});
                else if(parameter==Consumer.class) args.add((Consumer<InputListener>)ignored->{});
                else if(parameter==Optional.class) args.add(Optional.empty());
                else if(parameter==boolean.class) args.add(name.equals("IconButtonWidget") && args.stream().noneMatch(Boolean.class::isInstance));
                else if(parameter==com.naocraftlab.skins.runtime.WidgetIcon.class || parameter==com.naocraftlab.skins.runtime.GuiIcon.class) args.add(com.naocraftlab.skins.runtime.GuiIcon.STATUS_COMPATIBILITY_EXTENDED);
                else throw new AssertionError("Unbound exact inner constructor ABI: "+parameter);
            }
            var widget=(WidgetTerminal)constructor.newInstance(args.toArray());
            ((ScreenTerminal)screen).setFocused(widget);
            var paint=button.getDeclaredMethod(immediate?"renderWidget":"extractContents",Graphics.class,int.class,int.class,float.class);paint.setAccessible(true);
            for(boolean keyboard:List.of(false,true)) for(boolean hover:List.of(false,true)) {
                ClientTerminal.instance.lastInputType=keyboard?net.minecraft.client.InputType.KEYBOARD_TAB:net.minecraft.client.InputType.MOUSE;
                type.getField("keyboardNavigation").setBoolean(screen,keyboard);widget.isHovered=hover;
                var graphics=new Graphics();paint.invoke(widget,graphics,hover?15:-100,hover?25:-100,0F);
                boolean hoverFrame=name.equals("IconButtonWidget") || name.equals("PassiveIndicatorWidget");
                check(graphics.fills.stream().anyMatch(f->f[4]==0xFFFFFFFF)==(keyboard || hoverFrame&&hover),"Actual same-field inner paint modality/hover: "+owner+"$"+name+" keyboard="+keyboard+" hover="+hover);
                check(widget.isFocused(),"Inner paint never clears logical focus");
            }
            System.out.println("PASS actual compiled inner "+owner+"$"+name+" pointer-negative/keyboard-positive/hover control; widget-base/graphics terminal");
        }
    }

    private static WidgetTerminal focusButton(Class<?> type,Object screen,ViewSpec.Widget spec,Consumer<InputListener> action) throws Exception {
        Class<?> button=type.getClassLoader().loadClass(type.getName()+"$ActionButton");
        var constructor=button.getDeclaredConstructor(type,String.class,com.naocraftlab.skins.runtime.Bounds.class,TextValue.class,
                ViewSpec.WidgetKind.class,Optional.class,boolean.class,boolean.class,boolean.class,Consumer.class);
        constructor.setAccessible(true);
        return (WidgetTerminal)constructor.newInstance(screen,spec.id(),spec.bounds(),Text.literal(spec.label().key()),spec.kind(),spec.icon(),
                false,false,spec.kind()==ViewSpec.WidgetKind.TRANSPARENT_BUTTON,action);
    }

    private static void focusConsumers(byte[] skin) throws Exception {
        String owner=CpmPreviewScreenSlice.availableOwner();
        Class<?> type=CpmPreviewScreenSlice.loadFocus(owner);
        Object screen=type.getConstructor().newInstance();
        var host=(ScreenTerminal)screen;
        host.width=854;host.height=480;host.font=new FontTerminal();host.minecraft=ClientTerminal.instance;
        var appearance=new PreviewRenderer.PreviewAppearance(new TextureRegistry.TextureHandle("fixture:focus",64,64),
                SkinModel.CLASSIC,Optional.empty(),PreviewRenderer.CapeMode.OFF,true);
        try(var fixture=new com.naocraftlab.skins.runtime.GalleryNativePassFixture(appearance,skin)) {
            for(var field:type.getDeclaredFields()) {
                Object value=switch(field.getName()) {
                    case "runtime" -> fixture.runtime;
                    case "scrollController" -> new ScrollTerminal();
                    case "activationCharacters" -> new com.naocraftlab.skins.runtime.ActivationCharacterGuard();
                    case "focusRequests" -> new com.naocraftlab.skins.runtime.FocusRequestLedger();
                    case "dispatchOrigin" -> com.naocraftlab.skins.runtime.InteractionOrigin.PROGRAMMATIC;
                    case "nativeDispatchDepth" -> 1;
                    case "capabilities" -> new Capabilities() {
                        public PreviewRenderer<Graphics> createEditorPreviewRenderer(){throw new AssertionError("focus only");}
                        public PreviewRenderer<Graphics> createSimplePreviewRenderer(){throw new AssertionError("focus only");}
                    };
                    default -> field.getType()==Map.class ? new HashMap<>() : field.getType()==List.class ? new ArrayList<>() : null;
                };
                if(value!=null)field.set(screen,value);
            }
            var view=fixture.runtime.view(854,480,0,0);
            String field=owner.contains("submission") ? "widgets" : "nativeWidgets";
            @SuppressWarnings("unchecked") var widgets=(Map<String,WidgetTerminal>)type.getField(field).get(screen);
            for(var spec:view.widgets()) widgets.put(spec.id(),navigationWidget(spec));
            var card=view.widgets().stream().filter(w->w.id().startsWith("gallery.card.") && w.value().filter("active"::equals).isPresent()).findFirst().orElseThrow();
            int[] activations={0};
            if(owner.contains("submission")) widgets.put(card.id(),focusButton(type,screen,card,ignored->{activations[0]++;fixture.runtime.dispatchWidget(card.id());}));
            var widget=widgets.get(card.id());
            host.setFocused(widget);
            ClientTerminal.instance.lastInputType=net.minecraft.client.InputType.KEYBOARD_TAB;
            if(type.getFields().length>0) type.getField("keyboardNavigation").setBoolean(screen,true);
            var keyboard=new Graphics();
            invoke(type,screen,"renderStateFrames",new Class<?>[]{Graphics.class,ViewSpec.class},keyboard,view);
            check(keyboard.fills.stream().anyMatch(f->f[4]==0xFFFFFFFF),"Keyboard focused card has white decoration before pointer");
            ClientTerminal.instance.lastInputType=net.minecraft.client.InputType.MOUSE;
            input(type,screen,owner,"press",card.bounds().x()+4,card.bounds().y()+4,0,0,0);
            check(host.getFocused()==widget && widget.isFocused(),"Pointer host retains logical focus on identical native widget");
            if(owner.contains("submission")) check(activations[0]==1,"Compiled host -> real native AbstractButton.onClick -> actual ActionButton -> runtime dispatch exactly once");
            var pointer=new Graphics();
            invoke(type,screen,"renderStateFrames",new Class<?>[]{Graphics.class,ViewSpec.class},pointer,view);
            check(pointer.fills.stream().anyMatch(f->f[4]==0xFF80C880),"Pointer retains active green on same view");
            check(pointer.fills.stream().noneMatch(f->f[4]==0xFFFFFFFF),"Pointer-focused off-hover card must not paint keyboard white frame: "+owner);
            ClientTerminal.instance.lastInputType=net.minecraft.client.InputType.KEYBOARD_TAB;
            for(String operation:List.of("wheel","drag")) {
                input(type,screen,owner,operation,-100,-100,0,1,1);
                var unchanged=new Graphics();
                invoke(type,screen,"renderStateFrames",new Class<?>[]{Graphics.class,ViewSpec.class},unchanged,view);
                check(unchanged.fills.stream().anyMatch(f->f[4]==0xFFFFFFFF),"Native wheel/drag without button input preserve keyboard modality");
            }
            ClientTerminal.instance.lastInputType=net.minecraft.client.InputType.MOUSE;
            input(type,screen,owner,"release",-100,-100,0,0,0);
            if(owner.contains("submission")) invokeResult(type,screen,"keyPressed",new Class<?>[]{KeyEvent.class},new KeyEvent(65,false));
            var released=new Graphics();
            invoke(type,screen,"renderStateFrames",new Class<?>[]{Graphics.class,ViewSpec.class},released,view);
            check(released.fills.stream().noneMatch(f->f[4]==0xFFFFFFFF) && widget.isFocused(),"Release/pointer plus non-navigation key cannot resurrect keyboard frame or clear logical focus");
            siblingInnerFocusConsumers(type,screen,owner);
            if(owner.contains("submission")) {
                fixture.offlineCapeCapability(true);
                fixture.runtime.view(854,480,0,0);
                fixture.drainWorkers();
                for(String branch:List.of(".more","gallery.inspect.")) {
                    var current=fixture.runtime.view(854,480,0,0);
                    var target=current.widgets().stream().filter(w->branch.equals(".more")?w.id().endsWith(branch):w.id().startsWith(branch)).filter(w->w.enabled()&&w.visible()).findFirst().orElseThrow();
                    var selection=fixture.runtime.snapshot().selectedPresetId();
                    int[] count={0};
                    widgets.clear();
                    var same=focusButton(type,screen,target,ignored->{count[0]++;fixture.runtime.dispatchWidget(target.id());});
                    widgets.put(target.id(),same);host.setFocused(same);type.getField("view").set(screen,current);
                    ClientTerminal.instance.lastInputType=net.minecraft.client.InputType.MOUSE;
                    input(type,screen,owner,"press",target.bounds().x()+3,target.bounds().y()+3,0,0,0);
                    check(count[0]==1 && host.getFocused()==same && same.isFocused(),"Connected exact branch pointer action/logical identity once: "+branch);
                    check(fixture.runtime.snapshot().selectedPresetId().equals(selection),"Child pointer action preserves draft: "+branch);
                    if(branch.equals(".more")) {
                        var opened=fixture.runtime.view(854,480,0,0);
                        check(opened.widgets().stream().anyMatch(w->w.id().endsWith(".edit")),"Single pointer toggle opens action mode");
                    } else check(!fixture.runtime.view(854,480,0,0).equals(current),"Actual inspect producer changes view");
                    String paint=branch.equals(".more")?"renderSurfaceOverlays":"renderStateFrames";
                    type.getField("mouseX").setInt(screen,-100);type.getField("mouseY").setInt(screen,-100);
                    var negative=new Graphics();invoke(type,screen,paint,new Class<?>[]{Graphics.class,ViewSpec.class},negative,current);
                    check(negative.fills.stream().noneMatch(f->f[4]==0xFFFFFFFF),"Pointer off-hover exact branch has no keyboard frame: "+branch);
                    ClientTerminal.instance.lastInputType=net.minecraft.client.InputType.KEYBOARD_TAB;
                    var positive=new Graphics();invoke(type,screen,paint,new Class<?>[]{Graphics.class,ViewSpec.class},positive,current);
                    check(positive.fills.stream().anyMatch(f->f[4]==0xFFFFFFFF),"Keyboard exact branch retains frame: "+branch);
                    ClientTerminal.instance.lastInputType=net.minecraft.client.InputType.MOUSE;
                    type.getField("mouseX").setInt(screen,target.bounds().x()+3);type.getField("mouseY").setInt(screen,target.bounds().y()+3);
                    var hover=new Graphics();invoke(type,screen,paint,new Class<?>[]{Graphics.class,ViewSpec.class},hover,current);
                    if(!branch.equals(".more")) check(hover.fills.stream().anyMatch(f->f[4]==0x18FFFFFF),"Actual cape hover foreground preserved");
                    else check(hover.drawn.stream().anyMatch(s->s.contains("▲") || s.contains("▼")),"Transparent glyph preserved without invented hover frame: "+hover.drawn);
                    check(same.isFocused() && count[0]==1,"Painting preserves same logical focus and single action");
                    System.out.println("PASS connected pointer/logical/offhover + keyboard/hover branch "+branch);
                }
                fixture.runtime.dispatchWidget("gallery.preset."+fixture.runtime.snapshot().selectedPresetId().orElseThrow()+".more");
                focusNavigationConsumers(type,screen,fixture);
            }
        }
        System.out.println("PASS same-instance compiled pointer host -> logical focus -> off-hover frame; keyboard/active positives: "+owner+"; synthetic input/widget/graphics terminals, NOT game/GPU");
    }

    private static void focusNavigationConsumers(Class<?> type,Object screen,com.naocraftlab.skins.runtime.GalleryNativePassFixture fixture) throws Exception {
        var host=(ScreenTerminal)screen;
        var initial=fixture.runtime.view(host.width,host.height,0,0);
        check(initial.widgets().stream().anyMatch(w->w.kind()==ViewSpec.WidgetKind.TEXT_FIELD),"Actual Gallery presents search EditBox");
        check(initial.widgets().stream().noneMatch(w->w.kind()==ViewSpec.WidgetKind.TAB_BUTTON) && initial.tabGroups().isEmpty(),"Gallery presents no vertical/native tab controls");
        invoke(type,screen,"rebuildNativeWidgets",new Class<?>[]{ViewSpec.class,Map.class},initial,Map.of());
        type.getField("view").set(screen,initial);
        var cards=initial.navigationNodes().stream().filter(n->n.pattern()==ViewSpec.NavigationPattern.GRID && n.id().startsWith("gallery.card.")).toList();
        check(cards.size()==2,"Real arrow navigation has adjacent cards");
        check((Boolean)invokeResult(type,screen,"focusWidget",new Class<?>[]{String.class},cards.get(0).id()),"Initial logical native card focus");
        ClientTerminal.instance.lastInputType=net.minecraft.client.InputType.KEYBOARD_ARROW;
        check((Boolean)invokeResult(type,screen,"keyPressed",new Class<?>[]{KeyEvent.class},new KeyEvent(262,false)),"Actual native Right arrow admitted");
        var requested=fixture.runtime.view(host.width,host.height,0,0);
        var request=requested.focusRequest().orElseThrow();
        check(request.widgetId().equals(cards.get(1).id()),"Native arrow -> current runtime target, not fabricated request");
        applyNavigationFocus(type,screen,fixture);
        @SuppressWarnings("unchecked") var widgets=(Map<String,WidgetTerminal>)type.getField("widgets").get(screen);
        var focused=widgets.get(request.widgetId());
        check(host.getFocused()==focused && focused.isFocused(),"Current rebuilt native target focused");
        check(fixture.runtime.view(host.width,host.height,0,0).focusRequest().isEmpty(),"Current request ACK preserved");
        var paint=focused.getClass().getDeclaredMethod("renderContents",Graphics.class,int.class,int.class,float.class);paint.setAccessible(true);
        var keyboard=new Graphics();paint.invoke(focused,keyboard,-100,-100,0F);
        check(keyboard.fills.stream().anyMatch(f->f[4]==0xFFFFFFFF),"Actual arrow/current rebuild -> keyboard paint");
        var spec=requested.widget(request.widgetId()).orElseThrow();
        ClientTerminal.instance.lastInputType=net.minecraft.client.InputType.MOUSE;
        input(type,screen,type.getName(),"press",spec.bounds().x()+4,spec.bounds().y()+4,0,0,0);
        type.getField("nativeDispatchDepth").setInt(screen,0);
        check(!(Boolean)invokeResult(type,screen,"applyFocusRequest",new Class<?>[]{ViewSpec.class},requested),"Late already-ACKed runtime request rejected by real ledger");
        type.getField("nativeDispatchDepth").setInt(screen,1);
        var pointer=new Graphics();paint.invoke(focused,pointer,-100,-100,0F);
        check(host.getFocused()==focused && focused.isFocused() && pointer.fills.stream().noneMatch(f->f[4]==0xFFFFFFFF),"Pointer plus stale focus request preserves logical target without resurrecting keyboard frame");
        System.out.println("PASS actual native Right arrow -> runtime request -> rebuild/materialization -> focus ACK -> paint; pointer/stale request negative; native InputType admitted device terminal, NOT OS input");
    }

    private static void inputConsumers(byte[] skin) throws Exception {
        String owner = CpmPreviewScreenSlice.availableOwner();
        Class<?> type = CpmPreviewScreenSlice.loadInput(owner);
        Object screen = type.getConstructor().newInstance();
        var appearance = new PreviewRenderer.PreviewAppearance(new TextureRegistry.TextureHandle("fixture:input",64,64),
                SkinModel.CLASSIC,Optional.empty(),PreviewRenderer.CapeMode.OFF,com.naocraftlab.skins.client.OuterLayerVisibility.allVisible(),true);
        try (var fixture = new com.naocraftlab.skins.runtime.GalleryNativePassFixture(appearance,skin)) {
            for (var field : type.getDeclaredFields()) {
                Object value = switch (field.getName()) {
                    case "runtime" -> fixture.runtime;
                    case "scrollController" -> new ScrollTerminal();
                    case "capabilities" -> new Capabilities() {
                        public PreviewRenderer<Graphics> createEditorPreviewRenderer() { throw new AssertionError("input only"); }
                        public PreviewRenderer<Graphics> createSimplePreviewRenderer() { throw new AssertionError("input only"); }
                    };
                    case "activationCharacters" -> new com.naocraftlab.skins.runtime.ActivationCharacterGuard();
                    case "focusRequests" -> new com.naocraftlab.skins.runtime.FocusRequestLedger();
                    case "dispatchOrigin" -> com.naocraftlab.skins.runtime.InteractionOrigin.PROGRAMMATIC;
                    case "nativeDispatchDepth" -> 1;
                    default -> Map.class.isAssignableFrom(field.getType()) ? new HashMap<>()
                            : List.class.isAssignableFrom(field.getType()) ? new ArrayList<>() : null;
                };
                if (value != null) field.set(screen,value);
            }
            var nativeScreen = (ScreenTerminal)screen;
            nativeScreen.width=854; nativeScreen.height=480;
            if (owner.contains("submission")) navigationConsumers(type, screen, fixture);
            var before = fixture.presentedRequest(nativeScreen.width,nativeScreen.height);
            var bounds = fixture.preview().anchorBounds();
            double x=bounds.x()+bounds.width()/2.0, y=bounds.y()+bounds.height()/2.0;
            var noEffects = fixture.noEffectsCheckpoint();
            input(type,screen,owner,"press",x,y,0,0,0);
            input(type,screen,owner,"drag",x+10,y-10,0,10,-10);
            input(type,screen,owner,"release",x+10,y-10,0,0,0);
            var rotated=fixture.presentedRequest(nativeScreen.width,nativeScreen.height);
            check(Math.abs(rotated.yawDegrees()-before.yawDegrees()-8)<0.001,"Compiled primary press -> drag must change actual Gallery request yaw: "+owner);
            check(Math.abs(rotated.pitchDegrees()-before.pitchDegrees()-6)<0.001,"Compiled drag must change actual Gallery request pitch");
            input(type,screen,owner,"wheel",x,y,0,0,1);
            check(fixture.presentedRequest(nativeScreen.width,nativeScreen.height).scale()>rotated.scale(),"Compiled model wheel must zoom request instead of scrolling Gallery stage: "+owner);
            check(fixture.runtime.view(854,480,0,0).scrollSurface("gallery.stage").orElseThrow().offsetPixels()==0,"Model wheel must not scroll left content");
            var released=fixture.presentedRequest(nativeScreen.width,nativeScreen.height);
            input(type,screen,owner,"drag",x,y,0,10,10);
            check(fixture.presentedRequest(nativeScreen.width,nativeScreen.height).yawDegrees()==released.yawDegrees(),"Unowned drag must not rotate");
            input(type,screen,owner,"press",x,y,1,0,0);
            input(type,screen,owner,"drag",x,y,1,10,10);
            check(fixture.presentedRequest(nativeScreen.width,nativeScreen.height).yawDegrees()==released.yawDegrees(),"Nonprimary gesture must not rotate");
            var presented=fixture.runtime.view(854,480,0,0);
            var staticCard=presented.previews().stream().filter(p -> p.id().startsWith("gallery.preset.")).findFirst().orElseThrow().anchorBounds();
            var search=presented.widget("gallery.search").orElseThrow().bounds();
            for (double[] point : List.of(new double[]{10,10},new double[]{10,470},new double[]{400,100},new double[]{853,100},
                    new double[]{staticCard.x()+staticCard.width()/2.0,staticCard.y()+staticCard.height()/2.0},
                    new double[]{search.x()+search.width()/2.0,search.y()+search.height()/2.0})) {
                input(type,screen,owner,"press",point[0],point[1],0,0,0);
                input(type,screen,owner,"drag",point[0],point[1],0,10,10);
                input(type,screen,owner,"release",point[0],point[1],0,0,0);
                input(type,screen,owner,"wheel",point[0],point[1],0,0,1);
                check(fixture.presentedRequest(nativeScreen.width,nativeScreen.height).yawDegrees()==released.yawDegrees() && fixture.presentedRequest(nativeScreen.width,nativeScreen.height).scale()==released.scale(),"Chrome/static right preview cannot control main model");
            }
            input(type,screen,owner,"press",x,y,0,0,0);
            fixture.selectC();
            var selected=fixture.presentedRequest(nativeScreen.width,nativeScreen.height);
            input(type,screen,owner,"drag",x,y,0,10,10);
            input(type,screen,owner,"release",x,y,0,0,0);
            check(fixture.presentedRequest(nativeScreen.width,nativeScreen.height).yawDegrees()==selected.yawDegrees(),"Stale source gesture must retire");
            nativeScreen.width=240;nativeScreen.height=112;
            var small=fixture.runtime.view(240,112,0,0);
            var left=small.scrollSurface("gallery.stage").orElseThrow();
            var right=small.scrollSurface("gallery.cards").orElseThrow();
            check(left.maximumPixels()>0 && right.maximumPixels()>0,"Both native scroll surfaces must actually overflow");
            float scale=fixture.presentedRequest(nativeScreen.width,nativeScreen.height).scale();
            input(type,screen,owner,"wheel",left.viewport().right()-1,left.viewport().y()+2,0,0,-0.137);
            var shifted=fixture.runtime.view(240,112,0,0);
            double leftOffset=shifted.scrollSurface("gallery.stage").orElseThrow().offsetPixels();
            check(leftOffset>0 && leftOffset!=Math.rint(leftOffset),"Native nonmodel left wheel keeps fractional offset");
            check(fixture.presentedRequest(nativeScreen.width,nativeScreen.height).scale()==scale,"Nonmodel wheel must not zoom");
            check(shifted.scrollSurface("gallery.cards").orElseThrow().offsetPixels()==right.offsetPixels(),"Left wheel must not scroll right");
            input(type,screen,owner,"wheel",right.viewport().x()+4,right.viewport().y()+4,0,0,right.offsetPixels()>5 ? 0.137 : -0.137);
            var independent=fixture.runtime.view(240,112,0,0);
            double rightOffset=independent.scrollSurface("gallery.cards").orElseThrow().offsetPixels();
            check(rightOffset>0 && rightOffset!=right.offsetPixels() && rightOffset!=Math.rint(rightOffset),"Native right wheel keeps fractional independent offset");
            check(independent.scrollSurface("gallery.stage").orElseThrow().offsetPixels()==leftOffset,"Right wheel must not scroll left");
            for (String selectedSurface:List.of("gallery.stage","gallery.cards","gallery.stage")) {
                var frame=fixture.runtime.view(240,112,0,0);
                var surface=frame.scrollSurface(selectedSurface).orElseThrow();
                input(type,screen,owner,"wheel",surface.viewport().x()+4,surface.viewport().y()+4,0,0,-0.137);
                frame=fixture.runtime.view(240,112,0,0);
                var controller=(ScrollTerminal)type.getField("scrollController").get(screen);
                check(controller.surfaceId().equals(Optional.of(selectedSurface)),"Native wheel selects actual controller surface");
                invoke(type,screen,"nclskins$syncScroll",new Class<?>[]{ViewSpec.class},frame);
                check(controller.surfaceId().equals(Optional.of(selectedSurface)) && controller.offsetPixels()==frame.scrollSurface(selectedSurface).orElseThrow().offsetPixels(),
                        "Actual compiled frame binding retains selected surface/current offset across both surfaces");
            }
            for (String surfaceId : List.of("gallery.stage","gallery.cards")) {
                var dragView=fixture.runtime.view(240,112,0,0);
                var bar=com.naocraftlab.skins.runtime.ViewHostPolicy.scrollbar(dragView,surfaceId).orElseThrow();
                double dragX=bar.thumb().x()+bar.thumb().width()/2.0;
                double dragY=bar.thumb().y()+bar.thumb().height()/2.0;
                double previous=dragView.scrollSurface(surfaceId).orElseThrow().offsetPixels();
                input(type,screen,owner,"press",dragX,dragY,0,0,0);
                input(type,screen,owner,"drag",dragX,bar.track().bottom()-1,0,0,bar.track().bottom()-1-dragY);
                input(type,screen,owner,"release",dragX,bar.track().bottom()-1,0,0,0);
                check(fixture.runtime.view(240,112,0,0).scrollSurface(surfaceId).orElseThrow().offsetPixels()>previous,"Actual native thumb drag reaches "+surfaceId);
                check(fixture.presentedRequest(nativeScreen.width,nativeScreen.height).scale()==scale,"Scrollbar drag does not zoom model");
                scrollbarPaint(type,screen,owner,fixture.runtime.view(240,112,0,0));
            }
            fixture.runtime.nativeScrollPositionChanged("gallery.stage",left.maximumPixels());
            var clipped=fixture.runtime.view(240,112,0,0);
            var oldAnchor=small.previews().get(0).anchorBounds();
            double oldX=oldAnchor.x()+oldAnchor.width()/2.0,oldY=oldAnchor.y()+oldAnchor.height()/2.0;
            check(clipped.previews().get(0).anchorBounds().equals(oldAnchor),"Lower scroll must retain fixed preview fit");
            check(com.naocraftlab.skins.runtime.PointerRouting.interactivePreview(clipped,oldX,oldY),"Fixed preview retains its own input");
            var clippedRequest=fixture.presentedRequest(nativeScreen.width,nativeScreen.height);
            input(type,screen,owner,"press",oldX,oldY,0,0,0);
            input(type,screen,owner,"drag",oldX,oldY,0,10,10);
            input(type,screen,owner,"release",oldX,oldY,0,0,0);
            check(fixture.presentedRequest(nativeScreen.width,nativeScreen.height).yawDegrees()!=clippedRequest.yawDegrees(),"Lower scroll must not disable fixed preview gestures");
            nativeScreen.width=854;nativeScreen.height=480;
            var selectedId=fixture.runtime.snapshot().selectedPresetId().orElseThrow();
            fixture.runtime.dispatchWidget("gallery.preset."+selectedId+".more");
            fixture.runtime.dispatchWidget("gallery.preset."+selectedId+".edit");
            var editor=fixture.runtime.view(854,480,0,0).previews().get(0);
            var editorBefore=com.naocraftlab.skins.runtime.ViewHostPolicy.previewRequest(editor,Optional.empty(),Optional.of(appearance)).orElseThrow();
            var eb=editor.anchorBounds();double ex=eb.x()+eb.width()/2.0,ey=eb.y()+eb.height()/2.0;
            input(type,screen,owner,"press",ex,ey,0,0,0);
            input(type,screen,owner,"drag",ex+10,ey-10,0,10,-10);
            input(type,screen,owner,"release",ex,ey,0,0,0);
            input(type,screen,owner,"wheel",ex,ey,0,0,1);
            var editorAfter=com.naocraftlab.skins.runtime.ViewHostPolicy.previewRequest(fixture.runtime.view(854,480,0,0).previews().get(0),Optional.empty(),Optional.of(appearance)).orElseThrow();
            check(editorAfter.yawDegrees()!=editorBefore.yawDegrees() && editorAfter.pitchDegrees()!=editorBefore.pitchDegrees()
                    && editorAfter.scale()>editorBefore.scale(),"Editor native input positive control must remain live");
            noEffects.run();
        }
        System.out.println("PASS compiled native press/drag/release/wheel -> real runtime -> Gallery PreviewRequest: "+owner+"; terminal widgets/scroll/GPU, NOT game acceptance");
    }

    private static Object input(Class<?> type,Object screen,String owner,String operation,double x,double y,int button,double dx,double dy) throws Exception {
        boolean immediate=owner.endsWith("NclSkinsImmediateScreen");
        if (operation.equals("wheel")) return invokeResult(type,screen,immediate ? "forwardScroll" : "mouseScrolled",
                new Class<?>[]{double.class,double.class,double.class,double.class},x,y,dx,dy);
        String name=operation.equals("press") ? "mouseClicked" : operation.equals("drag") ? "mouseDragged" : "mouseReleased";
        if (immediate) return operation.equals("drag")
                ? invokeResult(type,screen,name,new Class<?>[]{double.class,double.class,int.class,double.class,double.class},x,y,button,dx,dy)
                : invokeResult(type,screen,name,new Class<?>[]{double.class,double.class,int.class},x,y,button);
        if (owner.contains("extraction")) {
            try (var resource = CpmOwnedPreviewDispatchProbe.class.getClassLoader().getResourceAsStream(
                    "com/mojang/blaze3d/platform/InputConstants.class")) {
                if (resource == null) throw new AssertionError("Selected input SDK absent");
                var constants = new org.objectweb.asm.tree.ClassNode();
                new org.objectweb.asm.ClassReader(resource).accept(constants, org.objectweb.asm.ClassReader.SKIP_CODE);
                String field = button == 0 ? "MOUSE_BUTTON_LEFT" : "MOUSE_BUTTON_RIGHT";
                button = constants.fields.stream().filter(value -> value.name.equals(field))
                        .map(value -> (Integer) value.value).findFirst().orElse(button);
            }
        }
        var event=new MouseEvent(x,y,button);
        return operation.equals("press") ? invokeResult(type,screen,name,new Class<?>[]{MouseEvent.class,boolean.class},event,false)
                : operation.equals("drag") ? invokeResult(type,screen,name,new Class<?>[]{MouseEvent.class,double.class,double.class},event,dx,dy)
                : invokeResult(type,screen,name,new Class<?>[]{MouseEvent.class},event);
    }

    private static Object invokeResult(Class<?> type,Object target,String name,Class<?>[] parameters,Object... values) throws Exception {
        var method=type.getDeclaredMethod(name,parameters);method.setAccessible(true);
        try { return method.invoke(target,values); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception exception) throw exception;
            if (failure.getCause() instanceof Error error) throw error;
            throw failure;
        }
    }

    public interface InputListener {
        default boolean hasShiftDown() { return false; }
    }

    public record MouseEvent(double x, double y, int button) implements InputListener {}

    public record KeyEvent(int key, boolean hasShiftDown) implements InputListener {
        public boolean isEscape() { return key == 256; }
    }

    public static WidgetTerminal navigationFocusWidget(Object screen,ViewSpec.Widget spec) throws Exception {
        if(spec.kind()==ViewSpec.WidgetKind.TEXT_FIELD) return navigationWidget(spec);
        var runtime=(ClientRuntime)screen.getClass().getField("runtime").get(screen);
        return focusButton(screen.getClass(),screen,spec,ignored->runtime.dispatchWidget(spec.id()));
    }

    public static WidgetTerminal navigationWidget(ViewSpec.Widget spec) {
        WidgetTerminal widget = spec.kind() == ViewSpec.WidgetKind.TEXT_FIELD ? new FieldTerminal() : new WidgetTerminal();
        widget.x=spec.bounds().x(); widget.y=spec.bounds().y(); widget.width=spec.bounds().width(); widget.height=spec.bounds().height();
        widget.active=spec.enabled(); widget.visible=spec.visible();
        return widget;
    }

    private static void navigationConsumers(Class<?> type,Object screen,com.naocraftlab.skins.runtime.GalleryNativePassFixture fixture) throws Exception {
        var host=(ScreenTerminal)screen;
        host.width=240; host.height=112;
        var initial=fixture.runtime.view(host.width,host.height,0,0);
        type.getField("view").set(screen,initial);
        invoke(type,screen,"rebuildNativeWidgets",new Class<?>[]{ViewSpec.class,Map.class},initial,Map.of());
        var selection=fixture.runtime.snapshot().selectedPresetId();
        var nodes=initial.navigationNodes().stream().filter(n -> n.enabled() && n.pattern()==ViewSpec.NavigationPattern.GRID).toList();
        check(nodes.size()>=2 && initial.scrollSurface("gallery.cards").orElseThrow().maximumPixels()>0,"Navigation fixture has actual offscreen cards");
        String previous=initial.navigationNodes().stream().filter(n -> n.enabled() && n.tabOrder()<nodes.get(0).tabOrder())
                .max(java.util.Comparator.comparingInt(ViewSpec.NavigationNode::tabOrder)).orElseThrow().id();
        @SuppressWarnings("unchecked") var widgets=(Map<String,WidgetTerminal>)type.getField("widgets").get(screen);
        host.setFocused(widgets.get(previous));
        for (var target : nodes) {
            var current=fixture.runtime.view(host.width,host.height,0,0);
            var ordered=current.navigationNodes().stream().filter(ViewSpec.NavigationNode::enabled)
                    .sorted(java.util.Comparator.comparingInt(ViewSpec.NavigationNode::tabOrder)).toList();
            int guard=0;
            while (!target.id().equals(invokeResult(type,screen,"focusedWidgetId",new Class<?>[0]))) {
                check(++guard<=ordered.size()+1,"Native Tab reaches each logical card without an entry override");
                check((Boolean)invokeResult(type,screen,"keyPressed",new Class<?>[]{KeyEvent.class},new KeyEvent(258,false)),"Native Tab is consumed");
                var next=fixture.runtime.view(host.width,host.height,0,0);
                var request=next.focusRequest().orElseThrow();
                check(widgets.get(request.widgetId())==null || !widgets.get(request.widgetId()).isFocused(),"Pending request is not already forced focus");
                invoke(type,screen,"rebuildNativeWidgets",new Class<?>[]{ViewSpec.class,Map.class},next,Map.of());
                type.getField("view").set(screen,next);
                type.getField("nativeDispatchDepth").setInt(screen,0);
                invoke(type,screen,"applyFocusRequest",new Class<?>[]{ViewSpec.class},next);
                type.getField("nativeDispatchDepth").setInt(screen,1);
                check(request.widgetId().equals(invokeResult(type,screen,"focusedWidgetId",new Class<?>[0])),"Actual host focus consumer applies materialized requested target");
                check(fixture.runtime.view(host.width,host.height,0,0).focusRequest().isEmpty(),"Runtime acknowledges native focus");
            }
            var revealed=fixture.runtime.view(host.width,host.height,0,0);
            var node=revealed.navigationNodes().stream().filter(n -> n.id().equals(target.id())).findFirst().orElseThrow();
            var viewport=revealed.scrollSurface("gallery.cards").orElseThrow().viewport();
            check(node.bounds().bottom()>viewport.y() && node.bounds().y()<viewport.bottom(),"Offscreen logical card is revealed before native focus");
            check(fixture.runtime.snapshot().selectedPresetId().equals(selection),"Tab focus is not draft selection");
        }
        var last=fixture.runtime.view(host.width,host.height,0,0);
        String lastCard=(String)invokeResult(type,screen,"focusedWidgetId",new Class<?>[0]);
        check((Boolean)invokeResult(type,screen,"keyPressed",new Class<?>[]{KeyEvent.class},new KeyEvent(258,true)),"Shift Tab admitted");
        applyNavigationFocus(type,screen,fixture);
        String reverse=(String)invokeResult(type,screen,"focusedWidgetId",new Class<?>[0]);
        check(reverse.endsWith(".more") && !reverse.equals(lastCard),"ShiftTab crosses to preceding card own child, not selected anchor");
        check((Boolean)invokeResult(type,screen,"keyPressed",new Class<?>[]{KeyEvent.class},new KeyEvent(257,false)),"Enter admitted exactly once");
        var expanded=fixture.runtime.view(host.width,host.height,0,0);
        check(expanded.widgets().stream().anyMatch(w -> w.id().endsWith(".edit")),"One Enter opens own action mode; double handler would close it");
        invoke(type,screen,"rebuildNativeWidgets",new Class<?>[]{ViewSpec.class,Map.class},expanded,Map.of());
        type.getField("view").set(screen,expanded);
        var pending=expanded.focusRequest();
        if(pending.isPresent()) applyNavigationFocus(type,screen,fixture);
        var focused=(String)invokeResult(type,screen,"focusedWidgetId",new Class<?>[0]);
        check((Boolean)invokeResult(type,screen,"focusWidget",new Class<?>[]{String.class},reverse),"Visible own mode control remains a real native target");
        check((Boolean)invokeResult(type,screen,"keyPressed",new Class<?>[]{KeyEvent.class},new KeyEvent(32,false)),"Space admitted exactly once");
        check(fixture.runtime.view(host.width,host.height,0,0).widgets().stream().noneMatch(w -> w.id().endsWith(".edit")),"One Space closes mode without bubbling");
        fixture.runtime.dispatchText("gallery.search","no-footer-navigation-match");
        var hidden=fixture.runtime.view(host.width,host.height,0,0);
        check(hidden.navigationNodes().stream().noneMatch(n -> n.id().startsWith("gallery.card.")),"Search removes hidden cards from navigation");
        fixture.runtime.dispatchText("gallery.search","");
        fixture.selectB();
        System.out.println("PASS compiled native Tab/ShiftTab/Enter -> real runtime focus/reveal -> compiled widget materialization/focus/ack; hidden search exclusion; synthetic widget allocation, not game");
        host.width=854; host.height=480; host.setFocused(null);
    }

    private static void applyNavigationFocus(Class<?> type,Object screen,com.naocraftlab.skins.runtime.GalleryNativePassFixture fixture) throws Exception {
        var host=(ScreenTerminal)screen;
        var next=fixture.runtime.view(host.width,host.height,0,0);
        invoke(type,screen,"rebuildNativeWidgets",new Class<?>[]{ViewSpec.class,Map.class},next,Map.of());
        type.getField("view").set(screen,next);
        type.getField("nativeDispatchDepth").setInt(screen,0);
        invoke(type,screen,"applyFocusRequest",new Class<?>[]{ViewSpec.class},next);
        type.getField("nativeDispatchDepth").setInt(screen,1);
    }

    private static void scrollbarPaint(Class<?> type,Object screen,String owner,ViewSpec view) throws Exception {
        var graphics=new Graphics();
        if (owner.endsWith("NclSkinsImmediateScreen")) {
            for (var bar : com.naocraftlab.skins.runtime.ViewHostPolicy.scrollbars(view))
                invoke(type,screen,"renderScrollbar",new Class<?>[]{Graphics.class,ViewSpec.Scrollbar.class},graphics,bar);
        } else {
            for (var field:type.getDeclaredFields()) if (field.getType()==Text.class && field.getName().contains("SCROLLER")) field.set(null,Text.literal(field.getName()));
            invoke(type,screen,owner.contains("submission") ? "renderScrollbar" : "drawScrollbar",
                    new Class<?>[]{Graphics.class,ViewSpec.class,int.class,int.class},graphics,view,-1,-1);
        }
        for (String id:List.of("gallery.stage","gallery.cards")) {
            var surface=view.scrollSurface(id).orElseThrow();
            var bar=com.naocraftlab.skins.runtime.ViewHostPolicy.scrollbar(view,id).orElseThrow();
            for (var bounds:List.of(bar.track(),bar.thumb())) {
                check(graphics.fills.stream().anyMatch(f -> f[0]==bounds.x() && f[1]==bounds.y() && f[2]==bounds.right() && f[3]==bounds.bottom())
                        || graphics.spriteBounds.stream().anyMatch(f -> Arrays.equals(f,new int[]{bounds.x(),bounds.y(),bounds.width(),bounds.height()})),"Actual native scrollbar paint bounds: "+id);
            }
            check(bar.track().width()==6 && bar.thumb().y()>=bar.track().y() && bar.thumb().bottom()<=bar.track().bottom(),"Paint is clipped to actual scrollbar track");
            if (id.equals("gallery.cards")) check(bar.track().right()==view.width(),"Right painted scrollbar flush screen edge");
            else check(bar.track().y()==36+(int)Math.round(view.previews().get(0).anchorBounds().height()*1.1) && bar.track().bottom()==view.height()-33,"Lower painted scrollbar exact preview/footer boundaries");
            check(Math.abs(bar.offset()-surface.offsetPixels())<=1,"Paint uses current surface offset");
        }
    }

    public interface ScrollPort {
        default void renderFrame(CpmPreviewScopeProbe.Graphics graphics, int x, int y, float tick) {}
        void synchronize(Optional<ViewSpec.ScrollSurface> surface);
        boolean mouseScrolled(double x, double y, double horizontal, double vertical);
        Optional<String> surfaceId();
        double offsetPixels();
        void acceptedRuntimeOffset(double offset);
    }

    public static final class ScrollTerminal implements ScrollPort {
        private Optional<ViewSpec.ScrollSurface> surface = Optional.empty();
        private double offset;
        public void synchronize(Optional<ViewSpec.ScrollSurface> next) { surface = next; offset = next.map(ViewSpec.ScrollSurface::offsetPixels).orElse(0.0); }
        public boolean forwardScroll(double x, double y, double horizontal, double vertical) { return mouseScrolled(x,y,horizontal,vertical); }
        public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
            if (surface.isEmpty()) return false;
            var value = surface.orElseThrow();
            double amount = Math.abs(horizontal)>Math.abs(vertical) ? horizontal : vertical;
            offset = Math.max(0,Math.min(value.maximumPixels(),offset-amount*value.wheelStepPixels()));
            return amount != 0;
        }
        public Optional<String> surfaceId() { return surface.map(ViewSpec.ScrollSurface::id); }
        public double offsetPixels() { return offset; }
        public void acceptedRuntimeOffset(double value) { offset = value; }
        public void render(CpmBakedDisplayProbe.Graphics graphics,int mouseX,int mouseY,float tick) {}
    }

    public interface RenderFrame {
        void render(CpmBakedDisplayProbe.Graphics graphics,int mouseX,int mouseY,float tick);
        default void render(Graphics graphics,int mouseX,int mouseY,float tick) { throw new AssertionError("Unbound root button paint"); }
        default void extractRenderState(Graphics graphics,int mouseX,int mouseY,float tick) { render(graphics,mouseX,mouseY,tick); }
    }

    public static class WidgetTerminal implements InputListener, RenderFrame {
        public boolean isHovered;
        public float alpha=1F;
        public WidgetTerminal(int x,int y,int width,int height,TextValue message) {this.x=x;this.y=y;this.width=width;this.height=height;this.message=message;}
        public int getX(){return x;} public int getY(){return y;} public int getWidth(){return width;} public int getHeight(){return height;}
        public TextValue getMessage(){return message;}
        public boolean isHoveredOrFocused(){return isHovered || isFocused();}
        public int getFGColor(){return -1;}
        public void renderScrollingStringOverContents(ActiveTextTerminal collector,TextValue text,int margin) {collector.graphics.drawn.add(text.getString());}
        public void onClick(MouseEvent event,boolean doubleClick) {}
        public void render(Graphics graphics,int mouseX,int mouseY,float tick) {
            boolean hovered=mouseX>=x && mouseX<x+width && mouseY>=y && mouseY<y+height;
            graphics.fill(x,y,x+width,y+height,hovered ? 0xFFEEEEEE : 0xFF444444);
        }
        public void extractRenderState(Graphics graphics,int mouseX,int mouseY,float tick) { render(graphics,mouseX,mouseY,tick); }
        public int x, y, width, height;
        public TextValue message;
        public void setX(int value) { x = value; }
        public void setY(int value) { y = value; }
        public void setWidth(int value) { width = value; }
        public void setHeight(int value) { height=value; }
        public void update(ViewSpec.Widget spec) {}
        public boolean isActive() { return active; }
        public void render(CpmBakedDisplayProbe.Graphics graphics,int mouseX,int mouseY,float tick) { CpmBakedDisplayProbe.rootPaint(this); }
        public void setMessage(TextValue value) { message = value; }
        public void setTooltip(CpmPreviewScopeProbe.FrameTooltip value) {}
        public void render(CpmPreviewScopeProbe.Graphics graphics, int mouseX, int mouseY, float tick) { CpmPreviewScopeProbe.framePaint(this); }
        private boolean focused;
        public boolean active = true, visible = true;
        public WidgetTerminal() {}
        public WidgetTerminal(boolean focused) {this.focused=focused;}
        public boolean isFocused() {return focused;}
        public void setFocused(boolean value) { focused = value; }
        public boolean mouseClicked(MouseEvent event, boolean doubleClick) { if (!active || !visible) return false;onClick(event,doubleClick);return true; }
    }

    public static final class FieldTerminal extends WidgetTerminal {
        public void setEditable(boolean value) {}
        public void setValue(String value) {}
        public String getValue() { return ""; }
        public void setHighlightPos(int position) {}
        public void setCursorPosition(int position) {}
        public void moveCursorToEnd(boolean select) {}
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
        public static Text parse(String value) { return new Text(value); }
        public static Text tryParse(String value) { return new Text(value); }
        public static Text withDefaultNamespace(String value) { return new Text(value); }
        public static Text fromNamespaceAndPath(String namespace,String path) { return new Text(namespace+":"+path); }
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
        public int advance = 6;
        public List<TextValue> split(TextValue text, int width) {
            var lines = new ArrayList<TextValue>();
            int chars = Math.max(1, width / 6);
            String value = text.getString();
            for (int start = 0; start < value.length(); start += chars) lines.add(Text.literal(value.substring(start, Math.min(value.length(), start + chars))));
            return lines;
        }
        public int width(TextValue text) { return text.getString().length() * advance; }
        public String plainSubstrByWidth(String text, int width) { return text.substring(0, Math.min(text.length(), Math.max(0, width / advance))); }
    }

    public static class ScreenTerminal {
        public static final Text HEADER_SEPARATOR=Text.literal("header"), INWORLD_HEADER_SEPARATOR=Text.literal("inworld_header"), FOOTER_SEPARATOR=Text.literal("footer"), INWORLD_FOOTER_SEPARATOR=Text.literal("inworld_footer");
        public FontTerminal font;
        public ClientTerminal minecraft;
        public int width = 180, height = 120;
        public int rebuilds, widgetAssemblies;
        public List<ViewSpec.Widget> constructedWidgets = List.of();
        protected void init() {}
        protected void clearWidgets() {}
        public WidgetTerminal addRenderableWidget(WidgetTerminal widget) { return widget; }
        public InputListener addRenderableWidget(InputListener widget) { return widget; }
        protected void clearFocus() { setFocused(null); }
        protected void setInitialFocus() {}
        protected void setInitialFocus(WidgetTerminal widget) { setFocused(widget); }
        protected void setInitialFocus(InputListener widget) { setFocused(widget); }
        public void rebuildWidgets() { rebuilds++; clearWidgets(); clearFocus(); init(); setInitialFocus(); }
        public void constructWidgets(ViewSpec view) { widgetAssemblies++; constructedWidgets = view.widgets(); }
        public void triggerImmediateNarration(boolean value) {}
        public TextValue getNarrationMessage() { return Text.literal("Screen"); }
        private InputListener focused;
        public InputListener getFocused() { return focused; }
        public void setFocused(InputListener listener) {
            if (focused instanceof WidgetTerminal old) old.setFocused(false);
            focused=listener;
            if (focused instanceof WidgetTerminal next) next.setFocused(true);
        }
        public static boolean hasShiftDown() { return false; }
        public boolean mouseClicked(double x, double y, int button) { return false; }
        public boolean mouseClicked(MouseEvent event, boolean doubleClick) { return false; }
        public boolean mouseDragged(double x, double y, int button, double dx, double dy) { return false; }
        public boolean mouseDragged(MouseEvent event, double dx, double dy) { return false; }
        public boolean mouseReleased(double x, double y, int button) { return false; }
        public boolean mouseReleased(MouseEvent event) { return false; }
        public boolean mouseScrolled(double x, double y, double horizontal, double vertical) { return false; }
        public boolean keyPressed(KeyEvent event) { return false; }
    }

    public static final class ClientTerminal {
        public FontTerminal font=new FontTerminal();
        public net.minecraft.client.InputType lastInputType=net.minecraft.client.InputType.NONE;
        public net.minecraft.client.InputType getLastInputType(){return lastInputType;}
        public void execute(Runnable action) { action.run(); }
        public void setScreen(ScreenTerminal value) { screen = value; }
        public static final ClientTerminal instance=new ClientTerminal();
        public static ClientTerminal getInstance() { return instance; }
        public Object level;
        public ScreenTerminal screen;
    }
    public static final class PipelinesTerminal { public static final Object GUI_TEXTURED=new Object(); }
    public static final class GuiTerminal {
        public static ScreenTerminal currentScreen(ClientTerminal client) { return client.screen; }
        public static void setScreen(ClientTerminal client, ScreenTerminal screen) { client.screen = screen; }
    }
    public static final class TextComponents {
        public static String resolveString(UiMessage message) { return message.key() + message.arguments(); }
        public static TextValue resolve(UiMessage message) { return Text.literal(resolveString(message)); }
    }

    public record ActiveTextTerminal(Graphics graphics) {}
    public enum HoveredTextEffects { NONE }

    public static final class Graphics {
        public void setColor(float red,float green,float blue,float alpha) {}
        public void blit(Text texture,int x,int y,float u,float v,int width,int height,int tw,int th) {blits.add(texture.getString());}
        public void blit(Object pipeline,Text texture,int x,int y,float u,float v,int width,int height,int tw,int th,int color) {blits.add(texture.getString());}
        public ActiveTextTerminal textRendererForWidget(WidgetTerminal widget,HoveredTextEffects effects){return new ActiveTextTerminal(this);}
        public void blitSprite(Object pipeline,Text texture,int x,int y,int width,int height,int color) {blits.add(texture.getString());spriteBounds.add(new int[]{x,y,width,height});}
        final List<List<TextValue>> tooltips=new ArrayList<>();
        public void renderTooltip(FontTerminal font,List<TextValue> lines,int x,int y) { tooltips.add(lines); }
        public void setTooltipForNextFrame(FontTerminal font,List<TextValue> lines,int x,int y) { tooltips.add(lines); }
        public void setTooltipForNextFrame(List<TextValue> lines,int x,int y) { tooltips.add(lines); }
        final List<String> drawn = new ArrayList<>();
        final List<String> blits = new ArrayList<>();
        final List<int[]> spriteBounds = new ArrayList<>();
        public void blitSprite(Object pipeline,Text texture,int x,int y,int width,int height) { spriteBounds.add(new int[]{x,y,width,height}); }
        final List<int[]> capeCrops = new ArrayList<>();
        final List<com.naocraftlab.skins.client.BackEquipmentPreviewRenderer.Request> equipmentRequests = new ArrayList<>();
        public void blit(Text texture,int x,int y,int width,int height,float u,float v,int sw,int sh,int tw,int th) { capeCrops.add(new int[]{x,y,width,height,(int)u,(int)v,sw,sh,tw,th}); }
        public void blit(Object pipeline,Text texture,int x,int y,float u,float v,int width,int height,int sw,int sh,int tw,int th,int color) { capeCrops.add(new int[]{x,y,width,height,(int)u,(int)v,sw,sh,tw,th}); }
        public void blit(Object pipeline,Text texture,int x,int y,float u,float v,int width,int height,int tw,int th) {blits.add(texture.getString()+":"+x+","+y+","+width+","+height);}
        final List<int[]> fills = new ArrayList<>();
        final List<Integer> paintStrata = new ArrayList<>();
        int stratum;
        public void nextStratum() { stratum++; }
        final List<PreviewRenderer.PreviewIntent> previewIntents = new ArrayList<>();
        final List<int[]> fillClips=new ArrayList<>();
        private final java.util.ArrayDeque<int[]> activeClips=new java.util.ArrayDeque<>();
        public void fill(int left,int top,int right,int bottom,int color) {fills.add(new int[]{left,top,right,bottom,color});paintStrata.add(stratum);fillClips.add(activeClips.peek());}
        public void drawCenteredString(FontTerminal font,TextValue text,int x,int y,int color) {drawn.add(text.getString());textPoints.add(new int[]{x,y});}
        public void centeredText(FontTerminal font,TextValue text,int x,int y,int color) {drawn.add(text.getString());textPoints.add(new int[]{x,y});}
        final List<int[]> scissors=new ArrayList<>(), textPoints=new ArrayList<>();
        public void enableScissor(int left, int top, int right, int bottom) {int[] clip={left,top,right,bottom};scissors.add(clip);activeClips.push(clip);}
        public void disableScissor() {activeClips.pop();}
        public int drawStringResult(FontTerminal font, TextValue text, int x, int y, int color, boolean shadow) { drawn.add(text.getString()); textPoints.add(new int[]{x,y}); return 0; }
        public void drawString(FontTerminal font, TextValue text, int x, int y, int color) { drawn.add(text.getString()); textPoints.add(new int[]{x,y}); }
        public void text(FontTerminal font, TextValue text, int x, int y, int color) { drawn.add(text.getString()); textPoints.add(new int[]{x,y}); }
    }

    public interface Capabilities {
        default void finishPreviewPass(CpmPreviewScopeProbe.Graphics graphics) {}
        default void renderScrollbar(Graphics graphics,ViewSpec.Scrollbar scrollbar) { throw new AssertionError("Unexpected horizontal scrollbar"); }
        default void renderPanel(CpmPreviewScopeProbe.Graphics graphics, ViewSpec.Panel panel, int u, int v,
                Optional<com.naocraftlab.skins.runtime.Bounds> before, Optional<com.naocraftlab.skins.runtime.Bounds> after) {}
        PreviewRenderer<Graphics> createEditorPreviewRenderer();
        PreviewRenderer<Graphics> createSimplePreviewRenderer();
        default com.naocraftlab.skins.runtime.ViewChromeMetrics viewChromeMetrics() { return com.naocraftlab.skins.runtime.ViewChromeMetrics.STANDARD; }
    }

    public static final class RenderTerminal implements PreviewRenderer<Graphics>, com.naocraftlab.skins.client.BackEquipmentPreviewRenderer<Graphics>, AutoCloseable {
        public void render(CpmBakedDisplayProbe.Graphics graphics,PreviewRequest request) { requests.add(request); }
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
        public boolean prepareGalleryBody(String source, boolean expected, java.util.function.BooleanSupplier admission) {
            if (closed || !admission.getAsBoolean()) return false;
            prepareBody(source,expected);
            return !closed && admission.getAsBoolean();
        }
        public void renderGallery(Graphics graphics, String source, PreviewRequest request) { render(graphics,request); }
        public PreviewAccessories galleryAccessories(String source, PreviewRequest request, java.util.function.BooleanSupplier inputCurrent) { return inputCurrent.getAsBoolean() ? accessories(request) : PreviewAccessories.unavailable(LiveReason.LIVE_UNAVAILABLE); }
        public void render(Graphics graphics, PreviewRequest request) { requests.add(request); graphics.previewIntents.add(request.intent()); }
        public void render(Graphics graphics, com.naocraftlab.skins.client.BackEquipmentPreviewRenderer.Request request) { graphics.equipmentRequests.add(request); }
        public void close() { closed = true; }
    }

    public static final class Registry implements TextureRegistry {
        final Map<TextureHandle, byte[]> registered = new HashMap<>();
        public TextureHandle register(TextureKind kind, String hash, Path path) { throw new AssertionError("No path reread"); }
        public TextureHandle register(TextureKind kind, String hash, byte[] bytes) {
            check(kind == TextureKind.PLAYER_SKIN || kind == TextureKind.IMAGE, "Only owned body/cape texture registered");
            int width = 64, height = 64;
            if (kind == TextureKind.IMAGE) {
                try {
                    var image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
                    check(image != null, "Owned static cape is a decoded image");
                    width = image.getWidth(); height = image.getHeight();
                } catch (java.io.IOException failure) { throw new AssertionError(failure); }
            }
            var handle = new TextureHandle("fixture:selected-" + hash, width, height);
            registered.put(handle, bytes.clone());
            live.add(handle);
            return handle;
        }
        private int nextCape;
        private final Map<TextureHandle, CpuLease> rgbaLeases = new HashMap<>();
        private final java.util.Set<TextureHandle> live = new java.util.HashSet<>();
        public TextureHandle registerRgba(TextureKind kind, String hash, int width, int height, byte[] rgba, CpuLease lease) {
            var handle = new TextureHandle("fixture:cape-" + hash + "-" + ++nextCape,width,height);
            check(!rgbaLeases.containsKey(handle), "No duplicate owned cape registration");
            registered.put(handle,rgba.clone()); rgbaLeases.put(handle,lease); live.add(handle);
            return handle;
        }
        public com.naocraftlab.skins.client.TextureUseValidity capture(TextureHandle handle) {
            return () -> live.contains(handle);
        }
        public void release(TextureHandle handle) {
            live.remove(handle);
            var lease = rgbaLeases.remove(handle); if (lease != null) lease.close();
        }
        public void close() {
            new java.util.ArrayList<>(rgbaLeases.keySet()).forEach(this::release);
        }
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
        public Optional<ViewSpec.Preview> galleryPreviewCandidate() {
            return runtime == null ? Optional.empty() : runtime.galleryPreviewCandidate();
        }
        public CompletableFuture<Optional<byte[]>> loadCapePreview(ViewSpec.Preview preview) {
            return runtime.loadCapePreview(preview);
        }
        public CompletableFuture<Optional<byte[]>> loadCapePreview(String capeId) {
            return runtime.loadCapePreview(capeId);
        }
        public CompletableFuture<Optional<byte[]>> loadProviderTexture(ViewSpec.ProviderTexture texture) {
            return runtime.loadProviderTexture(texture);
        }
        public CompletableFuture<Optional<com.naocraftlab.skins.runtime.ProviderCapePoster>> loadProviderThumbnail(ViewSpec.ProviderTexture texture) {
            return runtime.loadProviderThumbnail(texture);
        }
        public void invalidateProviderThumbnail(ViewSpec.ProviderTexture texture) {
            runtime.invalidateProviderThumbnail(texture);
        }
        public CompletableFuture<Optional<com.naocraftlab.skins.runtime.CapePreviewSource>> openPreparedCapePreview(String capeId) {
            return runtime.openPreparedCapePreview(capeId);
        }
        public CompletableFuture<Optional<com.naocraftlab.skins.runtime.CapePreviewSource>> openPreparedCapePreview(String capeId, boolean thumbnail) {
            return runtime.openPreparedCapePreview(capeId, thumbnail);
        }
        public boolean previewCapeGlint(ViewSpec.Preview preview) { return runtime.previewCapeGlint(preview); }
        public String previewDecorationCapeSurfaceKey(ViewSpec.IconDecoration decoration) {
            return runtime.previewDecorationCapeSurfaceKey(decoration);
        }
        public void reportCapePreviewFailure(ViewSpec.Preview preview) { runtime.reportCapePreviewFailure(preview); }
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
        public java.util.function.BooleanSupplier galleryPreviewAdmission(ViewSpec.Preview preview) { return runtime.galleryPreviewAdmission(preview); }
        public java.util.function.BooleanSupplier galleryAccessoryAdmission(ViewSpec.Preview preview) { return runtime.galleryAccessoryAdmission(preview); }
        public boolean galleryPreviewFrameCurrent(ViewSpec.Preview preview) { return runtime.galleryPreviewFrameCurrent(preview); }
        public Consumer<PreviewRenderer.BodyStatus> observePreviewBody(ViewSpec.Preview preview, Supplier<PreviewRenderer.DisplayKind> source, Runnable accepted) {
            bodyRegistrations++; lastSource = source;
            return lastRegistration = runtime.observePreviewBody(preview, source, accepted);
        }
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

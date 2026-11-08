package com.naocraftlab.skins.buildlogic.probe;

import com.naocraftlab.skins.client.*;
import com.naocraftlab.skins.compat.cpm.*;
import com.naocraftlab.skins.runtime.*;
import com.tom.cpl.math.Vec3f;
import com.tom.cpm.shared.MinecraftClientAccess;
import com.tom.cpm.shared.definition.ModelDefinition;
import com.tom.cpm.shared.model.*;
import com.tom.cpm.shared.parts.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public final class CpmAccessoryContentProbe {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 2 ? args[2] : "hidden";
        boolean copy = mode.equals("copy");
        CpmBehaviorProbe.initializeHost(new File(args[0]));
        byte[] skin = Files.readAllBytes(Path.of(args[1], "classic-a.png"));
        var appearance = new PreviewRenderer.PreviewAppearance(new TextureRegistry.TextureHandle("fixture:content",64,64),
                SkinModel.CLASSIC, Optional.empty(), PreviewRenderer.CapeMode.OFF, true);
        try (var base = new GalleryNativePassFixture(appearance, skin); var gallery = base.freshOwnedCpm()) {
            var preview = gallery.runtime.galleryPreviewCandidate().orElseThrow();
            var source = CapeProjection.effectiveSkin(new UUID(0, 981), new UUID(0, 801),
                    "fixture:content-hidden", SkinModel.CLASSIC, !mode.equals("memo-world"));
            var player = CpmBehaviorProbe.fixturePlayer(source.cacheId());
            if (!mode.equals("memo-world")) ((CpmPlayerAccess) player).nclskins$source(source);
            var root = Cube.newFakeCube(); root.id = 10; root.hidden = true;
            var child = Cube.newFakeCube(); child.id = 11; child.parentId = 10;
            child.size = new Vec3f(2,3,4); child.hidden = true;
            if (mode.equals("visible") || mode.equals("cape-visible") || mode.equals("visible-anchor")) child.hidden = false;
            if (mode.equals("visible-anchor")) child.size = new Vec3f(0,0,0);
            if (mode.equals("default") || mode.equals("retexture")) root.hidden = false;
            root.scale = root.meshScale = new Vec3f(1,1,1);
            child.scale = child.meshScale = new Vec3f(1,1,1);
            var part = new ModelPartCubes(mode.equals("empty-root") ? List.of(root) : List.of(root, child));
            var definition = new ModelDefinition(MinecraftClientAccess.get().getDefinitionLoader(), player);
            var encoded = new com.tom.cpm.shared.io.IOHelper();
            part.write(encoded);
            var parts = new ArrayList<IModelPart>();
            parts.add(new ModelPartCubes(encoded.flip(), definition));
            if (!mode.equals("body-only") && !mode.equals("rootless-sheet") && !mode.equals("cape-rootless-sheet")) parts.add(new ModelPartRoot(10,
                    mode.startsWith("cape-") ? RootModelType.CAPE : mode.equals("left-sheet") ? RootModelType.ELYTRA_RIGHT : RootModelType.ELYTRA_LEFT));
            if (mode.equals("unused-sheet")) {
                var right = Cube.newFakeCube(); right.id = 12; right.hidden = true;
                right.scale = right.meshScale = new Vec3f(1,1,1);
                parts.add(new ModelPartCubes(List.of(right)));
                parts.add(new ModelPartRoot(12, RootModelType.ELYTRA_RIGHT));
            }
            if (mode.equals("retexture") || mode.endsWith("sheet")) {
                var sheet = new com.tom.cpm.shared.io.IOHelper(); sheet.writeEnum(mode.startsWith("cape-") ? TextureSheetType.CAPE : TextureSheetType.ELYTRA);
                var image = new com.tom.cpl.util.Image(64,32); image.fill(0xff336699);
                new com.tom.cpm.shared.skin.TextureProvider(image, new com.tom.cpl.math.Vec2i(64,32)).write(sheet);
                parts.add(new ModelPartTexture(sheet.flip(), definition));
            }
            if (mode.equals("pack")) {
                var collection = new ModelPartCollection(); collection.addAll(parts);
                var wire = new com.tom.cpm.shared.io.IOHelper(); collection.writePackage(wire);
                var link = new com.tom.cpm.shared.io.IOHelper();
                new com.tom.cpm.shared.definition.Link("synthetic", "content").write(link);
                var pack = new SyntheticPackageReader(link.flip(), definition).read(wire.flip(), definition);
                check(pack.getClass().getName().equals("com.tom.cpm.shared.parts.ModelPartCollection$Pack") && pack.getType() == null,
                        "actual SDK PackageLink reader creates opaque null-type Pack");
                parts.clear(); parts.add(pack);
            }
            if (mode.equals("unknown")) parts.add(new ModelPartCloneable("Synthetic", "", null));
            if (mode.equals("mapped-null") || mode.equals("animated") || mode.startsWith("legacy-")) {
                var editor = new com.tom.cpm.shared.editor.Editor();
                var ui = CpmImportFeasibilityProbe.class.getDeclaredMethod("host", Class.class);
                ui.setAccessible(true);
                editor.setUI((com.tom.cpl.gui.UI) ui.invoke(null, com.tom.cpl.gui.UI.class));
                var element = new com.tom.cpm.shared.editor.elements.ModelElement(editor);
                element.id = mode.equals("legacy-null") ? PlayerModelParts.BODY.ordinal() : 11; element.hidden = true; editor.elements.add(element);
                var animation = new com.tom.cpm.shared.editor.anim.EditorAnim(editor, "Synthetic", com.tom.cpm.shared.animation.AnimationType.GESTURE, false);
                animation.duration = 1000; animation.filename = "synthetic"; animation.displayName = "Synthetic";
                var frame = new com.tom.cpm.shared.editor.anim.AnimFrame(animation);
                frame.setPos(element, new Vec3f(1,0,0));
                if (mode.equals("animated") || mode.equals("legacy-animated")) frame.switchVis(element);
                animation.getFrames().add(frame); editor.animations.add(animation);
                var data = new com.tom.cpm.shared.io.IOHelper();
                if (mode.startsWith("legacy-")) {
                    animation.type = com.tom.cpm.shared.animation.AnimationType.POSE;
                    animation.pose = com.tom.cpm.shared.animation.VanillaPose.STANDING;
                    new ModelPartAnimation(editor, parts).write(data);
                    parts.add(new ModelPartAnimation(data.flip(), definition));
                } else {
                    new com.tom.cpm.shared.parts.anim.ModelPartAnimationNew(editor).write(data);
                    parts.add(new com.tom.cpm.shared.parts.anim.ModelPartAnimationNew(data.flip(), definition));
                }
            }
            definition.setParts(parts);
            try {
                definition.resolveAll();
                check(definition.doRender() && definition.getError() == null, "real SDK producer resolved");
                verifyDependencies(parts, definition, mode);
                if (mode.equals("null-part")) ((com.naocraftlab.skins.compat.cpm.mixin.CpmResolvedPartsAccessor) definition).nclskins$resolvedParts().add(null);
                if (mode.endsWith("sheet")) {
                    var sheetType = mode.startsWith("cape-") ? TextureSheetType.CAPE : TextureSheetType.ELYTRA;
                    check(definition.getTexture(sheetType, false) != null, "actual SDK retains own native sheet");
                    if (mode.equals("rootless-sheet") || mode.equals("cape-rootless-sheet"))
                        check(!definition.hasRoot(mode.startsWith("cape-") ? RootModelType.CAPE : RootModelType.ELYTRA_LEFT)
                                && !definition.hasRoot(RootModelType.ELYTRA_RIGHT), "native sheet has no authored root");
                    if (mode.equals("unused-sheet")) check(definition.hasRoot(RootModelType.ELYTRA_LEFT)
                            && definition.hasRoot(RootModelType.ELYTRA_RIGHT)
                            && definition.getModelElementFor(RootModelType.ELYTRA_RIGHT).get().isHidden(), "unused sheet has both permanently hidden native roots");
                }
                var roots = definition.getModelElementFor(RootModelType.ELYTRA_LEFT);
                if (roots != null && !roots.isEmpty()) {
                    var nativeRoot = roots.get();
                    check(nativeRoot.isHidden() == root.hidden, "authored root visibility preserved");
                    check(nativeRoot.children.size() == (mode.equals("empty-root") ? 0 : 1), "authored child topology preserved");
                    if (!nativeRoot.children.isEmpty()) check(nativeRoot.children.get(0).getCube().hidden == child.hidden, "authored child visibility preserved");
                    if (copy || mode.equals("copy-position")) definition.getAnimations().addCopy(new CopyTransform(
                            definition.getModelElementFor(PlayerModelParts.BODY).get(), nativeRoot.children.get(0), (short) (copy ? 512 : 1), 1));
                }
                player.setModelDefinition(CompletableFuture.completedFuture(definition), false);
                player.getModelDefinition();
                check(source.accessories().present() == !mode.equals("body-only")
                        && source.accessories().cape() == mode.startsWith("cape-")
                        && source.accessories().elytra() == (!mode.equals("body-only") && !mode.startsWith("cape-")), "broad native override remains unchanged");
                var observation = CapeProjection.previewAccessories(source.cacheId(), source.location(), source.model(), PreviewRenderer.LiveReason.AVAILABLE);
                var callback = gallery.runtime.observePreviewAccessories(preview, () -> observation);
                callback.accept(observation);
                var view = gallery.runtime.view(854,480,0,0);
                boolean card = view.widgets().stream().anyMatch(w -> w.id().equals("gallery.inspect.CPM"));
                boolean expected = List.of("copy", "animated", "legacy-animated", "visible", "visible-anchor", "cape-visible", "default", "retexture", "unknown",
                        "rootless-sheet", "cape-rootless-sheet", "left-sheet", "right-sheet", "pack", "null-part").contains(mode);
                check(card == expected, copy ? "copy visibility target must preserve Gallery CPM card"
                        : "SDK content classification must preserve conditional Gallery CPM card mode=" + mode);
                check(source.accessories().present() == !mode.equals("body-only"), "Gallery filtering cannot revoke native ownership");
                var current = gallery.runtime.galleryPreviewCandidate().orElseThrow();
                String body = gallery.runtime.previewBodySource(current);
                if (!expected) {
                    gallery.runtime.dispatchWidget("gallery.inspect.CPM");
                    check(gallery.runtime.previewBodySource(gallery.runtime.galleryPreviewCandidate().orElseThrow()).equals(body), "hidden stale CPM dispatch is inert");
                }
                long generation = source.accessories().generation();
                Object memo = field(player, "nclskins$content");
                check(memo != null, "ready definition produces memo");
                for (int i=0;i<4;i++) { player.getModelDefinition(); callback.accept(observation); gallery.runtime.view(854,480,0,0); }
                check(field(player, "nclskins$content") == memo, "same ready definition reuses exact memo record");
                check(source.accessories().generation() == generation, "repeated unchanged producer metadata reaches a fixed point");
                if (mode.startsWith("memo-")) {
                    check(((CpmPlayerAccess) player).nclskins$accessorySource() == source, "actual entrypoint bound source");
                    check((((CpmPlayerAccess) player).nclskins$source() == null) == mode.equals("memo-world"), "world binding preserves ordinary privileges");
                    var replacement = new ModelDefinition(MinecraftClientAccess.get().getDefinitionLoader(), player);
                    replacement.setParts(parts); replacement.resolveAll();
                    check(replacement.doRender(), "replacement is genuinely ready");
                    player.setModelDefinition(CompletableFuture.completedFuture(replacement), false);
                    player.getModelDefinition();
                    check(field(player, "nclskins$contentIdentity") == replacement && field(player, "nclskins$content") != memo,
                            "replacement definition recomputes memo");
                    long revision = (Long) field(player, "nclskins$modelRevision");
                    var registrationField = CapeProjection.class.getDeclaredField("CURRENT"); registrationField.setAccessible(true);
                    ((CapeProjection.Registration) ((java.util.concurrent.atomic.AtomicReference<?>) registrationField.get(null)).get()).close();
                    check(!source.active(), "actual registration close retired source without CPM refresh");
                    check(field(player, "nclskins$contentIdentity") == null && field(player, "nclskins$content") == null
                            && field(player, "nclskins$modelIdentity") == null, "retirement clears retained Player entire memo before next query mode=" + mode);
                    check((Long) field(player, "nclskins$modelRevision") == revision, "retirement does not rewind model revision");
                    check(mode.equals("memo-world") || ((CpmPlayerAccess) player).nclskins$source() == source, "retirement preserves owned privilege fence");
                    replacement.cleanup();
                }
                System.out.println("PASS SDK producer -> observation -> projection -> Gallery mode="+mode+" card="+card+" broad="+source.accessories().present());
            } finally { definition.cleanup(); CapeProjection.retireSkin(source.cacheId()); }
        }
        if (mode.startsWith("memo-")) lifecycleControls(mode.equals("memo-world"));
    }

    private static CapeProjection.Registration registration() {
        return CapeProjection.installEvents((CapeProjection.Events) java.lang.reflect.Proxy.newProxyInstance(
                CapeProjection.Events.class.getClassLoader(), new Class<?>[]{CapeProjection.Events.class},
                (proxy, method, args) -> method.getReturnType() == boolean.class ? true : null));
    }

    private static ModelDefinition ready(com.tom.cpm.shared.config.Player<?> player) throws Exception {
        var root = Cube.newFakeCube(); root.id = 10; root.hidden = true;
        root.scale = root.meshScale = new Vec3f(1,1,1);
        var definition = new ModelDefinition(MinecraftClientAccess.get().getDefinitionLoader(), player);
        definition.setParts(List.of(new ModelPartCubes(List.of(root)), new ModelPartRoot(10, RootModelType.ELYTRA_LEFT)));
        definition.resolveAll();
        check(definition.doRender() && definition.getError() == null, "lifetime control has genuine ready definition");
        player.setModelDefinition(CompletableFuture.completedFuture(definition), false);
        return definition;
    }

    private static int subscriptions(EffectiveSkinSource source) throws Exception {
        synchronized (source) { return ((List<?>) field(source, "retirement")).size(); }
    }

    private static void await(java.util.concurrent.CountDownLatch latch) {
        try { check(latch.await(10, java.util.concurrent.TimeUnit.SECONDS), "controlled lifetime phase reached"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }

    private static void clear(Object player, String message) throws Exception {
        check(field(player, "nclskins$content") == null && field(player, "nclskins$contentIdentity") == null
                && field(player, "nclskins$modelIdentity") == null, message);
    }

    private static void lifecycleControls(boolean world) throws Exception {
        if (world) staleWorldControl();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread retiring = null;
        try (var oldRegistration = registration()) {
            var old = CapeProjection.effectiveSkin(new UUID(0, 991), new UUID(0, 801), "fixture:memo-old", SkinModel.CLASSIC, !world);
            old.onRetirement(() -> { entered.countDown(); await(release); });
            var player = CpmBehaviorProbe.fixturePlayer(old.cacheId());
            var access = (CpmPlayerAccess) player;
            if (!world) access.nclskins$source(old);
            var definition = ready(player);
            try {
                player.getModelDefinition();
                check(access.nclskins$accessorySource() == old && subscriptions(old) == 2, "one subscription via actual entrypoint");
                for (int i = 0; i < 100; i++) {
                    access.nclskins$source(world ? null : old);
                    player.getModelDefinition();
                    check(subscriptions(old) == 2, "repeated binding detaches rather than exhausting source budget");
                }
                access.nclskins$source(null);
                check(subscriptions(old) == 1, "explicit unbind detaches old source callback");
                if (!world) access.nclskins$source(old);
                player.getModelDefinition();
                check(subscriptions(old) == 2, "rebound source registers one callback");
                retiring = new Thread(() -> { try { oldRegistration.close(); } catch (Throwable problem) { failure.set(problem); } }, "fixture-memo-retirement");
                retiring.start(); await(entered);
                check(!old.active(), "real retirement snapshotted callback before detach");
                player.getModelDefinition();
                clear(player, "query while retired callback is held cannot resurrect memo");
                try (var replacementRegistration = registration()) {
                    var replacement = CapeProjection.effectiveSkin(old.cacheId(), old.subject(), "fixture:memo-new", SkinModel.CLASSIC, !world);
                    access.nclskins$source(world ? null : replacement);
                    player.getModelDefinition();
                    Object memo = field(player, "nclskins$content");
                    check(memo != null && access.nclskins$accessorySource() == replacement, "replacement bound and ready");
                    release.countDown(); retiring.join(10000);
                    check(!retiring.isAlive() && failure.get() == null, "old real retirement and cleanup completed");
                    check(field(player, "nclskins$content") == memo && replacement.active(), "late snapshotted old callback preserves replacement memo");
                    access.nclskins$source(old);
                    player.getModelDefinition();
                    clear(player, "already retired registration cannot recreate memo");
                    check(access.nclskins$source() == old, "already retired binding preserves privilege fence");
                    access.nclskins$source(world ? null : replacement);
                    player.getModelDefinition();
                    check(subscriptions(replacement) == 1, "source rebind did not retain detached callbacks");
                    player.cleanup();
                    clear(player, "cleanup clears entire memo");
                    check(subscriptions(replacement) == 0, "cleanup detaches subscription");
                    check(access.nclskins$accessorySource() == replacement && access.nclskins$source() == (world ? null : replacement),
                            "cleanup preserves world tombstone and protected owned-preview source");
                    player.getModelDefinition();
                    clear(player, "late cleanup query preserves detached memo for both entrypoints");
                    check(subscriptions(replacement) == 0, "late cleanup query cannot reattach source");
                    access.nclskins$source(world ? null : replacement);
                    var rebound = ready(player);
                    try {
                        player.getModelDefinition();
                        check(field(player, "nclskins$contentIdentity") == rebound && subscriptions(replacement) == 1,
                                "explicit setter intentionally permits new binding after cleanup");
                    } finally { rebound.cleanup(); }
                }
            } finally { release.countDown(); if (retiring != null) retiring.join(10000); definition.cleanup(); }
        }
        queryRetirementRace(world);
        if (world) cleanupWorldControls();
        System.out.println("PASS memo rebind/detach/budget/late-snapshot/already-retired/cleanup/race world=" + world);
    }

    private static java.util.Queue<Runnable> gameQueue() throws Exception {
        var field = CpmBehaviorProbe.class.getDeclaredField("GAME"); field.setAccessible(true);
        @SuppressWarnings("unchecked") var queue = (java.util.Queue<Runnable>) field.get(null);
        return queue;
    }

    private static void drainGameQueue() throws Exception {
        Runnable task;
        while ((task = gameQueue().poll()) != null) task.run();
    }

    private static void cleanupWorldControls() throws Exception {
        var failures = new ArrayList<AssertionError>();
        for (String boundary : List.of("successor-B", "same-active-A", "never-bound")) {
            for (boolean completed : new boolean[]{false, true}) {
                try { cleanupWorldControl(boundary, completed); }
                catch (AssertionError failure) {
                    failures.add(failure);
                    System.out.println("FAIL cleanup boundary=" + boundary + " completed=" + completed + " " + failure.getMessage());
                }
            }
        }
        if (!failures.isEmpty()) {
            var failure = new AssertionError("cleanup world boundaries failed: " + failures.size());
            failures.forEach(failure::addSuppressed);
            throw failure;
        }
    }

    private static void cleanupWorldControl(String boundary, boolean completed) throws Exception {
        drainGameQueue();
        var oldPlayer = CpmBehaviorProbe.fixturePlayer(new UUID(0, 994));
        var access = (CpmPlayerAccess) oldPlayer;
        ModelDefinition oldDefinition = null, freshDefinition = null, changedDefinition = null;
        try (var firstRegistration = registration()) {
            var first = CapeProjection.effectiveSkin(oldPlayer.getUUID(), new UUID(0, 801), "fixture:cleanup-A", SkinModel.CLASSIC, false);
            oldDefinition = ready(oldPlayer);
            if (!boundary.equals("never-bound")) {
                oldPlayer.getModelDefinition();
                check(access.nclskins$accessorySource() == first && field(oldPlayer, "nclskins$content") != null,
                        "old world player bound genuine A memo before cleanup");
            } else check(access.nclskins$accessorySource() == null && access.nclskins$source() == null,
                    "never-bound cleanup begins before first lazy binding");
            if (boundary.equals("successor-B")) {
                firstRegistration.close();
                check(!first.active(), "real registration retired A before cleanup");
            }
            long binding = (Long) field(oldPlayer, "nclskins$accessoryBinding");
            long revision = (Long) field(oldPlayer, "nclskins$modelRevision");
            var future = oldPlayer.getDefinitionFuture();
            oldPlayer.cleanup();
            check(oldPlayer.getDefinitionFuture() == future && !gameQueue().isEmpty() && oldDefinition.doRender(),
                    "actual SDK cleanup retains future and queues definition cleanup");
            check((Long) field(oldPlayer, "nclskins$accessoryBinding") > binding
                    && (Long) field(oldPlayer, "nclskins$modelRevision") == revision,
                    "cleanup advances binding token without rewinding revision");
            check(field(oldPlayer, "nclskins$accessories") == null && field(oldPlayer, "nclskins$retirement") == null
                    && subscriptions(first) == 0, "cleanup detaches observer and retirement handle");
            clear(oldPlayer, "cleanup clears all memo references immediately");
            if (completed) {
                drainGameQueue();
                check(!oldDefinition.doRender(), "scheduled genuine definition cleanup completed");
            }
            try (var successorRegistration = boundary.equals("successor-B") ? registration() : null) {
                var current = boundary.equals("successor-B")
                        ? CapeProjection.effectiveSkin(oldPlayer.getUUID(), first.subject(), "fixture:cleanup-B", SkinModel.CLASSIC, false) : first;
                var freshPlayer = CpmBehaviorProbe.fixturePlayer(current.cacheId());
                freshDefinition = ready(freshPlayer);
                freshPlayer.getModelDefinition();
                var observation = current.accessories();
                check(observation.availability() == CpmAccessoryObservation.Availability.CONFIRMED && observation.elytra()
                        && observation.elytraContentEmpty() && observation.generation() > 0,
                        "fresh legitimate world observer confirms known-empty native facts before old query");
                var observer = field(freshPlayer, "nclskins$accessories");
                long authority = (Long) field(current, "observer");
                long freshRevision = (Long) field(freshPlayer, "nclskins$modelRevision");
                oldPlayer.getModelDefinition();
                check(current.accessories().equals(observation) && (Long) field(current, "observer") == authority
                        && field(freshPlayer, "nclskins$accessories") == observer,
                        "late cleaned old world query preserves legitimate facts/generation/authority boundary=" + boundary);
                check(access.nclskins$accessorySource() == (boundary.equals("never-bound") ? null : first)
                        && field(oldPlayer, "nclskins$accessories") == null && field(oldPlayer, "nclskins$retirement") == null,
                        "cleanup preserves terminal prior binding without acquiring current source");
                clear(oldPlayer, "late cleaned world query cannot restore old memo");
                changedDefinition = new ModelDefinition(MinecraftClientAccess.get().getDefinitionLoader(), freshPlayer);
                changedDefinition.setParts(List.of()); changedDefinition.resolveAll();
                check(changedDefinition.doRender(), "changed genuine definition ready");
                freshPlayer.setModelDefinition(CompletableFuture.completedFuture(changedDefinition), false);
                freshPlayer.getModelDefinition();
                check(current.accessories().availability() == CpmAccessoryObservation.Availability.CONFIRMED
                        && !current.accessories().present() && current.accessories().generation() > observation.generation()
                        && (Long) field(current, "observer") == authority
                        && (Long) field(freshPlayer, "nclskins$modelRevision") > freshRevision,
                        "legitimate observer still publishes changed definition after old late query");
                drainGameQueue();
                check(!oldDefinition.doRender(), "pending genuine definition cleanup eventually completes");
                System.out.println("PASS cleanup boundary=" + boundary + " completed=" + completed);
            }
        } finally {
            drainGameQueue();
            if (changedDefinition != null) changedDefinition.cleanup();
            if (freshDefinition != null) freshDefinition.cleanup();
            if (oldDefinition != null) oldDefinition.cleanup();
        }
    }

    private static void staleWorldControl() throws Exception {
        var player = CpmBehaviorProbe.fixturePlayer(new UUID(0, 993));
        ModelDefinition definition = null;
        EffectiveSkinSource old;
        try (var registration = registration()) {
            old = CapeProjection.effectiveSkin(player.getUUID(), new UUID(0, 801), "fixture:memo-stale", SkinModel.CLASSIC, false);
            definition = ready(player); player.getModelDefinition();
            check(((CpmPlayerAccess) player).nclskins$accessorySource() == old, "stale-player control bound original world source");
        }
        try (var registration = registration()) {
            var replacement = CapeProjection.effectiveSkin(player.getUUID(), old.subject(), "fixture:memo-successor", SkinModel.CLASSIC, false);
            player.getModelDefinition();
            check(((CpmPlayerAccess) player).nclskins$accessorySource() == old && !replacement.accessories().present(),
                    "retained old world player cannot seize replacement source after retirement");
            clear(player, "retired world tombstone retains no model memo");
        } finally { if (definition != null) definition.cleanup(); }
    }

    private static void queryRetirementRace(boolean world) throws Exception {
        var traversing = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var retired = new java.util.concurrent.CountDownLatch(1);
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        try (var registration = registration()) {
            var source = CapeProjection.effectiveSkin(new UUID(0, 992), new UUID(0, 801), "fixture:memo-race", SkinModel.CLASSIC, !world);
            source.onRetirement(retired::countDown);
            var player = CpmBehaviorProbe.fixturePlayer(source.cacheId());
            if (!world) ((CpmPlayerAccess) player).nclskins$source(source);
            var definition = ready(player);
            var resolved = ModelDefinition.class.getDeclaredField("resolved"); resolved.setAccessible(true);
            var parts = new ArrayList<>((List<?>) resolved.get(definition));
            resolved.set(definition, new AbstractList<Object>() {
                @Override public Object get(int index) { traversing.countDown(); await(release); return parts.get(index); }
                @Override public int size() { return parts.size(); }
            });
            var query = new Thread(() -> { try { player.getModelDefinition(); } catch (Throwable problem) { failure.set(problem); } }, "fixture-memo-query");
            var retirement = new Thread(() -> { try { registration.close(); } catch (Throwable problem) { failure.set(problem); } }, "fixture-memo-race-retirement");
            try {
                query.start(); await(traversing);
                retirement.start(); await(retired);
                check(!source.active(), "retirement crosses actual in-flight traversal");
            } finally {
                release.countDown(); query.join(10000); retirement.join(10000); resolved.set(definition, parts);
            }
            check(!query.isAlive() && !retirement.isAlive() && failure.get() == null, "concurrent query and retirement complete without failure/deadlock");
            clear(player, "in-flight traversal cannot resurrect retired memo");
            definition.cleanup();
        }
    }

    private static Object field(Object owner, String name) throws Exception {
        Class<?> type = owner.getClass();
        while (type != null) {
            try { var field = type.getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
            catch (NoSuchFieldException absent) { type = type.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }

    private static final class SyntheticPackageReader extends ModelPartCollection.PackageLink {
        private SyntheticPackageReader(com.tom.cpm.shared.io.IOHelper link, ModelDefinition definition) throws java.io.IOException {
            super(link, definition);
        }
        private IModelPart read(com.tom.cpm.shared.io.IOHelper data, ModelDefinition definition) throws java.io.IOException {
            return super.load(data, definition);
        }
    }

    private static void verifyDependencies(List<IModelPart> parts, ModelDefinition definition, String mode) {
        int active=0, mappedNull=0, legacyAccessory=0, legacyBody=0;
        for (var part : parts) {
            if (part instanceof com.tom.cpm.shared.parts.anim.ModelPartAnimationNew) {
                var state=((com.naocraftlab.skins.compat.cpm.mixin.CpmAnimationStateAccessor)part).nclskins$animationState();
                for (var animation:state.getAnims().values()) for (var channel:animation.animatorChannels.values()) {
                    if (!(channel.part instanceof com.tom.cpm.shared.parts.anim.AnimatorChannel.CubeVisDriver)) continue;
                    var target=definition.getElementById(((com.naocraftlab.skins.compat.cpm.mixin.CpmVisibilityTargetAccessor)channel.part).nclskins$visibilityTarget());
                    if (target != definition.getElementById(11)) continue;
                    if (channel.frameData==null) mappedNull++; else active++;
                }
            } else if (part instanceof ModelPartAnimation) {
                var declarations=((com.naocraftlab.skins.compat.cpm.mixin.CpmLegacyAnimationsAccessor)part).nclskins$legacyAnimations();
                for (var value:declarations.values()) for (int id:((com.naocraftlab.skins.compat.cpm.mixin.CpmLegacyVisibilityAccessor)value).nclskins$legacyVisibilityTargets()) {
                    if (definition.getElementById(id)==definition.getElementById(11)) legacyAccessory++;
                    if (definition.getElementById(id)==definition.getModelElementFor(PlayerModelParts.BODY).get()) legacyBody++;
                }
            }
        }
        if (mode.equals("mapped-null")) check(mappedNull>0 && active==0,"genuine mapped accessory visibility channel has no frame driver");
        if (mode.equals("animated")) check(active>0,"genuine authored accessory visibility channel has frame data");
        if (mode.equals("legacy-null")) check(legacyAccessory==0 && legacyBody>0,"genuine legacy BODY target does not become native accessory content");
        if (mode.equals("legacy-animated")) check(legacyAccessory>0,"genuine legacy accessory visibility target is retained");
    }
}

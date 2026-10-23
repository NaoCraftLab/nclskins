package com.naocraftlab.skins.buildlogic.probe;

import com.naocraftlab.skins.client.*;
import com.naocraftlab.skins.compat.cpm.*;
import com.naocraftlab.skins.runtime.*;
import com.naocraftlab.skins.core.provider.BuiltinProvider;
import com.tom.cpl.util.Image;
import com.tom.cpm.shared.animation.AnimationState;
import com.tom.cpm.shared.config.Player;
import com.tom.cpm.shared.definition.*;
import com.tom.cpm.shared.model.*;
import com.tom.cpm.shared.model.render.ModelRenderManager;
import com.tom.cpm.shared.skin.TextureProvider;
import java.io.File;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public final class CpmAccessoryProbe {
    public static void select(CapeProjection.Registration registration, Player<?> player,
            EffectiveSkinSource source, ModelDefinition definition, boolean cpm) {
        player.setModelDefinition(CompletableFuture.completedFuture(definition), false);
        player.getModelDefinition();
        var identity = new CapeProjection.Identity(source.cacheId(), player.getName());
        var candidate = CapeProjection.cpmCandidate(source);
        registration.publish(new CapeProjection.Snapshot(cpm ? List.of(BuiltinProvider.CPM, BuiltinProvider.MINECRAFT)
                : List.of(BuiltinProvider.MINECRAFT, BuiltinProvider.CPM), Map.of(), Map.of(), Map.of(), Map.of(),
                candidate == null ? Map.of() : Map.of(identity, candidate), null, null, null));
        CapeProjection.resolve(source.cacheId(), player.getName(), null,
                new CapeProjection.Candidate("fixture:ordinary", null, false), false);
    }

    static void cape(ModelDefinition definition, Player<?> player, boolean allowed) throws Exception {
        if (!Boolean.getBoolean("nclskins.cpm.capeProbe")) return;
        Class<?> host = Class.forName("net.minecraft.client.renderer.entity.layers.CapeLayer");
        var methods = Arrays.stream(host.getDeclaredMethods()).filter(m -> m.getName().endsWith("$nclskins$accessoryCape")).toList();
        if (methods.size() != 1) throw new AssertionError("Exact transformed cape handler cardinality");
        var method = methods.get(0); method.setAccessible(true);
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        Object receiver = ((sun.misc.Unsafe) field.get(null)).allocateInstance(host);
        com.llamalad7.mixinextras.injector.wrapoperation.Operation<Boolean> root = values ->
                ((ModelDefinition) values[0]).hasRoot((com.tom.cpm.shared.model.render.VanillaModelPart) values[1]);
        Object subject = method.getParameterTypes()[3] == Player.class ? player
                : new ModelRenderManager.BoundPlayer(player, new AnimationState(), definition);
        boolean actual = (boolean) method.invoke(receiver, definition, RootModelType.CAPE, root, subject);
        check(actual == (allowed && definition.hasRoot(RootModelType.CAPE)), "executed actual transformed cape branch");
    }

    public static void run(ModelDefinitionLoader<?> loader, Player<?> player,
            CapeProjection.Registration registration, File fixtures) throws Exception {
        var previous = ((CpmPlayerAccess) player).nclskins$source();
        UUID id = new UUID(0, 171);
        var source = CapeProjection.effectiveSkin(id, id, "fixture:accessory", SkinModel.CLASSIC, false);
        ((CpmPlayerAccess) player).nclskins$source(source);
        Object body = new Object(), wings = new Object();
        int[] materials = {0};
        var manager = new ModelRenderManager<Object, Object, Object, Object>() {
            { for (Object model : List.of(body, wings)) holders.put(model, new RedirectHolder<>(this, model) {
                protected void swapIn0() {}
                protected void swapOut0() {}
                protected void bindTexture(Object material, TextureProvider sheet) { materials[0]++; }
                protected void bindDefaultTexture(Object material, TextureSheetType sheet) { materials[0]++; }
            }); }
        };
        var render = new RenderManager<>(manager, loader, value -> null);
        int assertions = 0;
        try {
            for (String variant : List.of("geometry-sheet", "geometry-only", "elytra-only", "empty")) {
                Image image;
                try (var input = new java.io.FileInputStream(new File(fixtures, "accessory-" + variant + ".png"))) { image = Image.loadFrom(input); }
                var definition = loader.loadModel(image, player);
                definition.resolveAll();
                check(definition.getError() == null && definition.doRender(), "fixture resolved");
                Object geometry = definition.getModelElementFor(PlayerModelParts.BODY);
                Object animations = definition.getAnimations();
                if (variant.equals("geometry-sheet")) CpmCloakGeometryProbe.run(registration, player, source, definition);
                for (boolean winner : new boolean[]{true, false, false, true}) {
                    select(registration, player, source, definition, winner);
                    var observation = source.accessories();
                    check(observation.availability() == CpmAccessoryObservation.Availability.CONFIRMED, "resolved observation");
                    check(observation.cape() == variant.startsWith("geometry"), "cape root observation");
                    check(observation.elytra() == !variant.equals("empty"), "elytra root observation");
                    boolean allow = winner && !variant.equals("empty");
                    check(CpmIntegration.accessories(player, definition) == allow, "native chain decision");
                    cape(definition, player, allow);
                    manager.bindModel(body, null, null, definition, player, new AnimationState());
                    render.bindElytra(body, wings);
                    check(manager.isBound(wings) == allow, "actual transformed wings binding");
                    for (TextureSheetType sheet : List.of(TextureSheetType.CAPE, TextureSheetType.ELYTRA, TextureSheetType.SKIN, TextureSheetType.ARMOR1)) {
                        materials[0] = 0;
                        manager.bindSkin(body, new Object(), sheet);
                        boolean accessory = sheet == TextureSheetType.CAPE || sheet == TextureSheetType.ELYTRA;
                        check(materials[0] == (accessory && !allow ? 0 : 1), "actual transformed material gate " + sheet);
                        assertions++;
                    }
                    check(manager.isBound(body) && definition.getModelElementFor(PlayerModelParts.BODY) == geometry
                            && definition.getAnimations() == animations, "body and animation unchanged");
                    manager.unbindModel(wings);
                    manager.unbindModel(body);
                    assertions += 6;
                }
                manager.bindModel(body, null, null, definition, null, new AnimationState());
                for (TextureSheetType sheet : List.of(TextureSheetType.CAPE, TextureSheetType.ELYTRA)) {
                    materials[0] = 0;
                    manager.bindSkin(body, new Object(), sheet);
                    check(materials[0] == 1, "source-less upstream editor material remains available " + sheet);
                    assertions++;
                }
                check(manager.isBound(body) && definition.getModelElementFor(PlayerModelParts.BODY) == geometry
                        && definition.getAnimations() == animations, "source-less body and animation unchanged");
                manager.unbindModel(body);
                definition.cleanup();
            }
            var pending = new CompletableFuture<ModelDefinition>();
            player.setModelDefinition(pending, false);
            player.getModelDefinition();
            check(source.accessories().availability() == CpmAccessoryObservation.Availability.PENDING, "pending is not empty");
            pending.completeExceptionally(new java.io.IOException("fixture failure"));
            player.getModelDefinition();
            check(source.accessories().availability() == CpmAccessoryObservation.Availability.FAILED, "failure is not empty");
            player.setModelDefinition(CompletableFuture.completedFuture(null), false);
            player.getModelDefinition();
            check(source.accessories().empty(), "completed no model is empty");
            Player.setEnableRendering(false);
            player.getModelDefinition();
            check(source.accessories().availability() == CpmAccessoryObservation.Availability.BLOCKED, "disabled policy is not empty");
            Player.setEnableRendering(true);
            assertions += 4;
        } finally {
            manager.unbindModel(wings);
            manager.unbindModel(body);
            ((CpmPlayerAccess) player).nclskins$source(previous);
            CapeProjection.retireSkin(id);
        }
        remoteObserver(loader, player, registration, fixtures);
        preview(loader, fixtures);
        System.out.println("Production accessory pipeline: " + assertions + " assertions; decoded geometry/sheets, transitions, body/armor isolation, pending/error/policy");
    }

    @SuppressWarnings("unchecked")
    private static void remoteObserver(ModelDefinitionLoader<?> loader, Player<?> observerPlayer,
            CapeProjection.Registration registration, File fixtures) throws Exception {
        UUID observer = observerPlayer.getUUID(), remote = new UUID(0, 202);
        check(!observer.equals(remote), "observer is not the rendered remote subject");
        Player<?> player = ((ModelDefinitionLoader<UUID>) loader).loadPlayer(remote, ModelDefinitionLoader.PLAYER_UNIQUE);
        player.getDefinitionFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
        ModelDefinition definition;
        try (var input = new java.io.FileInputStream(new File(fixtures, "accessory-geometry-sheet.png"))) {
            definition = loader.loadModel(Image.loadFrom(input), player);
        }
        definition.resolveAll();
        player.setModelDefinition(CompletableFuture.completedFuture(definition), true);
        check(CpmIntegration.source(player) == null && !(definition instanceof OwnedCpmDefinition),
                "remote definition predates effective source registration");
        player.getModelDefinition();
        check(CpmIntegration.accessories(player, definition), "unmanaged CPM remains available without a current source");
        var source = CapeProjection.effectiveSkin(remote, remote, "fixture:remote", SkinModel.CLASSIC, false);
        Object geometry = definition.getModelElementFor(PlayerModelParts.BODY);
        Object animation = definition.getAnimations();
        var future = player.getDefinitionFuture();
        Object body = new Object(), wings = new Object();
        int[] materials = {0};
        var manager = new ModelRenderManager<Object, Object, Object, Object>() {
            { for (Object model : List.of(body, wings)) holders.put(model, new RedirectHolder<>(this, model) {
                protected void swapIn0() {}
                protected void swapOut0() {}
                protected void bindTexture(Object material, TextureProvider sheet) { materials[0]++; }
                protected void bindDefaultTexture(Object material, TextureSheetType sheet) { materials[0]++; }
            }); }
        };
        var render = new RenderManager<>(manager, loader, value -> null);
        var ordinary = new CapeProjection.Candidate("fixture:remote_cape", "fixture:remote_elytra", true);
        try {
            player.getModelDefinition();
            check(source.accessories().present(), "late remote source receives the actual resolved observation");
            var identity = new CapeProjection.Identity(observer, "Observer");
            var orders = List.of(List.of(BuiltinProvider.CPM, BuiltinProvider.MINECRAFT),
                    List.of(BuiltinProvider.MINECRAFT, BuiltinProvider.CPM), List.of(BuiltinProvider.MINECRAFT),
                    List.of(BuiltinProvider.MINECRAFT, BuiltinProvider.CPM), List.of(BuiltinProvider.CPM, BuiltinProvider.MINECRAFT));
            for (int step = 0; step < orders.size(); step++) {
                boolean allow = step == 0 || step == 3 || step == 4;
                registration.publish(new CapeProjection.Snapshot(orders.get(step), Map.of(), Map.of(), Map.of(), Map.of(),
                        registration.cpmCandidates(), identity, null, null));
                var selected = CapeProjection.resolve(remote, player.getName(), null, step == 3 ? null : ordinary, false);
                check(selected.provider() == (allow ? BuiltinProvider.CPM : BuiltinProvider.MINECRAFT),
                        "observer remote winner follows enabled order and candidate presence");
                var material = CapeProjection.material(remote, player.getName(), false);
                check(material.equals(selected), "auxiliary material cannot replace authoritative remote input");
                manager.bindModel(body, null, null, definition, player, new AnimationState());
                render.bindElytra(body, wings);
                check(manager.isBound(wings) == allow, "observer order toggles actual remote wings without reconnect");
                cape(definition, player, allow);
                for (TextureSheetType sheet : List.of(TextureSheetType.CAPE, TextureSheetType.ELYTRA,
                        TextureSheetType.SKIN, TextureSheetType.ARMOR1, TextureSheetType.ARMOR2)) {
                    materials[0] = 0;
                    manager.bindSkin(body, new Object(), sheet);
                    boolean accessory = sheet == TextureSheetType.CAPE || sheet == TextureSheetType.ELYTRA;
                    check(materials[0] == (accessory && !allow ? 0 : 1), "remote atomic material decision " + sheet);
                }
                check(manager.isBound(body) && player.getModelDefinition0() == definition
                        && player.getUUID().equals(remote) && source.active()
                        && CpmIntegration.source(player) == null && player.getDefinitionFuture() == future
                        && definition.getModelElementFor(PlayerModelParts.BODY) == geometry
                        && definition.getAnimations() == animation, "observer policy preserves remote body, animation, assignment and identity");
            }
            var stale = CapeProjection.cpmObserver(source, player.getName());
            var replacement = CapeProjection.effectiveSkin(remote, remote, "fixture:remote_next", SkinModel.CLASSIC, false);
            player.getModelDefinition();
            check(!stale.publish(100, CpmAccessoryObservation.Availability.CONFIRMED, true, true),
                    "retired callback cannot publish against replacement source");
            check(!replacement.accessories().present(), "old player cannot seize replacement source");
            render.bindElytra(body, wings);
            check(!manager.isBound(wings) && manager.isBound(body), "retirement clears old wings without clearing body");
            materials[0] = 0;
            manager.bindSkin(body, new Object(), TextureSheetType.ELYTRA);
            check(materials[0] == 0, "retired observation cannot restore material");
            CapeProjection.retireSkin(remote);
            player.getModelDefinition();
            check(!CpmIntegration.accessories(player, definition), "retired observer does not become unmanaged passthrough");
            check(player.getDefinitionFuture() == future && CpmIntegration.source(player) == null,
                    "observation never changes remote loader source or publication");
            var draft = CapeProjection.effectiveSkin(remote, observer, "fixture:remote_preview", SkinModel.CLASSIC, true);
            var unmanaged = CpmBehaviorProbe.fixturePlayer(remote);
            unmanaged.setModelDefinition(CompletableFuture.completedFuture(definition), true);
            unmanaged.getModelDefinition();
            check(((CpmPlayerAccess) unmanaged).nclskins$accessorySource() == null && !draft.accessories().present(),
                    "source-less player cannot capture preview observation");
            System.out.println("Remote observer pipeline: remove/reorder/empty fallback/return, actual wings and sheets, stale source fence, unchanged BODY/assignment");
        } finally {
            manager.unbindModel(wings);
            manager.unbindModel(body);
            definition.cleanup();
            CapeProjection.retireSkin(remote);
        }
    }

    private static void preview(ModelDefinitionLoader<?> loader, File fixtures) throws Exception {
        UUID world = new UUID(0, 172);
        var first = CapeProjection.effectiveSkin(new UUID(0, 173), world, "fixture:same_draft", SkinModel.CLASSIC, true);
        var second = CapeProjection.effectiveSkin(new UUID(0, 174), world, "fixture:same_draft", SkinModel.CLASSIC, true);
        var playerA = CpmBehaviorProbe.fixturePlayer(first.cacheId());
        var playerB = CpmBehaviorProbe.fixturePlayer(second.cacheId());
        ((CpmPlayerAccess) playerA).nclskins$source(first);
        ((CpmPlayerAccess) playerB).nclskins$source(second);
        Image image;
        try (var input = new java.io.FileInputStream(new File(fixtures, "accessory-geometry-sheet.png"))) { image = Image.loadFrom(input); }
        var a = loader.loadModel(image, playerA); a.resolveAll();
        var b = loader.loadModel(image, playerB); b.resolveAll();
        playerA.setModelDefinition(CompletableFuture.completedFuture(a), false); playerA.getModelDefinition();
        playerB.setModelDefinition(CompletableFuture.completedFuture(b), false); playerB.getModelDefinition();
        var nativeAppearance = new PreviewRenderer.PreviewAppearance(new TextureRegistry.TextureHandle(first.location(), 64, 64),
                SkinModel.CLASSIC, Optional.empty(), PreviewRenderer.CapeMode.CAPE, OuterLayerVisibility.allVisible(), true, false, true);
        try (var frame = CpmFrame.open(nativeAppearance)) {
            check(CpmFrame.accepts((OwnedCpmDefinition) a), "first draft binds");
            check(CpmIntegration.accessories(playerA, a), "explicit native inspection");
            var replacement = loader.loadModel(image, playerA); replacement.resolveAll();
            playerA.setModelDefinition(CompletableFuture.completedFuture(replacement), false); playerA.getModelDefinition();
            check(a.doRender() && !CpmIntegration.accessories(playerA, a), "old resolved definition cannot draw after model replacement");
            check(CpmIntegration.accessories(playerA, replacement), "current replacement can draw in the same source scope");
            replacement.cleanup();
            check(!CpmFrame.accepts((OwnedCpmDefinition) b), "same pixels do not merge screen identities");
            check(!CpmIntegration.accessories(playerB, b), "other draft cannot inspect in this frame");
            CapeProjection.retireSkin(first.cacheId());
            check(!CpmIntegration.accessories(playerA, a), "retirement removes inspection before release");
        }
        var ordinaryAppearance = new PreviewRenderer.PreviewAppearance(new TextureRegistry.TextureHandle(second.location(), 64, 64),
                SkinModel.CLASSIC, Optional.of(new TextureRegistry.TextureHandle("fixture:ordinary", 64, 32)),
                PreviewRenderer.CapeMode.CAPE, OuterLayerVisibility.allVisible());
        try (var frame = CpmFrame.open(ordinaryAppearance)) {
            check(CpmFrame.accepts((OwnedCpmDefinition) b), "ordinary accessories retain CPM body");
            check(!CpmIntegration.accessories(playerB, b), "ordinary inspection suppresses native bundle");
        }
        a.cleanup(); b.cleanup(); CapeProjection.retireSkin(second.cacheId());
        System.out.println("Production preview accessories: 9 assertions; exact draft identity, ordinary/native selection, retirement");
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}

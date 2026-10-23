package com.naocraftlab.skins.compat.cpm;

import com.naocraftlab.skins.client.PreviewModelFrames;
import com.naocraftlab.skins.client.SkinCatalogSource;
import com.naocraftlab.skins.client.SkinExtensionEnvironmentSource.State;
import com.naocraftlab.skins.core.png.PngValidator;
import com.naocraftlab.skins.runtime.CapeProjection;
import com.naocraftlab.skins.runtime.EffectiveSkinSource;
import com.tom.cpl.util.Image;
import com.tom.cpm.shared.MinecraftClientAccess;
import com.tom.cpm.shared.config.ConfigKeys;
import com.tom.cpm.shared.config.Player;
import com.tom.cpm.shared.definition.ModelDefinitionLoader;
import java.util.concurrent.CompletableFuture;

public final class CpmIntegration {
    private static volatile SkinCatalogSource resources;
    private static final com.naocraftlab.skins.client.CpmModelFileConversion MODEL_FILE_CONVERSION = new CpmModelFileAdapter();

    public static java.util.Optional<com.naocraftlab.skins.client.CpmModelFileConversion> modelFileConversion() {
        return MODEL_FILE_CONVERSION.availability() == com.naocraftlab.skins.client.CpmModelFileConversion.Availability.READY
                ? java.util.Optional.of(MODEL_FILE_CONVERSION) : java.util.Optional.empty();
    }

    private CpmIntegration() {}

    public static void initialize(SkinCatalogSource source) {
        resources = source;
        CapeProjection.skinModels(change -> {
            var client = MinecraftClientAccess.get();
            if (client != null) client.executeOnGameThread(() -> {
                if (change.active() && MinecraftClientAccess.get() == client) {
                    ((CpmModelCacheAccess) client.getDefinitionLoader()).nclskins$refresh(change.cacheId(), change.source());
                }
            });
        });
        PreviewModelFrames.install(CpmFrame::open);
        PreviewModelFrames.prepareWith(CpmIntegration::preparePreview);
    }

    private static volatile Runnable configurationChanged = () -> {};

    @SuppressWarnings("unchecked")
    public static PreviewModelFrames.Body preparePreview(Object profile,
            com.naocraftlab.skins.client.PreviewRenderer.PreviewAppearance appearance) {
        var client = MinecraftClientAccess.get();
        if (client == null) return body(com.naocraftlab.skins.client.PreviewRenderer.BodyStatus.FAILED, () -> true);
        Player<?> player = client.getDefinitionLoader().loadPlayer(profile, ModelDefinitionLoader.PLAYER_UNIQUE);
        if (player == null) return body(com.naocraftlab.skins.client.PreviewRenderer.BodyStatus.PENDING, () -> true);
        EffectiveSkinSource source = source(player);
        if (source == null || !source.preview() || !source.active()
                || !source.location().equals(appearance.skin().location()) || source.model() != appearance.model())
            return body(com.naocraftlab.skins.client.PreviewRenderer.BodyStatus.PENDING, () -> true);
        java.util.function.BooleanSupplier active = source::active;
        if (!Player.isEnableRendering() || !ConfigKeys.ENABLE_MODEL_LOADING.getValueFor(player))
            return body(com.naocraftlab.skins.client.PreviewRenderer.BodyStatus.BLOCKED, active);
        var resolved = player.getModelDefinition();
        var raw = player.getModelDefinition0();
        var rawState = raw == null ? null : raw.getResolveState();
        if (resolved == null && raw instanceof OwnedCpmDefinition owned && owned.source() == source
                && rawState == com.tom.cpm.shared.definition.ModelDefinition.ModelLoadingState.LOADED)
            resolved = player.getModelDefinition();
        if (resolved != null && resolved instanceof OwnedCpmDefinition owned && owned.source() == source)
            return body(com.naocraftlab.skins.client.PreviewRenderer.BodyStatus.READY,
                    () -> source.active() && player.getModelDefinition0() == owned && owned.doRender()
                            && Player.isEnableRendering() && ConfigKeys.ENABLE_MODEL_LOADING.getValueFor(player));
        if (raw != null) return body(switch (rawState) {
            case NEW, RESOLVING -> com.naocraftlab.skins.client.PreviewRenderer.BodyStatus.PENDING;
            case SAFETY_BLOCKED -> com.naocraftlab.skins.client.PreviewRenderer.BodyStatus.BLOCKED;
            case LOADED -> com.naocraftlab.skins.client.PreviewRenderer.BodyStatus.BLOCKED;
            default -> com.naocraftlab.skins.client.PreviewRenderer.BodyStatus.FAILED;
        }, active);
        var future = player.getDefinitionFuture();
        return body(future == null || !future.isDone()
                ? com.naocraftlab.skins.client.PreviewRenderer.BodyStatus.PENDING
                : com.naocraftlab.skins.client.PreviewRenderer.BodyStatus.FAILED, active);
    }

    private static PreviewModelFrames.Body body(com.naocraftlab.skins.client.PreviewRenderer.BodyStatus status,
            java.util.function.BooleanSupplier current) { return new PreviewModelFrames.Body(status, current); }

    public static com.naocraftlab.skins.client.SkinExtensionEnvironmentSource.Subscription subscribe(Runnable listener) {
        configurationChanged = listener;
        return () -> { if (configurationChanged == listener) configurationChanged = () -> {}; };
    }

    public static void settingsChanged() { CapeProjection.invalidateCpmObservations(); configurationChanged.run(); }

    public static State modelLoadingState() {
        try {
            if (MinecraftClientAccess.get() == null) return State.UNKNOWN;
            return Player.isEnableRendering() && ConfigKeys.ENABLE_MODEL_LOADING.getValueFor((Player<?>) null) ? State.ACTIVE : State.INACTIVE;
        } catch (RuntimeException unavailable) { return State.UNKNOWN; }
    }

    public static java.util.UUID subject(Player<?> player, java.util.UUID nativeId) {
        EffectiveSkinSource source = source(player);
        return source == null || !source.preview() ? nativeId : source.subject();
    }

    public static EffectiveSkinSource source(Player<?> player) {
        return ((CpmPlayerAccess) player).nclskins$source();
    }

    public static void observe(Player<?> player, com.tom.cpm.shared.definition.ModelDefinition resolved,
            com.tom.cpm.shared.definition.ModelDefinition raw,
            com.naocraftlab.skins.runtime.CpmAccessoryObserver observer, long revision) {
        var availability = com.naocraftlab.skins.runtime.CpmAccessoryObservation.Availability.UNKNOWN;
        boolean cape = false;
        boolean elytra = false;
        if (!Player.isEnableRendering()) {
            availability = com.naocraftlab.skins.runtime.CpmAccessoryObservation.Availability.BLOCKED;
        } else if (resolved != null) {
            availability = com.naocraftlab.skins.runtime.CpmAccessoryObservation.Availability.CONFIRMED;
            cape = resolved.hasRoot(com.tom.cpm.shared.model.RootModelType.CAPE)
                    || resolved.getTexture(com.tom.cpm.shared.model.TextureSheetType.CAPE, false) != null;
            elytra = resolved.hasRoot(com.tom.cpm.shared.model.RootModelType.ELYTRA_LEFT)
                    || resolved.hasRoot(com.tom.cpm.shared.model.RootModelType.ELYTRA_RIGHT)
                    || resolved.getTexture(com.tom.cpm.shared.model.TextureSheetType.ELYTRA, false) != null;
        } else if (raw != null) {
            availability = switch (raw.getResolveState()) {
                case NEW, RESOLVING -> com.naocraftlab.skins.runtime.CpmAccessoryObservation.Availability.PENDING;
                case SAFETY_BLOCKED -> com.naocraftlab.skins.runtime.CpmAccessoryObservation.Availability.BLOCKED;
                case ERRORRED -> com.naocraftlab.skins.runtime.CpmAccessoryObservation.Availability.FAILED;
                default -> com.naocraftlab.skins.runtime.CpmAccessoryObservation.Availability.UNKNOWN;
            };
        } else {
            var future = player.getDefinitionFuture();
            if (future != null) availability = !future.isDone()
                    ? com.naocraftlab.skins.runtime.CpmAccessoryObservation.Availability.PENDING
                    : future.isCompletedExceptionally() || future.isCancelled()
                    ? com.naocraftlab.skins.runtime.CpmAccessoryObservation.Availability.FAILED
                    : com.naocraftlab.skins.runtime.CpmAccessoryObservation.Availability.CONFIRMED;
        }
        observer.publish(revision, availability, cape, elytra);
    }

    public static boolean accessories(Player<?> player, com.tom.cpm.shared.definition.ModelDefinition definition) {
        if (player == null || definition == null) return true;
        EffectiveSkinSource source = ((CpmPlayerAccess) player).nclskins$accessorySource();
        if (source == null) return true;
        if (!source.active() || !source.accessories().present() || player.getModelDefinition0() != definition
                || !definition.doRender() || !Player.isEnableRendering()) return false;
        return source.preview() ? CpmFrame.accessories(source) : CapeProjection.cpmWins(source);
    }

    public static CompletableFuture<Image> pixels(EffectiveSkinSource source) {
        SkinCatalogSource resourceSource = resources;
        return CompletableFuture.supplyAsync(() -> {
            if (!source.active()) return null;
            int[] pixels = source.pixels();
            if (pixels == null) {
                try {
                    if (!source.location().matches("[a-z0-9_.-]+:textures/[a-z0-9_./-]+")) return null;
                    pixels = new PngValidator().skinPixels(resourceSource.loadResource(source.location()));
                } catch (java.io.IOException | com.naocraftlab.skins.core.png.PngValidationException unavailable) { return null; }
            }
            Image image = new Image(64, 64);
            for (int i = 0; i < pixels.length; i++) image.setRGB(i % 64, i / 64, pixels[i]);
            return source.active() ? image : null;
        }, ModelDefinitionLoader.THREAD_POOL);
    }
}

package com.naocraftlab.skins.core.provider;

import com.naocraftlab.skins.core.model.AppearanceSyncStatus;
import com.naocraftlab.skins.core.model.SkinVariant;

import java.util.Objects;

public record AppearanceProviders(ProviderChannel<ProviderSkin> skin, ProviderChannel<ProviderCape> cape) {
    public enum Component {
        SKIN, CAPE
    }

    public AppearanceProviders {
        Objects.requireNonNull(skin, "skin");
        Objects.requireNonNull(cape, "cape");
        if (skin.order().stream().anyMatch(provider -> !provider.supportsSkin())
                || cape.order().stream().anyMatch(provider -> !provider.supportsCape())) {
            throw new IllegalArgumentException("Provider does not support component");
        }
    }

    public static AppearanceProviders initial() {
        return new AppearanceProviders(ProviderChannel.initial(), ProviderChannel.initial());
    }

    public static AppearanceProviders fromIntent(
            long revision, String skinHash, SkinVariant variant, String capeId, AppearanceSyncStatus status) {
        if (revision == 0) {
            return initial();
        }
        AppearanceProviders selected = initial().select(revision,
                skinHash == null ? null : new ProviderSkin(skinHash, variant),
                capeId == null ? null : new ProviderCape(capeId, null));
        ProviderDelivery.Status uncertain = status == AppearanceSyncStatus.UNKNOWN
                        || status == AppearanceSyncStatus.ATTEMPTING
                ? ProviderDelivery.Status.UNKNOWN : ProviderDelivery.Status.PENDING;
        ProviderDelivery.Status skinStatus = status == AppearanceSyncStatus.OFFICIAL
                        || status == AppearanceSyncStatus.PARTIAL
                ? ProviderDelivery.Status.CONFIRMED : uncertain;
        ProviderDelivery.Status capeStatus = status == AppearanceSyncStatus.OFFICIAL
                ? ProviderDelivery.Status.CONFIRMED : uncertain;
        return new AppearanceProviders(
                selected.skin.settle(selected.skin.minecraftDelivery(), skinStatus, selected.skin.desired()),
                selected.cape.settle(selected.cape.minecraftDelivery(), capeStatus, selected.cape.desired()));
    }

    public boolean galleryAvailable() {
        return skin.order().stream().anyMatch(BuiltinProvider::writable)
                && cape.order().stream().anyMatch(BuiltinProvider::writable);
    }

    public boolean minecraftEnabled() {
        return skin.enabled(BuiltinProvider.MINECRAFT) || cape.enabled(BuiltinProvider.MINECRAFT);
    }

    public AppearanceProviders select(long revision, ProviderSkin skinValue, ProviderCape capeValue) {
        return new AppearanceProviders(skin.select(revision, skinValue), cape.select(revision, capeValue));
    }

    public AppearanceProviders select(long revision, ProviderSkin skinValue, ProviderCape offlineCape, ProviderCape minecraftCape) {
        return new AppearanceProviders(skin.select(revision, skinValue), cape.select(revision, offlineCape, minecraftCape));
    }

    public AppearanceProviders selectMatchingObservations(
            long revision, ProviderSkin skinValue, ProviderCape offlineCape, ProviderCape minecraftCape) {
        return new AppearanceProviders(
                skin.selectMatchingObservation(revision, skinValue, skinValue, Objects::equals),
                cape.selectMatchingObservation(revision, offlineCape, minecraftCape,
                        (observed, selected) -> observed.id().equals(selected.id())));
    }

    public AppearanceProviders revise(
            long revision, ProviderSkin skinValue, ProviderCape offlineCape, ProviderCape minecraftCape) {
        ProviderCape nextOfflineCape = mergeCape(cape.offlineDesired(), offlineCape);
        ProviderCape nextMinecraftCape = mergeCape(cape.desired(), minecraftCape);
        boolean skinChanged = skin.intentRevision() == 0
                || !skinIdentityEquals(skin.desired(), skinValue);
        boolean capeChanged = cape.intentRevision() == 0
                || !capeIdentityEquals(cape.desired(), nextMinecraftCape);
        return new AppearanceProviders(
                skin.revise(revision, skinValue, skinValue, skinChanged),
                cape.revise(revision, nextOfflineCape, nextMinecraftCape, capeChanged));
    }

    public AppearanceProviders bootstrap(long revision, ProviderSkin skinValue, ProviderCape capeValue) {
        return new AppearanceProviders(skin.bootstrap(revision, skinValue), cape.bootstrap(revision, capeValue));
    }

    public AppearanceProviders bootstrap(long revision, ProviderSkin skinValue, ProviderCape offlineCape, ProviderCape minecraftCape) {
        var bootstrapped = bootstrap(revision, skinValue, minecraftCape);
        if (cape.intentRevision() != 0 || offlineCape == null) return bootstrapped;
        var channel = bootstrapped.cape();
        return new AppearanceProviders(bootstrapped.skin(), new ProviderChannel<>(channel.order(),
                channel.enabled(BuiltinProvider.OFFLINE) ? ProviderObservation.observed(offlineCape) : channel.offline(),
                channel.minecraft(), channel.configurationRevision(), channel.intentRevision(), channel.desired(),
                channel.minecraftDelivery(), offlineCape, channel.optifine()));
    }

    public AppearanceProviders withCapeTexture(String capeId, String textureCacheKey) {
        return withCapeTexture(capeId, textureCacheKey, null);
    }

    public AppearanceProviders withCapeTexture(String capeId, String textureCacheKey, Boolean hasElytra) {
        ProviderCape texture = new ProviderCape(capeId, textureCacheKey, hasElytra);
        ProviderObservation<ProviderCape> offline = cape.offline();
        ProviderObservation<ProviderCape> minecraft = cape.minecraft();
        return new AppearanceProviders(skin, new ProviderChannel<>(cape.order(),
                new ProviderObservation<>(offline.known(), enrichCape(offline.value(), texture)),
                new ProviderObservation<>(minecraft.known(), enrichCape(minecraft.value(), texture)),
                cape.configurationRevision(), cape.intentRevision(), enrichCape(cape.desired(), texture),
                cape.minecraftDelivery(), enrichCape(cape.offlineDesired(), texture),
                new ProviderObservation<>(cape.optifine().known(), enrichCape(cape.optifine().value(), texture))));
    }

    private static ProviderCape enrichCape(ProviderCape value, ProviderCape texture) {
        return value != null && value.id().equals(texture.id()) && (value.textureCacheKey() == null || value.textureCacheKey().equals(texture.textureCacheKey()) && value.hasElytra() == null)
                ? texture : value;
    }

    private static boolean skinIdentityEquals(ProviderSkin first, ProviderSkin second) {
        return Objects.equals(first, second);
    }

    private static boolean capeIdentityEquals(ProviderCape first, ProviderCape second) {
        return Objects.equals(first == null ? null : first.id(), second == null ? null : second.id());
    }

    private static ProviderCape mergeCape(ProviderCape previous, ProviderCape next) {
        if (previous == null || next == null || !previous.id().equals(next.id())) {
            return next;
        }
        return new ProviderCape(
                next.id(),
                next.textureCacheKey() == null ? previous.textureCacheKey() : next.textureCacheKey(),
                next.hasElytra() == null ? previous.hasElytra() : next.hasElytra());
    }

    public AppearanceProviders enable(Component component, BuiltinProvider provider) {
        requireSupported(component, provider);
        return switch (component) {
            case SKIN -> new AppearanceProviders(skin.enable(provider), cape);
            case CAPE -> new AppearanceProviders(skin, cape.enable(provider));
        };
    }

    public AppearanceProviders disable(Component component, BuiltinProvider provider) {
        if (component == Component.SKIN && !provider.supportsSkin()) {
            return this;
        }
        return switch (component) {
            case SKIN -> new AppearanceProviders(skin.disable(provider), cape);
            case CAPE -> new AppearanceProviders(skin, cape.disable(provider));
        };
    }

    public AppearanceProviders move(Component component, BuiltinProvider provider, int direction) {
        if (component == Component.SKIN && !provider.supportsSkin()) {
            return this;
        }
        return switch (component) {
            case SKIN -> new AppearanceProviders(skin.move(provider, direction), cape);
            case CAPE -> new AppearanceProviders(skin, cape.move(provider, direction));
        };
    }

    private static void requireSupported(Component component, BuiltinProvider provider) {
        Objects.requireNonNull(component, "component");
        Objects.requireNonNull(provider, "provider");
        if (component == Component.SKIN && !provider.supportsSkin()
                || component == Component.CAPE && !provider.supportsCape()) {
            throw new IllegalArgumentException("Provider does not support component");
        }
    }
}

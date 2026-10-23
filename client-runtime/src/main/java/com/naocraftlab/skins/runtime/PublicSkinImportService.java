package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.MinecraftSkinCatalog;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.core.api.PublicSkinImportException;
import com.naocraftlab.skins.core.model.PersonalSkinSource;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.png.NormalizedSkin;
import com.naocraftlab.skins.core.png.PngValidationException;
import com.naocraftlab.skins.core.png.PngValidator;
import com.naocraftlab.skins.core.importing.PublicProfileLookup;
import com.naocraftlab.skins.core.importing.PublicSkinImageSource;

import java.util.Optional;
import java.util.Locale;
import java.util.Objects;


public final class PublicSkinImportService implements PublicSkinImports {
    private final Optional<PublicProfileLookup> players;
    private final PublicSkinImageSource images;
    private final CatalogVariantLoader catalog;
    private final Optional<PublicSkinImageSource.DependencySource> dependencies;
    private final java.util.function.LongSupplier clock;
    private final PngValidator pngValidator = new PngValidator();

    public PublicSkinImportService(Optional<PublicProfileLookup> players, PublicSkinImageSource images,
            CatalogVariantLoader catalog) {
        this(players, images, catalog, images instanceof PublicSkinImageSource.DependencySource source
                ? Optional.of(source) : Optional.empty(), System::nanoTime);
    }

    public PublicSkinImportService(Optional<PublicProfileLookup> players, PublicSkinImageSource images,
            CatalogVariantLoader catalog, Optional<PublicSkinImageSource.DependencySource> dependencies, java.util.function.LongSupplier clock) {
        this.dependencies = Objects.requireNonNull(dependencies);
        this.clock = Objects.requireNonNull(clock);
        this.players = Objects.requireNonNull(players, "players");
        this.images = Objects.requireNonNull(images, "images");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    @Override
    public ImportOperations.ImportDraft loadPlayer(String playerNameOrUuid) throws Exception {
        PublicProfileLookup lookup = players.orElseThrow(() ->
                new UnsupportedOperationException("Public player skin lookup is unavailable"));
        return loadResolvedPlayer(lookup.lookup(playerNameOrUuid));
    }

    private ImportOperations.ImportDraft loadResolvedPlayer(PublicProfileLookup.Observation result) throws Exception {
        Objects.requireNonNull(result, "result");
        NormalizedSkin skin;
        if (result.texture().isPresent()) {
            skin = images.loadVerified(result.texture().orElseThrow());
        } else {
            try {
                byte[] png = catalog.load(
                        MinecraftSkinCatalog.COLLECTION_ID,
                        result.defaultSkinId().orElseThrow(),
                        result.variant() == SkinVariant.SLIM ? SkinModel.SLIM : SkinModel.CLASSIC);
                skin = pngValidator.normalizeSkinWithVariant(png);
            } catch (PngValidationException failure) {
                throw playerTextureFailure(failure);
            }
        }
        return new ImportOperations.ImportDraft(
                result.canonicalName(), skin.detectedVariant(), skin.pngBytes(), PersonalSkinSource.PLAYER_NAME);
    }

    static PublicSkinImportException playerTextureFailure(PngValidationException failure) {
        Objects.requireNonNull(failure, "failure");
        PublicSkinImportException.Code code = failure.reason() == PngValidationException.Reason.OVERSIZED
                ? PublicSkinImportException.Code.OVERSIZED
                : PublicSkinImportException.Code.PROFILE_REJECTED;
        return new PublicSkinImportException(code, "Public player skin texture was rejected.");
    }

    @Override
    public ImportOperations.ImportDraft loadUrl(String url) throws Exception {
        NormalizedSkin skin = images.loadUntrusted(url);
        String fallback = "Imported URL skin";
        String name = fallback;
        try {
            String path = java.net.URI.create(url.trim()).getPath();
            if (path != null && !path.isBlank()) {
                String candidate = path.substring(path.lastIndexOf('/') + 1);
                if (candidate.toLowerCase(Locale.ROOT).endsWith(".png")) {
                    candidate = candidate.substring(0, candidate.length() - 4);
                }
                name = UntrustedDisplayName.sanitize(candidate, fallback);
            }
        } catch (IllegalArgumentException ignored) {

        }
        return new ImportOperations.ImportDraft(
                name, skin.detectedVariant(), skin.pngBytes(), PersonalSkinSource.URL);
    }

    @FunctionalInterface
    public interface CatalogVariantLoader {
        byte[] load(String collectionId, String skinId, SkinModel model) throws Exception;
    }

    @Override
    public ImportOperations.AcquiredInput acquireFile(java.nio.file.Path path,
            Optional<com.naocraftlab.skins.client.CpmModelFileConversion> converter,
            com.naocraftlab.skins.client.CpmModelFileConversion.Reason unavailable) throws Exception {
        return admitInput(images.acquireFile(path), converter, unavailable,
                UntrustedDisplayName.fromFileName(path.getFileName().toString(), "Imported skin"), PersonalSkinSource.FILE, dependencies, clock);
    }

    @Override
    public ImportOperations.AcquiredInput acquireUrl(String url,
            Optional<com.naocraftlab.skins.client.CpmModelFileConversion> converter,
            com.naocraftlab.skins.client.CpmModelFileConversion.Reason unavailable) throws Exception {
        return admitInput(images.acquireUrl(url), converter, unavailable, urlName(url), PersonalSkinSource.URL, dependencies, clock);
    }

    private static String urlName(String url) {
        try {
            String path = java.net.URI.create(url.trim()).getPath();
            return UntrustedDisplayName.fromFileName(path.substring(path.lastIndexOf('/') + 1), "Imported URL skin");
        } catch (IllegalArgumentException | NullPointerException failure) { return "Imported URL skin"; }
    }

    public static ImportOperations.AcquiredInput admitInput(PublicSkinImageSource.Acquired acquired,
            Optional<com.naocraftlab.skins.client.CpmModelFileConversion> converter,
            com.naocraftlab.skins.client.CpmModelFileConversion.Reason unavailable,
            String name, PersonalSkinSource source) throws Exception {
        return admitInput(acquired, converter, unavailable, name, source,
                Optional.empty(), System::nanoTime);
    }

    private static ImportOperations.AcquiredInput admitInput(PublicSkinImageSource.Acquired acquired,
            Optional<com.naocraftlab.skins.client.CpmModelFileConversion> converter,
            com.naocraftlab.skins.client.CpmModelFileConversion.Reason unavailable,
            String name, PersonalSkinSource source, Optional<PublicSkinImageSource.DependencySource> fetch, java.util.function.LongSupplier clock) throws Exception {
        if (acquired instanceof PublicSkinImageSource.PngInput png) {
            var skin = png.skin();
            return new ImportOperations.AcquiredSkin(new ImportOperations.ImportDraft(
                    name, skin.detectedVariant(), skin.pngBytes(), source));
        }
        return admitModel(((PublicSkinImageSource.ModelInput) acquired).input(), converter, unavailable, fetch, clock);
    }

    private static ImportOperations.AcquiredInput admitModel(com.naocraftlab.skins.client.ImportInput frozen,
            Optional<com.naocraftlab.skins.client.CpmModelFileConversion> converter,
            com.naocraftlab.skins.client.CpmModelFileConversion.Reason unavailable,
            Optional<PublicSkinImageSource.DependencySource> fetch, java.util.function.LongSupplier clock) throws Exception {
        try {
            if (converter.isEmpty()) throw new com.naocraftlab.skins.client.CpmModelFileConversion.Failure(unavailable);
            if (converter.orElseThrow() instanceof com.naocraftlab.skins.client.CpmModelFileConversion.DependencyAdmission adapter) {
                var operation = new DependencyInput(frozen, adapter, fetch, clock);
                return operation.admit(() -> true);
            }
            try (frozen; var input = frozen.open()) {
                return new ImportOperations.AcquiredModel(converter.orElseThrow().admit(input));
            }
        } catch (Exception | Error failure) {
            try { frozen.close(); } catch (java.io.IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    private static final class DependencyInput implements ImportOperations.AcquiredDependency {
        private static final int MAX_BYTES = 128 * 1024 * 1024;
        private final com.naocraftlab.skins.client.ImportInput input;
        private final com.naocraftlab.skins.client.CpmModelFileConversion.DependencyAdmission adapter;
        private final Optional<PublicSkinImageSource.DependencySource> fetch;
        private final java.util.function.LongSupplier clock;
        private final long started;
        private final java.util.Map<String, byte[]> dependencies = new java.util.HashMap<>();
        private com.naocraftlab.skins.client.CpmModelFileConversion.Dependency pending;
        private int attempts, bytes;
        private volatile boolean closed;

        private DependencyInput(com.naocraftlab.skins.client.ImportInput input,
                com.naocraftlab.skins.client.CpmModelFileConversion.DependencyAdmission adapter,
                Optional<PublicSkinImageSource.DependencySource> fetch, java.util.function.LongSupplier clock) {
            this.input = input; this.adapter = adapter; this.fetch = Objects.requireNonNull(fetch);
            this.clock = Objects.requireNonNull(clock); started = clock.getAsLong();
        }

        private void requireCurrent(java.util.function.BooleanSupplier current) throws Exception {
            if (closed || Thread.currentThread().isInterrupted() || !current.getAsBoolean())
                throw new com.naocraftlab.skins.client.CpmModelFileConversion.Failure(
                        com.naocraftlab.skins.client.CpmModelFileConversion.Reason.CLOSED);
            if (clock.getAsLong() - started >= java.time.Duration.ofSeconds(120).toNanos())
                throw new PublicSkinImportException(PublicSkinImportException.Code.NETWORK_FAILURE, "Dependency operation expired");
        }

        private synchronized ImportOperations.AcquiredInput admit(java.util.function.BooleanSupplier current) throws Exception {
            requireCurrent(current);
            com.naocraftlab.skins.client.CpmModelFileConversion.Admission result = null;
            try (var reader = input.open()) { result = adapter.admit(reader, java.util.Map.copyOf(dependencies)); }
            catch (Exception | Error failure) {
                if (result instanceof com.naocraftlab.skins.client.CpmModelFileConversion.Ready ready) ready.model().close();
                throw failure;
            }
            if (result instanceof com.naocraftlab.skins.client.CpmModelFileConversion.Ready ready) {
                try {
                    requireCurrent(current);
                    close();
                    return new ImportOperations.AcquiredModel(ready.model());
                } catch (Exception | Error failure) { ready.model().close(); throw failure; }
            }
            requireCurrent(current);
            pending = (com.naocraftlab.skins.client.CpmModelFileConversion.Dependency) result;
            if (fetch.isEmpty()) throw new com.naocraftlab.skins.client.CpmModelFileConversion.Failure(
                    com.naocraftlab.skins.client.CpmModelFileConversion.Reason.PORTABILITY_UNSUPPORTED);
            if (dependencies.containsKey(pending.link()) || attempts >= 16 || bytes >= MAX_BYTES)
                throw new PublicSkinImportException(PublicSkinImportException.Code.OVERSIZED, "Dependency operation limit");
            return this;
        }

        @Override public synchronized ImportOperations.AcquiredInput acquire(java.util.function.BooleanSupplier current) throws Exception {
            try {
                requireCurrent(current);
                if (pending == null || attempts >= 16 || bytes >= MAX_BYTES)
                    throw new PublicSkinImportException(PublicSkinImportException.Code.OVERSIZED, "Dependency operation limit");
                var requested = pending;
                pending = null;
                attempts++;
                long remaining = java.time.Duration.ofSeconds(120).toNanos() - (clock.getAsLong() - started);
                if (remaining <= 0) throw new PublicSkinImportException(
                        PublicSkinImportException.Code.NETWORK_FAILURE, "Dependency operation expired");
                byte[] body = fetch.orElseThrow(() -> new com.naocraftlab.skins.client.CpmModelFileConversion.Failure(
                        com.naocraftlab.skins.client.CpmModelFileConversion.Reason.PORTABILITY_UNSUPPORTED))
                        .acquireDependency(requested.uri(), java.time.Duration.ofNanos(
                        Math.min(java.time.Duration.ofSeconds(30).toNanos(), remaining)), MAX_BYTES - bytes);
                requireCurrent(current);
                if (body.length > MAX_BYTES - bytes)
                    throw new PublicSkinImportException(PublicSkinImportException.Code.OVERSIZED, "Dependency operation limit");
                dependencies.put(requested.link(), body.clone());
                bytes += body.length;
                return admit(current);
            } catch (Exception | Error failure) {
                try { close(); } catch (java.io.IOException closeFailure) { failure.addSuppressed(closeFailure); }
                throw failure;
            }
        }

        @Override public void close() throws java.io.IOException {
            closed = true;
            synchronized (this) { pending = null; dependencies.clear(); input.close(); }
        }
    }
}

package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.SkinCatalogSource;
import com.naocraftlab.skins.core.importing.ExternalAppearanceRecord;
import com.naocraftlab.skins.core.importing.ExternalImportAdapter;
import com.naocraftlab.skins.core.importing.ExternalImportBatch;
import com.naocraftlab.skins.core.importing.ExternalImportContext;
import com.naocraftlab.skins.core.importing.ExternalImportProbe;
import com.naocraftlab.skins.core.importing.ExternalImportSource;
import com.naocraftlab.skins.core.importing.SkinLocator;
import com.naocraftlab.skins.core.model.AccountState;
import com.naocraftlab.skins.core.model.OwnedCapeInventory;
import com.naocraftlab.skins.core.model.PersonalSkinEntry;
import com.naocraftlab.skins.core.model.PersonalSkinSource;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.png.NormalizedSkin;
import com.naocraftlab.skins.core.png.PngValidationException;
import com.naocraftlab.skins.core.png.PngValidator;
import com.naocraftlab.skins.core.service.LibraryService;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;


final class ExternalAppearanceImportService {
    private final LibraryService library;
    private final PublicSkinImportService publicImports;
    private final SkinCatalogSource resources;
    private final PngValidator pngValidator;
    private final Map<ExternalImportSource, ExternalImportAdapter> adapters;

    ExternalAppearanceImportService(
            LibraryService library,
            PublicSkinImportService publicImports,
            SkinCatalogSource resources) {
        this(library, publicImports, resources, new PngValidator(), List.of(
                new MinecraftLauncherImportAdapter(),
                new CurseForgeAppImportAdapter(),
                new ModrinthAppImportAdapter(),
                new SkinShuffleImportAdapter(),
                new PrismLauncherImportAdapter()));
    }

    ExternalAppearanceImportService(
            LibraryService library,
            PublicSkinImportService publicImports,
            SkinCatalogSource resources,
            PngValidator pngValidator,
            List<ExternalImportAdapter> adapters) {
        this.library = Objects.requireNonNull(library, "library");
        this.publicImports = Objects.requireNonNull(publicImports, "publicImports");
        this.resources = Objects.requireNonNull(resources, "resources");
        this.pngValidator = Objects.requireNonNull(pngValidator, "pngValidator");
        EnumMap<ExternalImportSource, ExternalImportAdapter> mapped =
                new EnumMap<>(ExternalImportSource.class);
        for (ExternalImportAdapter adapter : adapters) {
            ExternalImportAdapter previous = mapped.put(
                    Objects.requireNonNull(adapter, "adapters contains null").source(), adapter);
            if (previous != null) {
                throw new IllegalArgumentException("duplicate external import adapter");
            }
        }
        this.adapters = Map.copyOf(mapped);
    }

    ExternalImportProbe probe(
            ExternalImportSource source,
            Optional<Path> selectedRoot,
            ExternalImportContext context) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(selectedRoot, "selectedRoot");
        Objects.requireNonNull(context, "context");
        if (source.requiresSqlite() && !SqliteSupport.available()) {
            return ExternalImportProbe.DEPENDENCY_MISSING;
        }
        ExternalImportAdapter adapter = adapters.get(source);
        if (adapter == null) {
            return ExternalImportProbe.UNAVAILABLE;
        }
        List<Path> roots = selectedRoot
                .map(path -> List.of(path.toAbsolutePath().normalize()))
                .orElseGet(() -> expectedRoots(source, context));
        for (Path root : roots) {
            try {
                if (adapter.probe(root, context)) {
                    return ExternalImportProbe.AVAILABLE;
                }
            } catch (IOException | RuntimeException failure) {
            }
        }
        return ExternalImportProbe.UNAVAILABLE;
    }

    ClientOperations.ExternalImportReview prepareAppearances(
            UUID accountId,
            ExternalImportSource source,
            Optional<Path> selectedRoot,
            ExternalImportContext context,
            OwnedCapeInventory ownedCapes) throws Exception {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(selectedRoot, "selectedRoot");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(ownedCapes, "ownedCapes");
        if (source.requiresSqlite() && !SqliteSupport.available()) {
            throw new ExternalImportException(
                    ExternalImportException.Code.DEPENDENCY_MISSING,
                    "Minecraft SQLite JDBC is unavailable");
        }

        ExternalImportAdapter adapter = Optional.ofNullable(adapters.get(source))
                .orElseThrow(() -> new ExternalImportException(
                        ExternalImportException.Code.NOT_FOUND,
                        "External import adapter is unavailable"));
        ExternalImportBatch batch = discover(adapter, source, selectedRoot, context);
        Map<String, ExistingPersonalSkin> existingSkins = existingPersonalSkins(accountId);
        List<ClientOperations.ExternalImportCandidate> resolved = new ArrayList<>();
        int skipped = 0;
        int warnings = batch.warnings().size();
        for (ExternalAppearanceRecord record : batch.records()) {
            try {
                Resolution resolution = resolve(record);
                String name = UntrustedDisplayName.sanitize(record.displayName(), "Imported look");
                String capeId = record.externalCapeId()
                        .filter(id -> ownedCapes.find(id).isPresent())
                        .orElse(null);
                if (record.externalCapeId().isPresent() && capeId == null) {
                    warnings++;
                }
                SkinVariant variant = record.declaredVariant().orElse(resolution.variant());
                byte[] resolvedPng = resolution.pngBytes();
                ExistingPersonalSkin existing = existingSkins.get(
                        pngValidator.pixelSha256(resolvedPng));
                byte[] candidatePng = existing == null ? resolvedPng : existing.pngBytes();
                String sha256 = existing == null ? sha256(candidatePng) : existing.sha256();
                resolved.add(new ClientOperations.ExternalImportCandidate(
                        "candidate-" + resolved.size(),
                        name,
                        variant,
                        resolution.source(),
                        candidatePng,
                        sha256,
                        capeId,
                        record.sourceOrder(),
                        existing != null));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            } catch (Exception rejectedRecord) {
                skipped++;
            }
        }
        if (resolved.isEmpty()) {
            throw new ExternalImportException(
                    ExternalImportException.Code.NO_VALID_APPEARANCES,
                    "The source contains no importable appearances");
        }
        return new ClientOperations.ExternalImportReview(
                source, resolved, skipped, warnings);
    }

    private Map<String, ExistingPersonalSkin> existingPersonalSkins(UUID accountId)
            throws IOException {
        AccountState account = library.load(accountId);
        Map<String, ExistingPersonalSkin> existing = new HashMap<>();
        for (PersonalSkinEntry entry : account.personalSkins()) {
            if (!entry.visible()) {
                continue;
            }
            Optional<UUID> assetId = entry.variantAssetIds().values().stream().findFirst();
            if (assetId.isEmpty()) {
                continue;
            }
            try {
                byte[] png = library.resolveSkin(account, assetId.orElseThrow()).pngBytes();
                existing.putIfAbsent(
                        pngValidator.pixelSha256(png),
                        new ExistingPersonalSkin(entry.sha256(), png));
            } catch (IOException | PngValidationException | RuntimeException invalidExistingAsset) {
            }
        }
        return Map.copyOf(existing);
    }

    Result commitAppearances(
            UUID accountId,
            List<ClientOperations.ExternalImportCandidate> selected,
            int skipped,
            int warnings) throws Exception {
        Objects.requireNonNull(accountId, "accountId");
        List<ClientOperations.ExternalImportCandidate> candidates = List.copyOf(
                Objects.requireNonNull(selected, "selected"));
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("At least one external appearance must be selected");
        }
        List<LibraryService.PersonalSkinPresetImport> imports = candidates.stream()
                .map(candidate -> new LibraryService.PersonalSkinPresetImport(
                        candidate.displayName(),
                        candidate.displayName(),
                        candidate.variant(),
                        candidate.source(),
                        candidate.normalizedPng(),
                        candidate.capeId()))
                .toList();
        LibraryService.BatchPersonalSkinPresetImport imported =
                library.importPersonalSkinPresets(accountId, imports);
        return new Result(
                imported.state(), imported.imported(), imported.alreadyPresent(), skipped, warnings);
    }

    private ExternalImportBatch discover(
            ExternalImportAdapter adapter,
            ExternalImportSource source,
            Optional<Path> selectedRoot,
            ExternalImportContext context) throws ExternalImportException {
        List<Path> roots = selectedRoot
                .map(path -> List.of(path.toAbsolutePath().normalize()))
                .orElseGet(() -> expectedRoots(source, context));
        IOException lastFailure = null;
        ExternalImportBatch recognizedEmpty = null;
        for (Path root : roots) {
            try {
                ExternalImportBatch batch = adapter.discover(root, context);
                if (!batch.records().isEmpty()) {
                    return batch;
                }
                recognizedEmpty = batch;
            } catch (IOException failure) {
                lastFailure = failure;
            }
        }
        if (recognizedEmpty != null) {
            return recognizedEmpty;
        }
        throw new ExternalImportException(
                ExternalImportException.Code.NOT_FOUND,
                "Expected external appearance data was not found",
                lastFailure);
    }

    private Resolution resolve(ExternalAppearanceRecord record) throws Exception {
        SkinLocator locator = record.skinLocator();
        if (locator instanceof SkinLocator.EmbeddedPng embedded) {
            return normalized(embedded.pngBytes(), PersonalSkinSource.FILE, Optional.empty());
        }
        if (locator instanceof SkinLocator.LocalPng local) {
            return normalized(readBounded(local.path()), PersonalSkinSource.FILE, Optional.empty());
        }
        if (locator instanceof SkinLocator.PublicUrl remote) {
            if (remote.localCache().filter(path ->
                    Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).isPresent()) {
                try {
                    return normalized(
                            readBounded(remote.localCache().orElseThrow()),
                            PersonalSkinSource.URL,
                            Optional.empty());
                } catch (IOException | RuntimeException invalidCache) {
                }
            }
            ClientOperations.ImportDraft draft = publicImports.loadUrl(remote.url());
            return normalized(
                    draft.pngBytes(), PersonalSkinSource.URL, Optional.of(draft.variant()));
        }
        if (locator instanceof SkinLocator.PublicPlayer player) {
            ClientOperations.ImportDraft draft = publicImports.loadPlayer(player.nameOrUuid());
            return normalized(
                    draft.pngBytes(), PersonalSkinSource.PLAYER_NAME, Optional.of(draft.variant()));
        }
        if (locator instanceof SkinLocator.MinecraftResource resource) {
            return normalized(
                    resources.loadResource(resource.identifier()),
                    PersonalSkinSource.FILE,
                    Optional.empty());
        }
        throw new IOException("Unsupported external skin locator");
    }

    private Resolution normalized(
            byte[] png, PersonalSkinSource source, Optional<SkinVariant> suggestedVariant)
            throws Exception {
        NormalizedSkin skin = pngValidator.normalizeSkinWithVariant(png);
        return new Resolution(
                skin.pngBytes(), suggestedVariant.orElse(skin.detectedVariant()), source);
    }

    private static byte[] readBounded(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("External skin file is unavailable");
        }
        try (InputStream input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(PngValidator.DEFAULT_MAX_BYTES + 1);
            if (bytes.length > PngValidator.DEFAULT_MAX_BYTES) {
                throw new IOException("External skin file exceeds the PNG limit");
            }
            return bytes;
        }
    }

    static List<Path> expectedRoots(
            ExternalImportSource source, ExternalImportContext context) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        Path home = Path.of(System.getProperty("user.home", ".")).toAbsolutePath().normalize();
        return expectedRoots(source, context, os, home, System.getenv());
    }

    static List<Path> expectedRoots(
            ExternalImportSource source,
            ExternalImportContext context,
            String os,
            Path home,
            Map<String, String> environment) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(os, "os");
        Objects.requireNonNull(home, "home");
        Objects.requireNonNull(environment, "environment");
        if (source == ExternalImportSource.SKIN_SHUFFLE) {
            return List.of(context.currentGameDirectory());
        }
        if (os.contains("win")) {
            Path appData = optionalPath(environment.get("APPDATA")).orElse(home);
            return List.of(appData.resolve(switch (source) {
                case MINECRAFT_LAUNCHER -> ".minecraft";
                case CURSEFORGE_APP -> "CurseForge";
                case MODRINTH_APP -> "ModrinthApp";
                case PRISM_LAUNCHER -> "PrismLauncher";
                case SKIN_SHUFFLE -> throw new IllegalStateException("handled above");
            }));
        }
        if (os.contains("mac")) {
            Path applicationSupport = home.resolve("Library").resolve("Application Support");
            return List.of(applicationSupport.resolve(switch (source) {
                case MINECRAFT_LAUNCHER -> "minecraft";
                case CURSEFORGE_APP -> "CurseForge";
                case MODRINTH_APP -> "ModrinthApp";
                case PRISM_LAUNCHER -> "PrismLauncher";
                case SKIN_SHUFFLE -> throw new IllegalStateException("handled above");
            }));
        }
        if (source == ExternalImportSource.MINECRAFT_LAUNCHER) {
            return List.of(home.resolve(".minecraft"));
        }
        if (source == ExternalImportSource.CURSEFORGE_APP) {
            Path configHome = optionalPath(environment.get("XDG_CONFIG_HOME"))
                    .orElse(home.resolve(".config"));
            return List.of(configHome.resolve("CurseForge"));
        }
        Path dataHome = optionalPath(environment.get("XDG_DATA_HOME"))
                .orElse(home.resolve(".local").resolve("share"));
        if (source == ExternalImportSource.MODRINTH_APP) {
            return List.of(
                    dataHome.resolve("ModrinthApp"),
                    home.resolve(".var").resolve("app").resolve("com.modrinth.ModrinthApp")
                            .resolve("data").resolve("ModrinthApp"));
        }
        return List.of(
                dataHome.resolve("PrismLauncher"),
                home.resolve(".var").resolve("app").resolve("org.prismlauncher.PrismLauncher")
                        .resolve("data").resolve("PrismLauncher"));
    }

    private static Optional<Path> optionalPath(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Path.of(value).toAbsolutePath().normalize());
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    record Result(
            AccountState state,
            int imported,
            int alreadyPresent,
            int skipped,
            int warnings) {
        Result {
            Objects.requireNonNull(state, "state");
            if (imported < 0 || alreadyPresent < 0 || skipped < 0 || warnings < 0) {
                throw new IllegalArgumentException("external import counters must not be negative");
            }
        }
    }

    private record Resolution(
            byte[] pngBytes, SkinVariant variant, PersonalSkinSource source) {
        private Resolution {
            pngBytes = Objects.requireNonNull(pngBytes, "pngBytes").clone();
            Objects.requireNonNull(variant, "variant");
            Objects.requireNonNull(source, "source");
        }

        @Override
        public byte[] pngBytes() {
            return pngBytes.clone();
        }
    }

    private record ExistingPersonalSkin(String sha256, byte[] pngBytes) {
        private ExistingPersonalSkin {
            Objects.requireNonNull(sha256, "sha256");
            pngBytes = Objects.requireNonNull(pngBytes, "pngBytes").clone();
        }

        @Override
        public byte[] pngBytes() {
            return pngBytes.clone();
        }
    }
}

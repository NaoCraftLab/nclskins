package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.SkinCatalogSource;
import com.naocraftlab.skins.core.importing.ExternalAppearanceRecord;
import com.naocraftlab.skins.core.importing.ExternalImportAdapter;
import com.naocraftlab.skins.core.importing.ExternalImportBatch;
import com.naocraftlab.skins.core.importing.ExternalImportContext;
import com.naocraftlab.skins.core.importing.ExternalImportProbe;
import com.naocraftlab.skins.core.importing.ExternalImportSource;
import com.naocraftlab.skins.core.importing.SkinLocator;
import com.naocraftlab.skins.core.model.PersonalSkinSource;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.png.NormalizedSkin;
import com.naocraftlab.skins.core.png.PngValidator;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import static com.naocraftlab.skins.runtime.ExternalImportSourceAccess.*;

public final class ExternalImportSourceAdapter implements ExternalImportSourceAccess {
    private final PublicSkinImportService publicImports;
    private final SkinCatalogSource resources;
    private final PngValidator pngValidator;
    private final Map<ExternalImportSource, ExternalImportAdapter> adapters;

    public ExternalImportSourceAdapter(
            PublicSkinImportService publicImports,
            SkinCatalogSource resources) {
        this(publicImports, resources, new PngValidator(), List.of(
                new MinecraftLauncherImportAdapter(),
                new CurseForgeAppImportAdapter(),
                new ModrinthAppImportAdapter(),
                new SkinShuffleImportAdapter(),
                new SkinSwapperFamilyImportAdapter(),
                new QuickSkinImportAdapter(),
                new PrismLauncherImportAdapter()));
    }

    ExternalImportSourceAdapter(
            PublicSkinImportService publicImports,
            SkinCatalogSource resources,
            PngValidator pngValidator,
            List<ExternalImportAdapter> adapters) {
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

    @Override public ExternalImportProbe probe(
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

    @Override public ExternalImportBatch discover(ExternalImportSource source, Optional<Path> selectedRoot,
            ExternalImportContext context) throws Exception {
        if (source.requiresSqlite() && !SqliteSupport.available()) {
            throw new ExternalImportException(ExternalImportException.Code.DEPENDENCY_MISSING,
                    "Minecraft SQLite JDBC is unavailable");
        }
        ExternalImportAdapter adapter = Optional.ofNullable(adapters.get(source))
                .orElseThrow(() -> new ExternalImportException(ExternalImportException.Code.NOT_FOUND,
                        "External import adapter is unavailable"));
        return discover(adapter, source, selectedRoot, context);
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

    @Override public Resolution resolve(ExternalAppearanceRecord record) throws Exception {
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
            ImportOperations.ImportDraft draft = publicImports.loadUrl(remote.url());
            return normalized(
                    draft.pngBytes(), PersonalSkinSource.URL, Optional.of(draft.variant()));
        }
        if (locator instanceof SkinLocator.PublicPlayer player) {
            ImportOperations.ImportDraft draft = publicImports.loadPlayer(player.nameOrUuid());
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
        NormalizedSkin skin = pngValidator.projectImport(png);
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
        if (source == ExternalImportSource.SKIN_SHUFFLE
                || source == ExternalImportSource.SKIN_SWAPPER_FAMILY
                || source == ExternalImportSource.QUICK_SKIN) {
            return List.of(context.currentGameDirectory());
        }
        if (os.contains("win")) {
            Path appData = optionalPath(environment.get("APPDATA")).orElse(home);
            return List.of(appData.resolve(switch (source) {
                case MINECRAFT_LAUNCHER -> ".minecraft";
                case CURSEFORGE_APP -> "CurseForge";
                case MODRINTH_APP -> "ModrinthApp";
                case PRISM_LAUNCHER -> "PrismLauncher";
                case SKIN_SHUFFLE, SKIN_SWAPPER_FAMILY, QUICK_SKIN -> throw new IllegalStateException("handled above");
            }));
        }
        if (os.contains("mac")) {
            Path applicationSupport = home.resolve("Library").resolve("Application Support");
            return List.of(applicationSupport.resolve(switch (source) {
                case MINECRAFT_LAUNCHER -> "minecraft";
                case CURSEFORGE_APP -> "CurseForge";
                case MODRINTH_APP -> "ModrinthApp";
                case PRISM_LAUNCHER -> "PrismLauncher";
                case SKIN_SHUFFLE, SKIN_SWAPPER_FAMILY, QUICK_SKIN -> throw new IllegalStateException("handled above");
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

}

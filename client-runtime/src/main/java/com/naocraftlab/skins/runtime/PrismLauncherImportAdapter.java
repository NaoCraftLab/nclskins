package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.importing.ExternalAppearanceRecord;
import com.naocraftlab.skins.core.importing.ExternalImportAdapter;
import com.naocraftlab.skins.core.importing.ExternalImportBatch;
import com.naocraftlab.skins.core.importing.ExternalImportContext;
import com.naocraftlab.skins.core.importing.ExternalImportSource;
import com.naocraftlab.skins.core.importing.SkinLocator;
import com.naocraftlab.skins.core.model.SkinVariant;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;


public final class PrismLauncherImportAdapter implements ExternalImportAdapter {
    @Override
    public ExternalImportSource source() {
        return ExternalImportSource.PRISM_LAUNCHER;
    }

    @Override
    public boolean probe(Path root, ExternalImportContext context) {
        try {
            Path skins = findSkinsDirectory(root.toAbsolutePath().normalize());
            if (Files.isRegularFile(skins.resolve("index.json"))) {
                return true;
            }
            if (!Files.isDirectory(skins)) {
                return false;
            }
            try (DirectoryStream<Path> files = Files.newDirectoryStream(skins, "*.png")) {
                return files.iterator().hasNext();
            }
        } catch (IOException | RuntimeException failure) {
            return false;
        }
    }

    @Override
    public ExternalImportBatch discover(Path root, ExternalImportContext context) throws IOException {
        Path skins = findSkinsDirectory(root.toAbsolutePath().normalize());
        Path index = skins.resolve("index.json");
        List<ExternalAppearanceRecord> records = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Set<String> indexedFiles = new HashSet<>();
        int order = 0;

        if (Files.isRegularFile(index)) {
            Map<String, Object> object = ExternalImportFiles.readJsonObject(index);
            Optional<List<Object>> indexed = ExternalImportFiles.array(object, "skins");
            if (indexed.isPresent()) {
                for (Object element : indexed.orElseThrow()) {
                    if (order >= ExternalImportFiles.MAX_RECORDS) {
                        warnings.add("record_limit");
                        break;
                    }
                    int sourceOrder = order++;
                    Optional<Map<String, Object>> parsedEntry = ExternalImportFiles.asObject(element);
                    if (parsedEntry.isEmpty()) {
                        warnings.add("malformed_record");
                        continue;
                    }
                    Map<String, Object> entry = parsedEntry.orElseThrow();
                    Optional<String> name = ExternalImportFiles.string(entry, "name");
                    if (name.isEmpty()) {
                        warnings.add("missing_name");
                        continue;
                    }
                    Optional<Path> png = ExternalImportFiles.safeChild(skins, name.orElseThrow() + ".png");
                    if (png.isEmpty()) {
                        warnings.add("unsafe_path");
                        continue;
                    }
                    indexedFiles.add(png.orElseThrow().getFileName().toString());
                    Optional<SkinVariant> variant = ExternalImportFiles.string(entry, "model")
                            .flatMap(ExternalImportFiles::variant);
                    SkinLocator locator;
                    if (Files.isRegularFile(png.orElseThrow(), LinkOption.NOFOLLOW_LINKS)) {
                        locator = new SkinLocator.LocalPng(png.orElseThrow());
                    } else if (ExternalImportFiles.string(entry, "url").isPresent()) {
                        locator = new SkinLocator.PublicUrl(
                                ExternalImportFiles.string(entry, "url").orElseThrow(), Optional.empty());
                    } else {
                        warnings.add("missing_skin_image");
                        continue;
                    }
                    records.add(new ExternalAppearanceRecord(
                            "prism-" + sourceOrder,
                            name.orElseThrow(),
                            variant,
                            locator,
                            ExternalImportFiles.string(entry, "capeId"),
                            sourceOrder));
                }
            }
        }

        if (Files.isDirectory(skins) && order < ExternalImportFiles.MAX_RECORDS) {
            List<Path> unindexed = new ArrayList<>();
            int remaining = ExternalImportFiles.MAX_RECORDS - order;
            try (DirectoryStream<Path> files = Files.newDirectoryStream(skins)) {
                for (Path path : files) {
                    String fileName = path.getFileName().toString();
                    if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                            && fileName.toLowerCase().endsWith(".png")
                            && !indexedFiles.contains(fileName)) {
                        if (unindexed.size() >= remaining) {
                            warnings.add("record_limit");
                            break;
                        }
                        unindexed.add(path);
                    }
                }
            }
            unindexed.sort((left, right) -> left.getFileName().toString()
                    .compareToIgnoreCase(right.getFileName().toString()));
            for (Path png : unindexed) {
                String fileName = png.getFileName().toString();
                String name = fileName.substring(0, fileName.length() - 4);
                records.add(new ExternalAppearanceRecord(
                        "prism-" + order,
                        name,
                        Optional.of(SkinVariant.CLASSIC),
                        new SkinLocator.LocalPng(png),
                        Optional.empty(),
                        order++));
            }
        }
        if (!Files.isDirectory(skins) && !Files.isRegularFile(index)) {
            throw new IOException("Prism Launcher skin library was not found");
        }
        return new ExternalImportBatch(source(), records, warnings);
    }

    private static Path findSkinsDirectory(Path root) throws IOException {
        if (Files.isRegularFile(root.resolve("index.json"))) {
            return root;
        }
        Path config = root.resolve("prismlauncher.cfg");
        if (Files.isRegularFile(config)) {
            List<String> lines = ExternalImportFiles.readBoundedText(config).lines().toList();
            for (String line : lines) {
                if (line.startsWith("SkinsDir=")) {
                    String configured = line.substring("SkinsDir=".length()).trim();
                    if (!configured.isEmpty()) {
                        Path path = Path.of(configured);
                        return path.isAbsolute() ? path.normalize() : root.resolve(path).normalize();
                    }
                }
            }
        }
        Path conventional = root.resolve("skins");
        return Files.isDirectory(conventional) || Files.isRegularFile(conventional.resolve("index.json"))
                ? conventional
                : root;
    }
}

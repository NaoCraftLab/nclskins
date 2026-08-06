package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.importing.ExternalAppearanceRecord;
import com.naocraftlab.skins.core.importing.ExternalImportAdapter;
import com.naocraftlab.skins.core.importing.ExternalImportBatch;
import com.naocraftlab.skins.core.importing.ExternalImportContext;
import com.naocraftlab.skins.core.importing.ExternalImportSource;
import com.naocraftlab.skins.core.importing.SkinLocator;
import com.naocraftlab.skins.core.model.SkinVariant;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;


public final class CurseForgeAppImportAdapter implements ExternalImportAdapter {
    private static final String TABLE = "minecraft_custom_skins";
    private static final Set<String> REQUIRED_COLUMNS = Set.of(
            "uuid", "name", "url", "data", "addedat");

    @Override
    public ExternalImportSource source() {
        return ExternalImportSource.CURSEFORGE_APP;
    }

    @Override
    public boolean probe(Path root, ExternalImportContext context) throws IOException {
        Path database = findDatabase(root);
        if (database == null) {
            return false;
        }
        try (Connection connection = ExternalImportSqlite.openReadOnly(database)) {
            return supportedSchema(connection);
        } catch (SQLException failure) {
            throw new IOException("CurseForge App database could not be inspected", failure);
        }
    }

    @Override
    public ExternalImportBatch discover(Path root, ExternalImportContext context) throws IOException {
        Path database = findDatabase(root);
        if (database == null) {
            throw new IOException("CurseForge App app.db was not found");
        }
        try (Connection connection = ExternalImportSqlite.openReadOnly(database)) {
            if (!supportedSchema(connection)) {
                throw new IOException("CurseForge App skin database has an unsupported format");
            }
            Set<String> columns = ExternalImportSqlite.columns(connection, quote(TABLE));
            boolean hasVariant = columns.contains("variant");
            String sql = "SELECT Name AS name, Url AS url, Data AS data, "
                    + (hasVariant ? "Variant" : "'CLASSIC'")
                    + " AS variant FROM " + quote(TABLE)
                    + " WHERE REPLACE(LOWER(Uuid), '-', '') = ? "
                    + "ORDER BY AddedAt DESC, rowid DESC LIMIT ?";
            List<ExternalAppearanceRecord> records = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, ExternalImportSqlite.normalizedUuid(context.profileId()));
                statement.setInt(2, ExternalImportFiles.MAX_RECORDS + 1);
                try (ResultSet result = statement.executeQuery()) {
                    int sourceOrder = 0;
                    while (result.next()) {
                        if (sourceOrder >= ExternalImportFiles.MAX_RECORDS) {
                            warnings.add("record_limit");
                            break;
                        }
                        int recordOrder = sourceOrder++;
                        Optional<byte[]> png;
                        try {
                            png = ExternalImportSqlite.readPngBlob(result, "data");
                        } catch (IOException invalidBlob) {
                            warnings.add("invalid_skin_image");
                            continue;
                        }
                        SkinLocator locator;
                        if (png.isPresent()) {
                            locator = new SkinLocator.EmbeddedPng(png.orElseThrow());
                        } else {
                            String url = bounded(result.getString("url"), 2048);
                            if (url == null) {
                                warnings.add("missing_skin_image");
                                continue;
                            }
                            locator = new SkinLocator.PublicUrl(url, Optional.empty());
                        }
                        String rawVariant = result.getString("variant");
                        Optional<SkinVariant> variant = ExternalImportFiles.variant(rawVariant);
                        if (rawVariant != null && variant.isEmpty()) {
                            warnings.add("unknown_model");
                        }
                        String fallback = "CurseForge skin " + (recordOrder + 1);
                        String displayName = bounded(result.getString("name"), 512);
                        records.add(new ExternalAppearanceRecord(
                                "curseforge-" + recordOrder,
                                displayName == null ? fallback : displayName,
                                variant,
                                locator,
                                Optional.empty(),
                                recordOrder));
                    }
                }
            }
            return new ExternalImportBatch(source(), records, warnings);
        } catch (SQLException failure) {
            throw new IOException("CurseForge App skins could not be read", failure);
        }
    }

    private static boolean supportedSchema(Connection connection) throws SQLException {
        return ExternalImportSqlite.tableExists(connection, TABLE)
                && ExternalImportSqlite.columns(connection, quote(TABLE)).containsAll(REQUIRED_COLUMNS);
    }

    private static Path findDatabase(Path root) {
        Path normalized = root.toAbsolutePath().normalize();
        if (Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            return normalized.getFileName() != null
                    && "app.db".equalsIgnoreCase(normalized.getFileName().toString())
                    ? normalized : null;
        }
        for (Path candidate : List.of(
                normalized.resolve("app.db"),
                normalized.resolve("database").resolve("app.db"),
                normalized.resolve("agent").resolve("database").resolve("app.db"))) {
            if (Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return candidate;
            }
        }
        return null;
    }

    private static String bounded(String value, int maximum) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() || trimmed.length() > maximum ? null : trimmed;
    }

    private static String quote(String identifier) {
        return '"' + identifier + '"';
    }
}

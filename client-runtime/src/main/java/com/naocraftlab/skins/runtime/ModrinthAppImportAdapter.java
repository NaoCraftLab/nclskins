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


public final class ModrinthAppImportAdapter implements ExternalImportAdapter {
    private static final String SKINS_TABLE = "custom_minecraft_skins";
    private static final String TEXTURES_TABLE = "custom_minecraft_skin_textures";
    private static final Set<String> REQUIRED_SKIN_COLUMNS = Set.of(
            "minecraft_user_uuid", "texture_key", "variant", "cape_id");
    private static final Set<String> REQUIRED_TEXTURE_COLUMNS = Set.of("texture_key", "texture");

    @Override
    public ExternalImportSource source() {
        return ExternalImportSource.MODRINTH_APP;
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
            throw new IOException("Modrinth App database could not be inspected", failure);
        }
    }

    @Override
    public ExternalImportBatch discover(Path root, ExternalImportContext context) throws IOException {
        Path database = findDatabase(root);
        if (database == null) {
            throw new IOException("Modrinth App app.db was not found");
        }
        try (Connection connection = ExternalImportSqlite.openReadOnly(database)) {
            if (!supportedSchema(connection)) {
                throw new IOException("Modrinth App skin database has an unsupported format");
            }
            Set<String> columns = ExternalImportSqlite.columns(connection, quote(SKINS_TABLE));
            boolean ordered = columns.contains("display_order");
            String sql = "SELECT s.variant AS variant, s.cape_id AS cape_id, "
                    + "t.texture AS texture FROM " + quote(SKINS_TABLE) + " s JOIN "
                    + quote(TEXTURES_TABLE) + " t ON t.texture_key = s.texture_key "
                    + "WHERE REPLACE(LOWER(s.minecraft_user_uuid), '-', '') = ? ORDER BY "
                    + (ordered ? "s.display_order ASC, " : "")
                    + "s.rowid ASC LIMIT ?";
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
                            png = ExternalImportSqlite.readPngBlob(result, "texture");
                        } catch (IOException invalidBlob) {
                            warnings.add("invalid_skin_image");
                            continue;
                        }
                        if (png.isEmpty()) {
                            warnings.add("missing_skin_image");
                            continue;
                        }
                        String rawVariant = result.getString("variant");
                        Optional<SkinVariant> variant = ExternalImportFiles.variant(rawVariant);
                        if (rawVariant != null && variant.isEmpty()) {
                            warnings.add("unknown_model");
                        }
                        records.add(new ExternalAppearanceRecord(
                                "modrinth-" + recordOrder,
                                "Modrinth skin " + (recordOrder + 1),
                                variant,
                                new SkinLocator.EmbeddedPng(png.orElseThrow()),
                                Optional.ofNullable(result.getString("cape_id")),
                                recordOrder));
                    }
                }
            }
            return new ExternalImportBatch(source(), records, warnings);
        } catch (SQLException failure) {
            throw new IOException("Modrinth App skins could not be read", failure);
        }
    }

    private static boolean supportedSchema(Connection connection) throws SQLException {
        return ExternalImportSqlite.tableExists(connection, SKINS_TABLE)
                && ExternalImportSqlite.tableExists(connection, TEXTURES_TABLE)
                && ExternalImportSqlite.columns(connection, quote(SKINS_TABLE))
                .containsAll(REQUIRED_SKIN_COLUMNS)
                && ExternalImportSqlite.columns(connection, quote(TEXTURES_TABLE))
                .containsAll(REQUIRED_TEXTURE_COLUMNS);
    }

    private static Path findDatabase(Path root) {
        Path normalized = root.toAbsolutePath().normalize();
        if (Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            return normalized.getFileName() != null
                    && "app.db".equalsIgnoreCase(normalized.getFileName().toString())
                    ? normalized : null;
        }
        Path candidate = normalized.resolve("app.db");
        return Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS) ? candidate : null;
    }

    private static String quote(String identifier) {
        return '"' + identifier + '"';
    }
}

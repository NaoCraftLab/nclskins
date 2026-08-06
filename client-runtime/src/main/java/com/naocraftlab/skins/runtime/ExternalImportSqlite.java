package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.png.PngValidator;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;


final class ExternalImportSqlite {
    private ExternalImportSqlite() {
    }

    static Connection openReadOnly(Path database) throws IOException {
        Path normalized = database.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("External import database is unavailable");
        }
        String uri = normalized.toUri().toASCIIString();
        String separator = uri.contains("?") ? "&" : "?";
        Path wal = normalized.resolveSibling(normalized.getFileName() + "-wal");
        try {
            return connectReadOnly(uri + separator + "mode=ro");
        } catch (SQLException standardFailure) {
            if (Files.exists(wal, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException(
                        "External import database could not be opened read-only", standardFailure);
            }
            try {
                return connectReadOnly(uri + separator + "mode=ro&immutable=1");
            } catch (SQLException immutableFailure) {
                immutableFailure.addSuppressed(standardFailure);
                throw new IOException(
                        "External import database could not be opened read-only", immutableFailure);
            }
        }
    }

    private static Connection connectReadOnly(String uri) throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + uri);
        try {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA query_only=ON");
            }
            return connection;
        } catch (SQLException failure) {
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    static boolean tableExists(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    static Set<String> columns(Connection connection, String quotedTable) throws SQLException {
        Set<String> columns = new HashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA table_info(" + quotedTable + ")")) {
            while (result.next()) {
                columns.add(result.getString("name").toLowerCase(Locale.ROOT));
            }
        }
        return Set.copyOf(columns);
    }

    static Optional<byte[]> readPngBlob(ResultSet result, String column)
            throws SQLException, IOException {
        try (InputStream input = result.getBinaryStream(column)) {
            if (input == null) {
                return Optional.empty();
            }
            byte[] bytes = input.readNBytes(PngValidator.DEFAULT_MAX_BYTES + 1);
            if (bytes.length == 0) {
                return Optional.empty();
            }
            if (bytes.length > PngValidator.DEFAULT_MAX_BYTES) {
                throw new IOException("External skin BLOB exceeds the PNG limit");
            }
            return Optional.of(bytes);
        }
    }

    static String normalizedUuid(java.util.UUID uuid) {
        return uuid.toString().replace("-", "").toLowerCase(Locale.ROOT);
    }
}

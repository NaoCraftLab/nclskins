package com.naocraftlab.skins.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SqliteSupportTest {
    @Test
    void availabilityProbeAcceptsTheOptionalRuntimeDriver() {
        assertTrue(SqliteSupport.available());
    }

    @Test
    void availabilityProbeDoesNotRequireTheOptionalDriverAtClassLoadTime() throws Exception {
        URL runtimeClasses = SqliteSupport.class.getProtectionDomain()
                .getCodeSource()
                .getLocation();
        try (URLClassLoader withoutSqlite = new URLClassLoader(
                new URL[] {runtimeClasses}, ClassLoader.getPlatformClassLoader())) {
            Class<?> isolatedSupport = Class.forName(
                    SqliteSupport.class.getName(), true, withoutSqlite);
            Method available = isolatedSupport.getDeclaredMethod("available");
            available.setAccessible(true);
            assertFalse((Boolean) available.invoke(null));
        }
    }

    @Test
    void importedDatabaseConnectionRejectsWrites(@TempDir Path root) throws Exception {
        Path database = root.resolve("app.db");
        Class.forName("org.sqlite.JDBC");
        try (Connection writable = DriverManager.getConnection(
                "jdbc:sqlite:" + database.toAbsolutePath());
             Statement statement = writable.createStatement()) {
            statement.execute("CREATE TABLE imported_skin(id TEXT)");
        }

        try (Connection readOnly = ExternalImportSqlite.openReadOnly(database);
             Statement statement = readOnly.createStatement()) {
            assertTrue(readOnly.isReadOnly() || queryOnly(statement));
            assertThrows(SQLException.class,
                    () -> statement.execute("INSERT INTO imported_skin VALUES ('mutated')"));
        }
    }

    private static boolean queryOnly(Statement statement) throws SQLException {
        try (var result = statement.executeQuery("PRAGMA query_only")) {
            return result.next() && result.getInt(1) == 1;
        }
    }
}

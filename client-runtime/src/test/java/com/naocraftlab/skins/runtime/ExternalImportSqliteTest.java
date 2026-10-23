package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.importing.*;
import com.naocraftlab.skins.core.png.PngValidationException;
import com.naocraftlab.skins.core.png.PngValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class ExternalImportSqliteTest {
    @Test
    void publicDriverBytesAreMaterializedAndRawInvalidBytesRemainDiscoverable() throws Exception {
        byte[] returned = {1, 2, 3};
        AtomicInteger reads = new AtomicInteger();
        ResultSet result = result(() -> { reads.incrementAndGet(); return returned; });
        assertSame(returned, ExternalImportSqlite.readPngBlob(result, "data").orElseThrow());
        assertEquals(1, reads.get());
        assertTrue(ExternalImportSqlite.readPngBlob(result(() -> null), "data").isEmpty());
        assertTrue(ExternalImportSqlite.readPngBlob(result(() -> new byte[0]), "data").isEmpty());
    }

    @Test
    void inclusiveSharedLengthAdmissionDoesNotAllocateThePolicyMaximum() throws Exception {
        assertEquals(1_140_850_688L, PngValidator.DEFAULT_MAX_BYTES);
        ExternalImportSqlite.requireBlobLength(PngValidator.DEFAULT_MAX_BYTES);
        assertThrows(IOException.class, () -> ExternalImportSqlite.requireBlobLength(PngValidator.DEFAULT_MAX_BYTES + 1));
        assertThrows(IOException.class, () -> ExternalImportSqlite.requireBlobLength(-1));
        assertArrayEquals(new byte[]{7}, ExternalImportSqlite.readPngBlob(result(() -> new byte[]{7}), "data").orElseThrow());
    }

    @Test
    void cancellationBeforeAndAfterDriverReadRejectsLateBytes() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        try {
            Thread.currentThread().interrupt();
            assertThrows(InterruptedIOException.class, () -> ExternalImportSqlite.readPngBlob(
                    result(() -> { reads.incrementAndGet(); return new byte[]{1}; }), "data"));
            assertEquals(0, reads.get());
            Thread.interrupted();
            assertThrows(InterruptedIOException.class, () -> ExternalImportSqlite.readPngBlob(result(() -> {
                reads.incrementAndGet(); Thread.currentThread().interrupt(); return new byte[]{1};
            }), "data"));
            assertEquals(1, reads.get());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    @Test
    void driverFailureKeepsTheExistingSqlFailureOutcome() {
        ResultSet failed = (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ResultSet.class},
                (proxy, method, arguments) -> { throw new java.sql.SQLException("fixture failure"); });
        assertThrows(java.sql.SQLException.class, () -> ExternalImportSqlite.readPngBlob(failed, "data"));
    }

    @Test
    void modrinthWithoutRowidTexturesKeepPreviousQuerySupportAndCloseBeforeReview(@TempDir Path directory) throws Exception {
        UUID profile = UUID.randomUUID();
        Path database = directory.resolve("app.db");
        byte[] png = PreparedCatalogServiceTest.png(64, 64, 0);
        try (Connection writer = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = writer.createStatement()) {
            statement.execute("CREATE TABLE custom_minecraft_skin_textures(texture_key TEXT PRIMARY KEY, texture BLOB) WITHOUT ROWID");
            statement.execute("CREATE TABLE custom_minecraft_skins(minecraft_user_uuid TEXT, texture_key TEXT, variant TEXT, cape_id TEXT)");
            try (var insert = writer.prepareStatement("INSERT INTO custom_minecraft_skin_textures VALUES (?, ?)")) {
                insert.setString(1, "valid"); insert.setBytes(2, png); insert.executeUpdate();
                insert.setString(1, "invalid"); insert.setBytes(2, new byte[]{1}); insert.executeUpdate();
            }
            try (var insert = writer.prepareStatement("INSERT INTO custom_minecraft_skins VALUES (?, ?, 'CLASSIC', NULL)")) {
                insert.setString(1, profile.toString());
                insert.setString(2, "valid"); insert.executeUpdate();
                insert.setString(2, "invalid"); insert.executeUpdate();
            }
        }
        var context = new ExternalImportContext(profile, "Fixture", directory);
        var importer = new ModrinthAppImportAdapter();
        assertTrue(importer.probe(database, context));
        var batch = importer.discover(database, context);
        assertEquals(2, batch.records().size());
        assertInstanceOf(SkinLocator.EmbeddedPng.class, batch.records().get(0).skinLocator());
        Files.delete(database);
        var preparation = new ExternalImportSourceAdapter(new PublicSkinImports() {
            public ImportOperations.AcquiredInput acquireUrl(String url, Optional<com.naocraftlab.skins.client.CpmModelFileConversion> converter,
                    com.naocraftlab.skins.client.CpmModelFileConversion.Reason unavailable) throws Exception {
                return new ImportOperations.AcquiredSkin(loadUrl(url));
            }
            public ImportOperations.AcquiredInput acquireFile(Path path, Optional<com.naocraftlab.skins.client.CpmModelFileConversion> converter,
                    com.naocraftlab.skins.client.CpmModelFileConversion.Reason unavailable) throws Exception {
                return PublicSkinImportService.admitInput(com.naocraftlab.skins.core.api.PublicSkinImageAdapter.acquireLocal(path),
                        converter, unavailable, "Imported skin", com.naocraftlab.skins.core.model.PersonalSkinSource.FILE);
            }
            @Override public ImportOperations.ImportDraft loadPlayer(String query) { throw new AssertionError(); }
            @Override public ImportOperations.ImportDraft loadUrl(String url) { throw new AssertionError(); }
        }, (collection, skin, model) -> { throw new AssertionError(); }, new PngValidator(), List.of());
        assertArrayEquals(new PngValidator().projectImport(png).pngBytes(), preparation.resolve(batch.records().get(0)).pngBytes());
        assertThrows(PngValidationException.class, () -> preparation.resolve(batch.records().get(1)));
    }

    @Test
    void curseForgeTiesMissingEmptyAndForeignRowsKeepSourceOrder(@TempDir Path directory) throws Exception {
        UUID profile = UUID.randomUUID();
        Path database = directory.resolve("app.db");
        byte[] png = PreparedCatalogServiceTest.png(64, 64, 0);
        try (Connection writer = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = writer.createStatement()) {
            statement.execute("CREATE TABLE minecraft_custom_skins(Id TEXT, Uuid TEXT, Name TEXT, Url TEXT, Data BLOB, AddedAt INTEGER, Variant TEXT)");
            try (var insert = writer.prepareStatement("INSERT INTO minecraft_custom_skins VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                Object[][] rows = {
                    {"first", "First", "", png, 5, "CLASSIC"},
                    {"second", "Second", "", png, 5, "SLIM"},
                    {"missing", "Missing", "", null, 6, "CLASSIC"},
                    {"empty", "", "https://example.com/fixture.png", new byte[0], 5, "CLASSIC"},
                    {"raw", "Raw", "https://example.com/ignored.png", new byte[]{1}, 4, "MYSTERY"}
                };
                for (Object[] row : rows) {
                    insert.setString(1, row[0].toString()); insert.setString(2, profile.toString());
                    insert.setString(3, row[1].toString()); insert.setString(4, row[2].toString());
                    insert.setBytes(5, (byte[]) row[3]); insert.setInt(6, (Integer) row[4]);
                    insert.setString(7, row[5].toString()); insert.executeUpdate();
                }
                insert.setString(1, "foreign"); insert.setString(2, UUID.randomUUID().toString());
                insert.setString(3, "Foreign"); insert.setBytes(5, png); insert.setInt(6, 100); insert.executeUpdate();
            }
        }
        var batch = new CurseForgeAppImportAdapter().discover(database, new ExternalImportContext(profile, "Fixture", directory));
        assertEquals(List.of(1, 2, 3, 4), batch.records().stream().map(ExternalAppearanceRecord::sourceOrder).toList());
        assertEquals(List.of("CurseForge skin 2", "Second", "First", "Raw"), batch.records().stream().map(ExternalAppearanceRecord::displayName).toList());
        assertInstanceOf(SkinLocator.PublicUrl.class, batch.records().get(0).skinLocator());
        assertArrayEquals(new byte[]{1}, ((SkinLocator.EmbeddedPng) batch.records().get(3).skinLocator()).pngBytes());
        assertEquals(List.of("missing_skin_image", "unknown_model"), batch.warnings());
    }

    @Test
    void jdbcConnectionsCloseAfterProbeDiscoveryAndSchemaFailure(@TempDir Path directory) throws Exception {
        Path database = directory.resolve("app.db");
        try (Connection writer = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = writer.createStatement()) {
            statement.execute("CREATE TABLE minecraft_custom_skins(Id TEXT, Uuid TEXT, Name TEXT, Url TEXT, Data BLOB, AddedAt INTEGER, Variant TEXT)");
        }
        java.sql.Driver delegate = DriverManager.getDriver("jdbc:sqlite::memory:");
        AtomicInteger opened = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        java.sql.Driver counting = new java.sql.Driver() {
            @Override public Connection connect(String url, java.util.Properties properties) throws java.sql.SQLException {
                if (!acceptsURL(url)) return null;
                Connection connection = delegate.connect(url, properties);
                opened.incrementAndGet();
                return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                        (proxy, method, arguments) -> {
                            if (method.getName().equals("close")) closed.incrementAndGet();
                            try { return method.invoke(connection, arguments); }
                            catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                        });
            }
            @Override public boolean acceptsURL(String url) throws java.sql.SQLException { return delegate.acceptsURL(url); }
            @Override public java.sql.DriverPropertyInfo[] getPropertyInfo(String url, java.util.Properties info) throws java.sql.SQLException { return delegate.getPropertyInfo(url, info); }
            @Override public int getMajorVersion() { return delegate.getMajorVersion(); }
            @Override public int getMinorVersion() { return delegate.getMinorVersion(); }
            @Override public boolean jdbcCompliant() { return delegate.jdbcCompliant(); }
            @Override public java.util.logging.Logger getParentLogger() throws java.sql.SQLFeatureNotSupportedException { return delegate.getParentLogger(); }
        };
        DriverManager.deregisterDriver(delegate);
        DriverManager.registerDriver(counting);
        try {
            var importer = new CurseForgeAppImportAdapter();
            var context = new ExternalImportContext(UUID.randomUUID(), "Fixture", directory);
            assertTrue(importer.probe(database, context));
            assertTrue(importer.discover(database, context).records().isEmpty());
            try (Connection writer = delegate.connect("jdbc:sqlite:" + database, new java.util.Properties());
                 var statement = writer.createStatement()) {
                statement.execute("DROP TABLE minecraft_custom_skins");
            }
            assertThrows(IOException.class, () -> importer.discover(database, context));
            assertEquals(3, opened.get());
            assertEquals(opened.get(), closed.get());
            try {
                Thread.currentThread().interrupt();
                assertThrows(InterruptedIOException.class, () -> importer.discover(database, context));
                assertEquals(3, opened.get());
            } finally { Thread.interrupted(); }
        } finally {
            DriverManager.deregisterDriver(counting);
            DriverManager.registerDriver(delegate);
        }
    }

    private static ResultSet result(java.util.function.Supplier<byte[]> bytes) {
        return (ResultSet) Proxy.newProxyInstance(ExternalImportSqliteTest.class.getClassLoader(), new Class<?>[]{ResultSet.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("getBytes")) return bytes.get();
                    throw new AssertionError("Unexpected driver API: " + method.getName());
                });
    }
}

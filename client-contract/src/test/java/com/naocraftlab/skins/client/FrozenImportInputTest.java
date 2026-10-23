package com.naocraftlab.skins.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class FrozenImportInputTest {
    private static final byte[] PNG = {(byte)137,80,78,71,13,10,26,10,1,2,3};

    @Test void readerOutlivesOwnerButRetiresExactOwnedFile(@TempDir Path directory) throws Exception {
        Path source = Files.write(directory.resolve("source.bin"), PNG);
        var frozen = FrozenImportInput.capture(source);
        var field = FrozenImportInput.class.getDeclaredField("path"); field.setAccessible(true);
        Path owned = (Path) field.get(frozen);
        var reader = frozen.open();
        Files.write(source, new byte[]{5});
        frozen.close();
        assertTrue(Files.exists(owned));
        assertArrayEquals(PNG, reader.readAllBytes());
        assertThrows(IOException.class, frozen::open);
        reader.close(); reader.close(); frozen.close();
        assertFalse(Files.exists(owned));
        assertArrayEquals(new byte[]{5}, Files.readAllBytes(source));
    }

    @Test void successfulCaptureWithThrowingSourceCloseRetiresItsStaging() throws Exception {
        Path directory = Path.of(System.getProperty("java.io.tmpdir"));
        java.util.Set<Path> before;
        try (var files = Files.list(directory)) {
            before = files.filter(p -> p.getFileName().toString().startsWith("nclskins-import-")).collect(java.util.stream.Collectors.toSet());
        }
        assertThrows(IOException.class, () -> FrozenImportInput.capture(new ByteArrayInputStream(PNG) {
            @Override public void close() throws IOException { throw new IOException("synthetic close"); }
        }));
        java.util.Set<Path> after;
        try (var files = Files.list(directory)) {
            after = files.filter(p -> p.getFileName().toString().startsWith("nclskins-import-")).collect(java.util.stream.Collectors.toSet());
        }
        assertEquals(before, after, "Failed ownership transfer must not leak staging");
    }

    @Test void invalidSignatureRemainsPrimaryWhenCloseFails() {
        var failure = assertThrows(FrozenImportInput.Rejected.class, () -> FrozenImportInput.capture(
                new ByteArrayInputStream(new byte[]{1,2,3}) {
                    @Override public void close() throws IOException { throw new IOException("synthetic close"); }
                }));
        assertEquals(FrozenImportInput.Rejection.INVALID, failure.reason());
        assertEquals(1, failure.getSuppressed().length);
    }
}

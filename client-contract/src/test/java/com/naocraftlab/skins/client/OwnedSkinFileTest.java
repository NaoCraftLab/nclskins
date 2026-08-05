package com.naocraftlab.skins.client;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OwnedSkinFileTest {
    @Test
    void ownsAnExactTemporaryCopyUntilClosed() throws Exception {
        byte[] bytes = new byte[]{1, 2, 3, 4};
        OwnedSkinFile staged = OwnedSkinFile.stage("a".repeat(64), bytes);
        Path path = staged.path();
        Path directory = path.getParent();

        assertTrue(Files.isRegularFile(path));
        assertArrayEquals(bytes, Files.readAllBytes(path));

        staged.close();

        assertFalse(Files.exists(path));
        assertFalse(Files.exists(directory));
        staged.close();
        assertThrows(IllegalStateException.class, staged::path);
    }
}

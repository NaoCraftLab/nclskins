package com.naocraftlab.skins.core.resourcepack;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;


final class ResourcePackCollectionIndexTest {
    @Test
    void readsCollectionNamespacesInDeclarationOrder() throws Exception {
        assertEquals(
                List.of("summer_event", "winter-event"),
                read("[\"summer_event\",\"winter-event\"]"));
    }

    @Test
    void rejectsUnsupportedRootsEntriesAndIds() {
        assertThrows(IOException.class, () -> read("{\"collections\":[]}"));
        assertThrows(IOException.class, () -> read("[\"valid\",7]"));
        assertThrows(IOException.class, () -> read("[\"invalid.collection\"]"));
        assertThrows(IOException.class, () -> read("[\"minecraft\"]"));
        assertThrows(IOException.class, () -> read("[\"summer\",\"summer\"]"));
        assertThrows(IOException.class, () -> read("[] trailing"));
    }

    @Test
    void boundsIndexBytesAndCollectionCount() {
        byte[] oversized = new byte[ResourcePackCollectionIndex.MAX_BYTES + 1];
        assertThrows(
                IOException.class,
                () -> ResourcePackCollectionIndex.read(new ByteArrayInputStream(oversized)));

        String tooMany = "[\"entry\"" + ",\"entry\"".repeat(
                ResourcePackCollectionIndex.MAX_COLLECTIONS) + "]";
        assertThrows(IOException.class, () -> read(tooMany));
    }

    private static List<String> read(String json) throws IOException {
        return ResourcePackCollectionIndex.read(new ByteArrayInputStream(
                json.getBytes(StandardCharsets.UTF_8)));
    }
}

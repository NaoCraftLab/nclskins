package com.naocraftlab.skins.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ServerAppearanceRefreshCommandPathTest {
    @Test
    void acceptsOnlyAnExactExecutableZeroArgumentLiteralLeaf() {
        assertTrue(ServerAppearanceRefreshCommandPath.isExactExecutableLeaf(
                true, true, true, true, true, false));

        assertFalse(ServerAppearanceRefreshCommandPath.isExactExecutableLeaf(
                false, false, false, false, false, false));
        assertFalse(ServerAppearanceRefreshCommandPath.isExactExecutableLeaf(
                true, false, true, true, true, false));
        assertFalse(ServerAppearanceRefreshCommandPath.isExactExecutableLeaf(
                true, true, true, false, true, false));
        assertFalse(ServerAppearanceRefreshCommandPath.isExactExecutableLeaf(
                true, true, true, true, false, false));
        assertFalse(ServerAppearanceRefreshCommandPath.isExactExecutableLeaf(
                true, true, true, true, true, true));
    }
}

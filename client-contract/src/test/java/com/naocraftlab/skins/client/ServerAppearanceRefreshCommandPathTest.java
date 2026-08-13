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

    @Test
    void acceptsOnlyTheExactNamespacedBukkitGreedyArgumentShape() {
        assertTrue(ServerAppearanceRefreshCommandPath.isExactBukkitWrapper(
                true, true, true, 1,
                true, true, true, true, false));

        assertFalse(ServerAppearanceRefreshCommandPath.isExactBukkitWrapper(
                false, false, false, 0,
                false, false, false, false, false));
        assertFalse(ServerAppearanceRefreshCommandPath.isExactBukkitWrapper(
                true, true, true, 2,
                true, true, true, true, false));
        assertFalse(ServerAppearanceRefreshCommandPath.isExactBukkitWrapper(
                true, true, true, 1,
                true, false, true, true, false));
        assertFalse(ServerAppearanceRefreshCommandPath.isExactBukkitWrapper(
                true, true, true, 1,
                true, true, true, false, false));
        assertFalse(ServerAppearanceRefreshCommandPath.isExactBukkitWrapper(
                true, true, true, 1,
                true, true, true, true, true));
    }
}

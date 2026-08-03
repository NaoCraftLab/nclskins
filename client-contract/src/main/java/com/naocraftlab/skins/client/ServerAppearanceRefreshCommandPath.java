package com.naocraftlab.skins.client;


public final class ServerAppearanceRefreshCommandPath {
    private ServerAppearanceRefreshCommandPath() {
    }

    public static boolean isExactExecutableLeaf(
            boolean rootPresent,
            boolean rootLiteral,
            boolean refreshPresent,
            boolean refreshLiteral,
            boolean refreshExecutable,
            boolean refreshHasChildren) {
        return rootPresent
                && rootLiteral
                && refreshPresent
                && refreshLiteral
                && refreshExecutable
                && !refreshHasChildren;
    }
}

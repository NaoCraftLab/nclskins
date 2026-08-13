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

    public static boolean isExactBukkitWrapper(
            boolean rootPresent,
            boolean rootLiteral,
            boolean rootExecutable,
            int rootChildCount,
            boolean argumentsPresent,
            boolean argumentsNode,
            boolean argumentsGreedyString,
            boolean argumentsExecutable,
            boolean argumentsHasChildren) {
        return rootPresent
                && rootLiteral
                && rootExecutable
                && rootChildCount == 1
                && argumentsPresent
                && argumentsNode
                && argumentsGreedyString
                && argumentsExecutable
                && !argumentsHasChildren;
    }
}

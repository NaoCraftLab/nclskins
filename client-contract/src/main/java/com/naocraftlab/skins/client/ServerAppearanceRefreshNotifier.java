package com.naocraftlab.skins.client;

import java.util.OptionalLong;


@FunctionalInterface
public interface ServerAppearanceRefreshNotifier {
    ServerAppearanceRefreshNotifier NO_OP = new ServerAppearanceRefreshNotifier() {
        @Override
        public OptionalLong activeConnectionGeneration() {
            return OptionalLong.empty();
        }

        @Override
        public void requestOfficialProfileRefresh() {
        }
    };


    default OptionalLong activeConnectionGeneration() {
        return OptionalLong.of(1L);
    }

    void requestOfficialProfileRefresh();
}

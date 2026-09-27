package com.naocraftlab.skins.runtime.update;

public final class UpdateCompositionRoot {
    public static UpdateCatalogClient create() {
        return new UpdateCatalogClient(
                new JdkUpdateHttpBoundary(), new UpdateCatalogParser(), new UpdateSelector());
    }

    private UpdateCompositionRoot() {}
}

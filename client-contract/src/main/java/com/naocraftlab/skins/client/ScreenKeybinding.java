package com.naocraftlab.skins.client;

public enum ScreenKeybinding {
    MY_LOOKS("key.nclskins.open_gallery", ScreenDestination.GALLERY),
    EDIT_ACTIVE_PRESET("key.nclskins.edit_active_preset", ScreenDestination.ACTIVE_EDITOR),
    PROVIDERS("key.nclskins.open_providers", ScreenDestination.PROVIDERS),
    SKIN_CATALOG("key.nclskins.open_skin_catalog", ScreenDestination.SKIN_CATALOG),
    SKIN_IMPORT("key.nclskins.open_skin_import", ScreenDestination.SKIN_IMPORT);

    public static final String CATEGORY_KEY = "key.category.nclskins.main";
    private final String translationKey;
    private final ScreenDestination destination;

    ScreenKeybinding(String translationKey, ScreenDestination destination) {
        this.translationKey = translationKey;
        this.destination = destination;
    }

    public String translationKey() { return translationKey; }
    public ScreenDestination destination() { return destination; }
}

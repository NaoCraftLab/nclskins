package com.naocraftlab.skins.client;

public record ProviderVisibility(boolean skin, boolean cape) {
    public static final ProviderVisibility ALL = new ProviderVisibility(true, true);

    public boolean any() {
        return skin || cape;
    }
}

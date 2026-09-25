package com.naocraftlab.skins.core.provider;

public enum BuiltinProvider {
    OFFLINE(true, true, true),
    MINECRAFT(true, true, true),
    OPTIFINE(false, true, false),
    SKINMC(false, true, false),
    SNEAKY(false, true, false);

    private final boolean skin;
    private final boolean cape;
    private final boolean writable;

    BuiltinProvider(boolean skin, boolean cape, boolean writable) {
        this.skin = skin;
        this.cape = cape;
        this.writable = writable;
    }

    public boolean supportsSkin() {
        return skin;
    }

    public boolean supportsCape() {
        return cape;
    }

    public boolean writable() {
        return writable;
    }
}

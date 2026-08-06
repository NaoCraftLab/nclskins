package com.naocraftlab.skins.core.importing;


public enum ExternalImportSource {
    MINECRAFT_LAUNCHER,
    CURSEFORGE_APP,
    MODRINTH_APP,
    SKIN_SHUFFLE,
    SKIN_SWAPPER_FAMILY,
    QUICK_SKIN,
    PRISM_LAUNCHER;

    public boolean requiresSqlite() {
        return this == CURSEFORGE_APP || this == MODRINTH_APP;
    }
}

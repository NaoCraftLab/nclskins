package com.naocraftlab.skins.buildlogic.probe.wavey;

import cpw.mods.modlauncher.api.ServiceRunner;
import java.nio.file.Path;

public final class LegacyCapeService implements ServiceRunner {
    @Override public void run() throws Throwable {
        LegacyCapeBatch.run(Path.of(System.getProperty("nclskins.wavey.root")));
    }
}

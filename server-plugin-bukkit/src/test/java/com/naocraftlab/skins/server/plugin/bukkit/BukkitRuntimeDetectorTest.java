package com.naocraftlab.skins.server.plugin.bukkit;

import com.naocraftlab.skins.server.plugin.common.ExactAdapterSelector;
import com.naocraftlab.skins.server.plugin.common.ServerRuntimeIdentity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BukkitRuntimeDetectorTest {
    @Test
    void extractsMinecraftVersionFromLegacyBukkitQualifier() {
        assertEquals("1.20.1", BukkitRuntimeDetector.minecraftVersion(
                "1.20.1-R0.1-SNAPSHOT"));
    }

    @Test
    void extractsMinecraftVersionFromModernPaperBuildQualifier() {
        assertEquals("26.1.2", BukkitRuntimeDetector.minecraftVersion(
                "26.1.2.build.74"));
        assertEquals("26.2", BukkitRuntimeDetector.minecraftVersion(
                "26.2.build.112"));
    }

    @Test
    void mapsBothAuthlib7RuntimePatchesToOneFamilyAdapterWithoutFallback() {
        ExactAdapterSelector<BukkitNativeAdapter> selector = BukkitAdapterCatalog.selector();
        ServerRuntimeIdentity firstPatch = paper("26.1.1");
        ServerRuntimeIdentity secondPatch = paper("26.1.2");

        assertTrue(selector.select(firstPatch).supported());
        assertTrue(selector.select(secondPatch).supported());
        assertEquals("paper-authlib7", selector.select(firstPatch).load().id());
        assertEquals("paper-authlib7", selector.select(secondPatch).load().id());
        assertFalse(selector.select(paper("26.1.3")).supported());
    }

    private static ServerRuntimeIdentity paper(String version) {
        return new ServerRuntimeIdentity(
                version,
                ServerRuntimeIdentity.Family.PAPER,
                ServerRuntimeIdentity.ThreadingModel.CLASSIC);
    }
}

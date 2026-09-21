package com.naocraftlab.skins.server.plugin.bukkit;

import com.naocraftlab.skins.server.plugin.common.ExactAdapterSelector;
import com.naocraftlab.skins.server.plugin.common.ServerRuntimeIdentity;
import org.junit.jupiter.api.Test;

import java.util.List;

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
        assertEquals("26.3", BukkitRuntimeDetector.minecraftVersion(
                "26.3-rc-3-R0.1-SNAPSHOT"));
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

    @Test
    void everyCataloguedIdentitySelectsExactlyOneExpectedBinding() {
        ExactAdapterSelector<BukkitNativeAdapter> selector = BukkitAdapterCatalog.selector();
        List<ExpectedBinding> expected = List.of(
                binding("1.20.1", ServerRuntimeIdentity.Family.CRAFTBUKKIT, "legacy-authlib4"),
                binding("1.20.1", ServerRuntimeIdentity.Family.SPIGOT, "legacy-authlib4"),
                binding("1.20.1", ServerRuntimeIdentity.Family.PAPER, "paper-authlib4"),
                binding("1.20.1", ServerRuntimeIdentity.Family.PURPUR, "paper-authlib4"),
                binding("1.20.1", ServerRuntimeIdentity.Family.FOLIA, "paper-authlib4"),
                binding("1.21.1", ServerRuntimeIdentity.Family.PAPER, "paper-authlib6"),
                binding("1.21.1", ServerRuntimeIdentity.Family.PURPUR, "paper-authlib6"),
                binding("1.21.11", ServerRuntimeIdentity.Family.PAPER, "paper-authlib7"),
                binding("1.21.11", ServerRuntimeIdentity.Family.PURPUR, "paper-authlib7"),
                binding("1.21.11", ServerRuntimeIdentity.Family.FOLIA, "paper-authlib7"),
                binding("26.1.1", ServerRuntimeIdentity.Family.PAPER, "paper-authlib7"),
                binding("26.1.2", ServerRuntimeIdentity.Family.PAPER, "paper-authlib7"),
                binding("26.1.2", ServerRuntimeIdentity.Family.PURPUR, "paper-authlib7"),
                binding("26.1.2", ServerRuntimeIdentity.Family.FOLIA, "paper-authlib7"),
                binding("26.2", ServerRuntimeIdentity.Family.PAPER, "paper-authlib9"),
                binding("26.2", ServerRuntimeIdentity.Family.PURPUR, "paper-authlib9"),
                binding("26.2", ServerRuntimeIdentity.Family.FOLIA, "paper-authlib9"));

        expected.forEach(binding -> {
            ExactAdapterSelector.Selection<BukkitNativeAdapter> selection =
                    selector.select(binding.identity());
            assertTrue(selection.supported(), binding.identity().toString());
            assertEquals(binding.adapterId(), selection.load().id());
        });
    }

    @Test
    void nearbyUnsupportedIdentitiesNeverFallBack() {
        ExactAdapterSelector<BukkitNativeAdapter> selector = BukkitAdapterCatalog.selector();

        assertFalse(selector.select(paper("1.21.2")).supported());
        assertFalse(selector.select(paper("26.1.3")).supported());
        assertFalse(selector.select(new ServerRuntimeIdentity(
                "1.21.1",
                ServerRuntimeIdentity.Family.FOLIA,
                ServerRuntimeIdentity.ThreadingModel.REGIONIZED)).supported());
        assertFalse(selector.select(new ServerRuntimeIdentity(
                "1.21.1",
                ServerRuntimeIdentity.Family.CRAFTBUKKIT,
                ServerRuntimeIdentity.ThreadingModel.CLASSIC)).supported());
    }

    @Test
    void selectsExactAuthlib10AdapterOnlyForPaper263() {
        ExactAdapterSelector<BukkitNativeAdapter> selector = BukkitAdapterCatalog.selector();

        assertTrue(selector.select(paper("26.3")).supported());
        assertEquals("paper-authlib10", selector.select(paper("26.3")).load().id());
        assertFalse(selector.select(paper("26.3.1")).supported());
    }

    @Test
    void supportsPurpur263WithAuthlib10Adapter() {
        ExactAdapterSelector<BukkitNativeAdapter> selector = BukkitAdapterCatalog.selector();

        assertTrue(selector.select(purpur("26.3")).supported());
        assertEquals("paper-authlib10", selector.select(purpur("26.3")).load().id());
        assertFalse(selector.select(purpur("26.4")).supported());
    }

    private static ServerRuntimeIdentity purpur(String version) {
        return new ServerRuntimeIdentity(
                version,
                ServerRuntimeIdentity.Family.PURPUR,
                ServerRuntimeIdentity.ThreadingModel.CLASSIC);
    }

    private static ServerRuntimeIdentity paper(String version) {
        return new ServerRuntimeIdentity(
                version,
                ServerRuntimeIdentity.Family.PAPER,
                ServerRuntimeIdentity.ThreadingModel.CLASSIC);
    }

    private static ExpectedBinding binding(
            String version,
            ServerRuntimeIdentity.Family family,
            String adapterId) {
        return new ExpectedBinding(
                new ServerRuntimeIdentity(
                        version,
                        family,
                        family == ServerRuntimeIdentity.Family.FOLIA
                                ? ServerRuntimeIdentity.ThreadingModel.REGIONIZED
                                : ServerRuntimeIdentity.ThreadingModel.CLASSIC),
                adapterId);
    }

    private record ExpectedBinding(ServerRuntimeIdentity identity, String adapterId) {
    }
}

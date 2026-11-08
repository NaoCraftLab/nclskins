package com.naocraftlab.skins.core.service;

import com.naocraftlab.skins.core.model.AccountAppearanceState;
import com.naocraftlab.skins.core.model.AccountState;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class GalleryContractTest {
    @Test
    void guardedRecoverableContractHasNoOrdinaryOrUnsupportedFallback() throws Exception {
        org.junit.jupiter.api.Assertions.assertTrue(java.lang.reflect.Modifier.isAbstract(
                LibraryStatePort.class.getMethod("mutateAccountAndAppearanceRecoverably",
                        UUID.class, LibraryStatePort.AccountAppearanceMutation.class,
                        java.util.function.BooleanSupplier.class).getModifiers()));
    }

    @Test
    void additiveDefaultsAndCopiesDoNotResetFavoriteOrUnrelatedPreferences() {
        UUID id = UUID.randomUUID();
        var preset = new com.naocraftlab.skins.core.model.AppearancePreset(id, "Name",
                com.naocraftlab.skins.core.model.SkinReference.accountDefault(), null,
                Instant.EPOCH, Instant.EPOCH);
        org.junit.jupiter.api.Assertions.assertFalse(preset.favorite());
        org.junit.jupiter.api.Assertions.assertTrue(preset.withFavorite(true).withOfflineCape(null).favorite());
        org.junit.jupiter.api.Assertions.assertEquals(preset.updatedAt(), preset.withFavorite(true).updatedAt());
        var initial = com.naocraftlab.skins.core.model.AccountUiPreferences.defaults(id);
        org.junit.jupiter.api.Assertions.assertTrue(initial.collapsedGallerySections().isEmpty());
        var patched = initial.withGallerySections(java.util.Map.of("gallery.favorites", true,
                "gallery.your_appearances", false));
        var copied = patched.withSelectedAddSourceTab(com.naocraftlab.skins.core.model.AddSourceTab.CATALOG)
                .withSelectedEditorTab(com.naocraftlab.skins.core.model.EditorTab.APPEARANCE)
                .withPreferredSkinVariant(com.naocraftlab.skins.core.model.SkinVariant.SLIM)
                .withCollectionCollapsed("other", true).withCollapsedCapeCollections(java.util.Set.of("cape"))
                .withSelectedProvidersTab(com.naocraftlab.skins.core.provider.AppearanceProviders.Component.CAPE);
        org.junit.jupiter.api.Assertions.assertEquals(java.util.Set.of("gallery.favorites"), copied.collapsedGallerySections());
        org.junit.jupiter.api.Assertions.assertEquals(java.util.Set.of("other"), copied.collapsedCollectionIds());
        org.junit.jupiter.api.Assertions.assertEquals(java.util.Set.of("cape"), copied.collapsedCapeCollections());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> initial.withGallerySections(java.util.Map.of("other", true)));
    }

    @Test
    void mutationPlanRejectsForeignAppearanceBeforeAnyWriterReceivesIt() {
        var account = AccountState.empty(UUID.randomUUID(), Instant.EPOCH);
        var foreign = AccountAppearanceState.empty(UUID.randomUUID(), Instant.EPOCH);
        assertThrows(IllegalArgumentException.class,
                () -> LibraryStatePort.AccountAppearanceMutationPlan.both(account, foreign));
    }
}

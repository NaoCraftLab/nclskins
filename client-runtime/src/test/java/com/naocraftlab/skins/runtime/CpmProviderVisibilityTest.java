package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.provider.AppearanceProviders;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CpmProviderVisibilityTest {
    @Test
    void savedCpmRowIsHiddenWithoutRemovingItsSlot() {
        var initial = AppearanceProviders.initial();
        var providers = new AppearanceProviders(initial.skin(), initial.cape().initializeCpm());
        var view = new ProvidersPresenter().present(providers, AppearanceProviders.Component.CAPE,
                false, false, PreviewInteractionModel.gallery(),
                com.naocraftlab.skins.core.model.SkinVariant.CLASSIC, 640, 360);
        assertFalse(view.widgets().stream().anyMatch(w -> w.id().contains("CPM")));
        assertTrue(providers.cape().enabled(com.naocraftlab.skins.core.provider.BuiltinProvider.CPM));
    }

    @Test
    void chooserUsesModPresenceRatherThanModelReadiness() {
        var presenter = new ProvidersPresenter();
        var providers = AppearanceProviders.initial();
        assertFalse(presenter.presentChooser(providers, AppearanceProviders.Component.CAPE,
                false, 320, 240, 0).widgets().stream().anyMatch(w -> w.id().equals("providers.row.CPM")));
        assertTrue(presenter.presentChooser(providers, AppearanceProviders.Component.CAPE,
                false, 320, 240, 0, true).widgets().stream().anyMatch(w -> w.id().equals("providers.row.CPM")));
        assertFalse(presenter.presentChooser(providers, AppearanceProviders.Component.SKIN,
                false, 320, 240, 0, true).widgets().stream().anyMatch(w -> w.id().equals("providers.row.CPM")));
    }
}

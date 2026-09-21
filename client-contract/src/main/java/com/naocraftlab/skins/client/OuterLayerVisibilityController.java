package com.naocraftlab.skins.client;


@FunctionalInterface
public interface OuterLayerVisibilityController {

    default OuterLayerVisibility current() {
        return OuterLayerVisibility.allVisible();
    }

    void applyDurable(OuterLayerVisibility visibility);
}

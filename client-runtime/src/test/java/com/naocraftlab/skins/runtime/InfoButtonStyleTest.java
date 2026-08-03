package com.naocraftlab.skins.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class InfoButtonStyleTest {
    @Test
    void framelessInfoGlyphUsesOnlyStateDependentLabelColor() {
        assertEquals(
                InfoButtonStyle.LABEL_COLOR,
                InfoButtonStyle.labelColor(true, false));
        assertEquals(
                InfoButtonStyle.HIGHLIGHTED_LABEL_COLOR,
                InfoButtonStyle.labelColor(true, true));
        assertEquals(
                InfoButtonStyle.DISABLED_LABEL_COLOR,
                InfoButtonStyle.labelColor(false, true));
    }
}

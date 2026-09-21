package com.naocraftlab.skins.runtime;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


final class ViewSpecWidgetInteractionTest {
    @Test
    void textFieldCarriesSelectAllAndSubmitPolicy() {
        ViewSpec.Widget field = ViewSpec.Widget.textField(
                "add.url.input",
                new Bounds(4, 8, 120, 20),
                UiMessage.info("label"),
                "https://example.test/skin.png",
                UiMessage.info("hint"),
                true,
                512,
                true,
                Optional.of("add.url.load"));

        assertTrue(field.selectAllOnFocusAcquire());
        assertEquals(Optional.of("add.url.load"), field.submitActionId());
    }

    @Test
    void nonTextWidgetRejectsTextFieldInteractionPolicy() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ViewSpec.Widget(
                        "action",
                        ViewSpec.WidgetKind.BUTTON,
                        new Bounds(0, 0, 20, 20),
                        UiMessage.info("action"),
                        Optional.empty(),
                        Optional.empty(),
                        true,
                        true,
                        0,
                        true,
                        Optional.empty()));
    }

    @Test
    void verticalTabCarriesItsSelectedStateAndIcon() {
        ViewSpec.Widget tab = ViewSpec.Widget.verticalTabButton(
                "editor.tab.appearance",
                new Bounds(406, 45, 24, 24),
                UiMessage.info("nclskins.editor.tab.appearance"),
                GuiIcon.EDITOR_TAB_APPEARANCE,
                true,
                true);

        assertEquals(ViewSpec.WidgetKind.TAB_BUTTON, tab.kind());
        assertEquals(Optional.of("selected"), tab.value());
        assertEquals(Optional.of(GuiIcon.EDITOR_TAB_APPEARANCE), tab.icon());
    }
}

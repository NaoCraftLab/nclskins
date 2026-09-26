package com.naocraftlab.skins.compat.client.identifier.extraction;

import com.naocraftlab.skins.runtime.ViewSpec;
import java.util.Optional;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import com.mojang.blaze3d.platform.InputConstants;

final class ExtractionInputAdapter {
    private ExtractionInputAdapter() {}

    static boolean isSpace(KeyEvent event) {
        return event.shortcutKey() == InputConstants.KEYCODE_SPACE;
    }

    static boolean isPrimaryPointer(MouseButtonEvent event) {
        return event.button() == InputConstants.MOUSE_BUTTON_LEFT;
    }

    static int productPointerButton(MouseButtonEvent event) {
        return 0;
    }

    static boolean isEnterKey(KeyEvent event) {
        return event.shortcutKey() == InputConstants.KEYCODE_RETURN || event.shortcutKey() == InputConstants.KEYCODE_NUMPADENTER;
    }

    static Optional<ViewSpec.NavigationCommand> navigationCommand(KeyEvent event) {
        return switch (event.shortcutKey()) {
            case InputConstants.KEYCODE_TAB -> Optional.of(event.hasShiftDown()
                    ? ViewSpec.NavigationCommand.TAB_BACKWARD
                    : ViewSpec.NavigationCommand.TAB_FORWARD);
            case InputConstants.KEYCODE_LEFT -> Optional.of(ViewSpec.NavigationCommand.LEFT);
            case InputConstants.KEYCODE_RIGHT -> Optional.of(ViewSpec.NavigationCommand.RIGHT);
            case InputConstants.KEYCODE_UP -> Optional.of(ViewSpec.NavigationCommand.UP);
            case InputConstants.KEYCODE_DOWN -> Optional.of(ViewSpec.NavigationCommand.DOWN);
            case InputConstants.KEYCODE_RETURN, InputConstants.KEYCODE_NUMPADENTER, InputConstants.KEYCODE_SPACE ->
                    Optional.of(ViewSpec.NavigationCommand.ACTIVATE);
            default -> Optional.empty();
        };
    }

    static boolean synchronizeBeforeRebuild() {
        return true;
    }

    static boolean focusBeforeScrollPublication() {
        return true;
    }

    static boolean synchronizeAfterNavigation() {
        return true;
    }
}

package com.naocraftlab.skins.compat.client.identifier.extraction;

import com.naocraftlab.skins.runtime.ViewSpec;
import java.util.Optional;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.lwjgl.glfw.GLFW;

final class ExtractionInputAdapter {
    private ExtractionInputAdapter() {}

    static boolean isSpace(KeyEvent event) {
        return event.key() == GLFW.GLFW_KEY_SPACE;
    }

    static boolean isPrimaryPointer(MouseButtonEvent event) {
        return event.button() == 0;
    }

    static int productPointerButton(MouseButtonEvent event) {
        return event.button();
    }

    static boolean isEnterKey(KeyEvent event) {
        return event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER;
    }

    static Optional<ViewSpec.NavigationCommand> navigationCommand(KeyEvent event) {
        return switch (event.key()) {
            case GLFW.GLFW_KEY_TAB -> Optional.of(event.hasShiftDown()
                    ? ViewSpec.NavigationCommand.TAB_BACKWARD
                    : ViewSpec.NavigationCommand.TAB_FORWARD);
            case GLFW.GLFW_KEY_LEFT -> Optional.of(ViewSpec.NavigationCommand.LEFT);
            case GLFW.GLFW_KEY_RIGHT -> Optional.of(ViewSpec.NavigationCommand.RIGHT);
            case GLFW.GLFW_KEY_UP -> Optional.of(ViewSpec.NavigationCommand.UP);
            case GLFW.GLFW_KEY_DOWN -> Optional.of(ViewSpec.NavigationCommand.DOWN);
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE ->
                    Optional.of(ViewSpec.NavigationCommand.ACTIVATE);
            default -> Optional.empty();
        };
    }

    static boolean synchronizeBeforeRebuild() {
        return false;
    }

    static boolean focusBeforeScrollPublication() {
        return false;
    }

    static boolean synchronizeAfterNavigation() {
        return false;
    }
}

package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.ScreenDestination;
import com.naocraftlab.skins.client.ScreenKeybinding;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ScreenKeybindingDispatcherTest {
    @Test
    void everyConflictingSubsetChoosesUiPriorityAndNeverQueuesAnotherScreen() {
        ScreenKeybinding[] bindings = ScreenKeybinding.values();
        for (int mask = 1; mask < (1 << bindings.length); mask++) {
            ScreenKeybindingDispatcher dispatcher = new ScreenKeybindingDispatcher();
            for (int index = bindings.length - 1; index >= 0; index--) {
                if ((mask & (1 << index)) != 0) dispatcher.press(bindings[index], true);
            }
            assertEquals(bindings[Integer.numberOfTrailingZeros(mask)].destination(), dispatcher.drain(true).orElseThrow());
            assertTrue(dispatcher.drain(true).isEmpty());
        }
    }

    @Test
    void blockedInputAndLostContextDoNotLeakIntoNextGameTick() {
        for (ScreenKeybinding binding : ScreenKeybinding.values()) {
            ScreenKeybindingDispatcher dispatcher = new ScreenKeybindingDispatcher();
            dispatcher.press(binding, false);
            assertTrue(dispatcher.drain(true).isEmpty());
            dispatcher.press(binding, true);
            assertTrue(dispatcher.drain(false).isEmpty());
            assertTrue(dispatcher.drain(true).isEmpty());
            dispatcher.press(binding, true);
            assertEquals(binding.destination(), dispatcher.drain(true).orElseThrow());
        }
    }

    @Test
    void publishedIdentityAndUiPriorityAreIndependentOfEnumDestinationOrderAndLocale() {
        assertEquals(List.of("key.nclskins.open_gallery", "key.nclskins.edit_active_preset",
                "key.nclskins.open_providers", "key.nclskins.open_skin_catalog", "key.nclskins.open_skin_import"),
                Arrays.stream(ScreenKeybinding.values()).map(ScreenKeybinding::translationKey).toList());
        assertEquals(List.of(ScreenDestination.GALLERY, ScreenDestination.ACTIVE_EDITOR,
                ScreenDestination.PROVIDERS, ScreenDestination.SKIN_CATALOG, ScreenDestination.SKIN_IMPORT),
                Arrays.stream(ScreenKeybinding.values()).map(ScreenKeybinding::destination).toList());
        assertEquals("key.category.nclskins.main", ScreenKeybinding.CATEGORY_KEY);
    }
}

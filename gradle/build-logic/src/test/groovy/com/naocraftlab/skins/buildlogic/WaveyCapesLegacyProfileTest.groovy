package com.naocraftlab.skins.buildlogic

import org.junit.jupiter.api.Test
import static org.junit.jupiter.api.Assertions.*

class WaveyCapesLegacyProfileTest {
    @Test
    void legacyClientDefaultsAreSelectedByActualLoaderAndImmediateAbi() {
        assertEquals('legacy-client-rendered-buffer', WaveyLoaderComposition.select('default', 'forge', 'rendered-buffer', false))
        assertEquals('legacy-client-mesh', WaveyLoaderComposition.select('default', 'neoforge', 'mesh', false))
        assertEquals('isolated', WaveyLoaderComposition.select('default', 'fabric', 'mesh', false))
        assertThrows(IllegalArgumentException) { WaveyLoaderComposition.select('legacy-client-mesh', 'forge', 'mesh', false) }
        assertThrows(IllegalArgumentException) { WaveyLoaderComposition.select('legacy-client-rendered-buffer', 'neoforge', 'rendered-buffer', false) }
        assertThrows(IllegalArgumentException) { WaveyLoaderComposition.select('legacy-client-mesh', 'neoforge', 'mesh', true) }
    }
}

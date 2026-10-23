package com.naocraftlab.skins.buildlogic

import org.junit.jupiter.api.Test
import static org.junit.jupiter.api.Assertions.*

class WaveyCapesLoaderProfileTest {
    @Test
    void explicitClientProfileRequiresTheBufferedSubmissionTopology() {
        Class profile = null
        try { profile = Class.forName('com.naocraftlab.skins.buildlogic.WaveyLoaderComposition') }
        catch (ClassNotFoundException absent) { }
        assertNotNull(profile, 'Canonical CLIENT composition owner is missing')
        assertEquals('direct-client-buffered', profile.select('direct-client-buffered', 'neoforge', 'buffered', true))
        assertEquals('isolated', profile.select('isolated', 'fabric', 'prepared', false))
        assertThrows(IllegalArgumentException) { profile.select('direct-client-buffered', 'fabric', 'buffered', true) }
        assertEquals('direct-client-buffered', profile.select('direct-client-buffered', 'neoforge', 'buffered', false))
        assertEquals('direct-client-prepared', profile.select('direct-client-prepared', 'neoforge', 'prepared', false))
        assertThrows(IllegalArgumentException) { profile.select('direct-client-buffered', 'neoforge', 'prepared', false) }
        assertThrows(IllegalArgumentException) { profile.select('unknown', 'neoforge', 'buffered', true) }
    }
}

package com.naocraftlab.skins.buildlogic

import groovy.json.JsonOutput
import org.junit.jupiter.api.Test
import static org.junit.jupiter.api.Assertions.*

final class CatalogInventoryTransportTest {
    @Test void boundaryRequiresEmptySecondPageAndRejectsTruncation() {
        List<String> calls = []
        def page = { int number ->
            calls.add(number.toString())
            [status: 200, headers: [:], body: JsonOutput.toJson(number == 1 ? (1..100).collect { [id: it] } : []).bytes]
        }
        assertEquals(100, GithubReleaseInventory.snapshot(page).size())
        assertEquals(['1', '2'], calls)
        assertThrows(IllegalArgumentException) {
            GithubReleaseInventory.snapshot { int n -> [status: 200, headers: [:], body: JsonOutput.toJson(n == 1 ? (1..100).collect { [id: it] } : [[id: 101]]).bytes] }
        }
        assertThrows(IllegalArgumentException) {
            GithubReleaseInventory.snapshot { int n -> [status: 200, headers: [link: '<https://api.github.com/next>; rel="next"'], body: '[]'.bytes] }
        }
        assertThrows(IllegalArgumentException) {
            GithubReleaseInventory.snapshot { int n -> [status: 200, headers: [:], body: '{}'.bytes] }
        }
        calls.clear()
        assertEquals([], GithubReleaseInventory.snapshot { int n -> calls.add(n.toString()); [status: 200, headers: [:], body: '[]'.bytes] })
        assertEquals(['1'], calls)
    }
}

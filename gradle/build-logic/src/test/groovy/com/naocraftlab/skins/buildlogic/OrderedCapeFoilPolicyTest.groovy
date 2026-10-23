package com.naocraftlab.skins.buildlogic

import org.junit.jupiter.api.Test
import static org.junit.jupiter.api.Assertions.*

class OrderedCapeFoilPolicyTest {
    @Test
    void onlyExactIdentityMarkerAndVanillaContinuationRouteAreAdmitted() {
        File root = new File('../..').canonicalFile
        String base = 'compat/capabilities/appearance/cape-foil-pipeline/src/main/java/com/naocraftlab/skins/compat/client/identifier/foil/'
        String marker = new File(root, base + 'OrderedCapeFoil.java').text
        String mixin = new File(root, base + 'pipeline/OrderedCapeFoilMixin.java').text
        assertTrue(SemanticVerifier.scopedOrderedCapeFoil(base + 'OrderedCapeFoil.java', marker))
        assertTrue(SemanticVerifier.scopedOrderedCapeFoil(base + 'pipeline/OrderedCapeFoilMixin.java', mixin))
        assertFalse(SemanticVerifier.scopedOrderedCapeFoil(base + 'OtherRenderer.java', marker))
        assertFalse(SemanticVerifier.scopedOrderedCapeFoil(base + 'OrderedCapeFoil.java', marker.replace('capture.orderedFoil()', 'true')))
        assertFalse(SemanticVerifier.scopedOrderedCapeFoil(base + 'OrderedCapeFoil.java', marker.replace('CapturedCapePass.material(RenderTypes.entityGlint(), validity, true)', 'RenderTypes.entityGlint()')))
        assertFalse(SemanticVerifier.scopedOrderedCapeFoil(base + 'pipeline/OrderedCapeFoilMixin.java', mixin.replace('order(1), pose, type, callback', 'order(1), pose, RenderTypes.entityGlint(), callback')))
        assertFalse(SemanticVerifier.scopedOrderedCapeFoil(base + 'pipeline/OrderedCapeFoilMixin.java', mixin.replace('OrderedCapeFoil.owns(type)', 'true')))
        assertFalse(SemanticVerifier.scopedOrderedCapeFoil(base + 'pipeline/OrderedCapeFoilMixin.java', mixin.replace('order(1)', 'order(0)')))
        assertFalse(SemanticVerifier.scopedOrderedCapeFoil(base + 'pipeline/OrderedCapeFoilMixin.java', mixin.replace('receiver, pose, type, callback', 'receiver, pose, RenderTypes.entityGlint(), callback')))
        assertFalse(SemanticVerifier.scopedOrderedCapeFoil(base + 'pipeline/OrderedCapeFoilMixin.java', mixin.replace('require = 1', 'require = 0')))
    }
}

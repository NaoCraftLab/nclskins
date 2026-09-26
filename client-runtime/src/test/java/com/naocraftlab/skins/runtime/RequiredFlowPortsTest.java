package com.naocraftlab.skins.runtime;

import java.lang.reflect.Modifier;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class RequiredFlowPortsTest {
    @Test
    void compositionMustImplementSaveWarmupAndObservation() throws Exception {
        assertTrue(Modifier.isAbstract(ClientOperations.class.getMethod(
                "durableAppearance").getModifiers()));
        assertTrue(Modifier.isAbstract(ClientOperations.class.getMethod(
                "reconcileAppearance", com.naocraftlab.skins.core.reconciliation.ReconciliationPolicy.Trigger.class).getModifiers()));
        assertTrue(Modifier.isAbstract(LibraryEditorPort.class.getMethod(
                "saveEditor", LibraryEditorPort.EditorSaveRequest.class).getModifiers()));
        assertTrue(Modifier.isAbstract(ClientOperations.class.getMethod(
                "warmResourceCapeCatalog", long.class).getModifiers()));
        assertTrue(Modifier.isAbstract(ClientOperations.class.getMethod(
                "warmCapeCatalog", UUID.class, long.class).getModifiers()));
        assertTrue(Modifier.isAbstract(ClientOperations.class.getMethod(
                "onCapeObservation", java.util.function.Consumer.class).getModifiers()));
    }
}

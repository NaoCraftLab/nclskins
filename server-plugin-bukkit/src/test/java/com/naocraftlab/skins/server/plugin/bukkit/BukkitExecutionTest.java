package com.naocraftlab.skins.server.plugin.bukkit;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;


final class BukkitExecutionTest {
    @Test
    void invokesSchedulerThroughItsPublicInterface() throws ReflectiveOperationException {
        Iterator<Integer> scheduler = List.of(1).iterator();
        Method concrete = scheduler.getClass().getMethod("next");
        assertThrows(IllegalAccessException.class,
                () -> concrete.invoke(scheduler));

        int result = (int) BukkitExecution.invokePublicInterface(
                scheduler, Iterator.class, "next", new Class<?>[0]);

        assertEquals(1, result);
    }
}

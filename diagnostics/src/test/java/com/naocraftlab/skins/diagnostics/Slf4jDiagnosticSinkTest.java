package com.naocraftlab.skins.diagnostics;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;


final class Slf4jDiagnosticSinkTest {
    @Test
    void checksLevelBeforeEvaluatingSupplier() {
        List<String> calls = new ArrayList<>();
        Logger logger = (Logger) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{Logger.class},
                (proxy, method, arguments) -> {
                    calls.add(method.getName());
                    if (method.getReturnType() == boolean.class) {
                        return false;
                    }
                    return null;
                });
        Slf4jDiagnosticSink sink = new Slf4jDiagnosticSink(logger);
        AtomicInteger evaluated = new AtomicInteger();

        sink.report(DiagnosticEvent.CLIENT_PICKER_FAILED, () -> {
            evaluated.incrementAndGet();
            return DiagnosticDetails.none();
        });

        assertEquals(0, evaluated.get());
        assertEquals(List.of("isDebugEnabled"), calls);
    }
}

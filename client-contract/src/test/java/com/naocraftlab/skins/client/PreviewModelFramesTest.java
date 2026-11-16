package com.naocraftlab.skins.client;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class PreviewModelFramesTest {
    @Test void statusMappingPreservesEveryLiveCompletionTrait() {
        var current = new AtomicBoolean(true);
        var own = new AtomicBoolean(true);
        var reuse = new AtomicBoolean(true);
        var consumption = new AtomicBoolean(true);
        var input = new ArrayList<String>();
        var received = new ArrayList<PreviewModelFrames.Body>();
        var original = new PreviewModelFrames.Completion() {
            public boolean current() { return current.get(); }
            public boolean currentInput(String location) { input.add(location); return location.equals("own") && current(); }
            public boolean needsOwnInputPreparation() { return own.get(); }
            public boolean reusesConfirmedOutput() { return reuse.get(); }
            public boolean requiresConsumption() { return consumption.get(); }
            public void accept(PreviewModelFrames.Body body) { received.add(body); }
        };
        var mapped = assertInstanceOf(PreviewModelFrames.Completion.class,
                PreviewModelFrames.mapStatus(original, status -> status == PreviewRenderer.BodyStatus.READY
                        ? PreviewRenderer.BodyStatus.ORDINARY : status));
        assertTrue(mapped.current());
        assertTrue(mapped.currentInput("own"));
        assertFalse(mapped.currentInput("foreign"));
        assertEquals(java.util.List.of("own", "foreign"), input);
        assertTrue(mapped.needsOwnInputPreparation());
        assertTrue(mapped.reusesConfirmedOutput());
        assertTrue(mapped.requiresConsumption());
        BooleanSupplier bodyCurrent = current::get;
        for (var status : PreviewRenderer.BodyStatus.values()) {
            mapped.accept(new PreviewModelFrames.Body(status, bodyCurrent));
            var body = received.get(received.size() - 1);
            assertEquals(status == PreviewRenderer.BodyStatus.READY ? PreviewRenderer.BodyStatus.ORDINARY : status, body.status());
            assertSame(bodyCurrent, body.current());
        }
        current.set(false); own.set(false); reuse.set(false); consumption.set(false);
        assertFalse(mapped.current());
        assertFalse(mapped.currentInput("own"));
        assertFalse(mapped.needsOwnInputPreparation());
        assertFalse(mapped.reusesConfirmedOutput());
        assertFalse(mapped.requiresConsumption());
        assertFalse(received.get(0).current().getAsBoolean());
    }

    @Test void plainConsumerKeepsPlainSemanticsAndCurrentness() {
        var received = new ArrayList<PreviewModelFrames.Body>();
        var live = new AtomicBoolean(true);
        var mapped = PreviewModelFrames.mapStatus(received::add, status -> status);
        assertFalse(mapped instanceof PreviewModelFrames.Completion);
        BooleanSupplier current = live::get;
        mapped.accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.READY, current));
        assertEquals(PreviewRenderer.BodyStatus.READY, received.get(0).status());
        assertSame(current, received.get(0).current());
        live.set(false);
        assertFalse(received.get(0).current().getAsBoolean());
    }

    @Test void statusMappingRejectsMissingCollaborators() {
        assertThrows(NullPointerException.class, () -> PreviewModelFrames.mapStatus(null, status -> status));
        assertThrows(NullPointerException.class, () -> PreviewModelFrames.mapStatus(body -> {}, null));
    }
}

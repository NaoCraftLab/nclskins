package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.client.GameSessionTokenSource;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

final class PreviewBodyRetirementTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings={"resources","world","account","screen","shutdown"})
    void coarseOwnerRetiresExactCurrentConnectionOnce(String event) throws Exception {
        var fixture=new GalleryRuntimeSelectionTest();fixture.directory=directory;fixture.setup();
        var runtime=fixture.runtime;
        var preview=fixture.view().previews().stream().filter(p->p.id().equals("gallery.preview")).findFirst().orElseThrow();
        var retired=new AtomicInteger();var accepted=new AtomicInteger();
        var callback=new AtomicReference<Consumer<PreviewRenderer.BodyStatus>>();
        callback.set(runtime.observePreviewBody(preview,()->PreviewRenderer.DisplayKind.UNCONFIRMED,accepted::incrementAndGet,()->{
            retired.incrementAndGet();
            runtime.invalidatePreviewBody(callback.get());
            callback.get().accept(PreviewRenderer.BodyStatus.READY);
        }));
        switch(event) {
            case "resources" -> runtime.resourcesReloaded();
            case "world" -> runtime.importWorldChanged();
            case "account" -> {
                fixture.identity.set(new GameSessionTokenSource.SessionIdentity(new UUID(0,941),"OtherFixture"));
                runtime.tick();
            }
            case "screen" -> runtime.closeScreen();
            case "shutdown" -> runtime.close();
            default -> throw new AssertionError(event);
        }
        assertEquals(1,retired.get());assertEquals(0,accepted.get());
        runtime.invalidatePreviewBody(callback.get());runtime.close();
        assertEquals(1,retired.get());
    }

    @ParameterizedTest
    @ValueSource(booleans={false,true})
    void fineReplacementDoesNotRevokeRetainedOwnerAndStaleReleaseCannotCloseSuccessor(boolean resources) throws Exception {
        var fixture=new GalleryRuntimeSelectionTest();fixture.directory=directory;fixture.setup();
        var runtime=fixture.runtime;
        var a=fixture.view().previews().stream().filter(p->p.id().equals("gallery.preview")).findFirst().orElseThrow();
        var oldRetired=new AtomicInteger();var nextRetired=new AtomicInteger();
        var old=runtime.observePreviewBody(a,()->PreviewRenderer.DisplayKind.UNCONFIRMED,()->{},oldRetired::incrementAndGet);
        fixture.selectB();
        var b=fixture.view().previews().stream().filter(p->p.id().equals("gallery.preview")).findFirst().orElseThrow();
        assertNotEquals(a.bodySource(),b.bodySource());
        var next=runtime.observePreviewBody(b,()->PreviewRenderer.DisplayKind.UNCONFIRMED,()->{},nextRetired::incrementAndGet);
        assertEquals(0,oldRetired.get());assertEquals(0,nextRetired.get());
        runtime.invalidatePreviewBody(old);
        assertEquals(0,nextRetired.get());
        if(resources)runtime.resourcesReloaded();else runtime.invalidatePreviewBody(next);
        assertEquals(1,nextRetired.get());assertEquals(0,oldRetired.get());
        runtime.close();assertEquals(1,nextRetired.get());
    }
}

package com.naocraftlab.skins.compat.client.resourcelocation.playerinfo;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.naocraftlab.skins.runtime.ClientApplicationHost;
import com.naocraftlab.skins.runtime.ClientProcess;
import com.naocraftlab.skins.runtime.ClientProcessHost;
import com.naocraftlab.skins.runtime.AppearanceRefresh;
import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

final class ImmediateMenuPreviewLifecycleTest {
    @Test
    void trailingMenuFrameDoesNotAccessNativeGraphicsOrRecreateClosedApplication() throws Exception {
        ImmediateClientRuntime host = ImmediateClientRuntime.instance();
        Field terminal = ImmediateClientRuntime.class.getDeclaredField("terminallyClosed");
        Field application = ImmediateClientRuntime.class.getDeclaredField("application");
        terminal.setAccessible(true);
        application.setAccessible(true);
        boolean previousTerminal = terminal.getBoolean(host);
        Object previousApplication = application.get(host);
        try {
            terminal.setBoolean(host, true);
            application.set(host, null);
            Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            Unsafe unsafe = (Unsafe) unsafeField.get(null);
            NclSkinsMenuPreview stale = (NclSkinsMenuPreview)
                    unsafe.allocateInstance(NclSkinsMenuPreview.class);

            assertDoesNotThrow(() -> stale.render(null, 0, 0, 0.0F));
            assertFalse(host.menuPreviewAvailable());

            terminal.setBoolean(host, false);
            assertFalse(host.menuPreviewAvailable());
            assertDoesNotThrow(() -> stale.render(null, 0, 0, 0.0F));

            ClientProcessHost<Object> process = new ClientProcessHost<>(new ClientProcess.Process() {
                public void warmSession() {}
                public void tick() {}
                public void worldChanged() {}
                public CompletableFuture<AppearanceRefresh.Result> afterReconnect() {
                    return CompletableFuture.completedFuture(AppearanceRefresh.Result.NOT_APPLICABLE);
                }
                public void close() {}
            });
            ClientApplicationHost<?> available = (ClientApplicationHost<?>)
                    unsafe.allocateInstance(ClientApplicationHost.class);
            Field processField = ClientApplicationHost.class.getDeclaredField("process");
            processField.setAccessible(true);
            processField.set(available, process);
            application.set(host, available);
            assertTrue(host.menuPreviewAvailable());
            process.close();
            assertFalse(host.menuPreviewAvailable());
            assertDoesNotThrow(() -> stale.render(null, 0, 0, 0.0F));

            application.set(host, null);
            terminal.setBoolean(host, true);
            assertThrows(IllegalStateException.class, host::runtime);
            assertNull(application.get(host));
        } finally {
            application.set(host, previousApplication);
            terminal.setBoolean(host, previousTerminal);
        }
    }
}

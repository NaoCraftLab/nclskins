package com.naocraftlab.skins.compat.fancymenu;

import com.naocraftlab.skins.client.ScreenDestination;
import com.naocraftlab.skins.diagnostics.DiagnosticDetails;
import com.naocraftlab.skins.diagnostics.DiagnosticEvent;
import com.naocraftlab.skins.diagnostics.DiagnosticSink;
import java.util.function.Consumer;

public final class FancyMenuIntegration {
    private static boolean attempted;

    private FancyMenuIntegration() {}

    public static void install(boolean present, Consumer<ScreenDestination> open, DiagnosticSink diagnostics) {
        if (!present || attempted) return;
        attempted = true;
        try {
            FancyMenuActions.register(open);
        } catch (ReflectiveOperationException | LinkageError incompatible) {
            diagnostics.report(DiagnosticEvent.CLIENT_FANCYMENU_INCOMPATIBLE, DiagnosticDetails::none);
        }
    }
}

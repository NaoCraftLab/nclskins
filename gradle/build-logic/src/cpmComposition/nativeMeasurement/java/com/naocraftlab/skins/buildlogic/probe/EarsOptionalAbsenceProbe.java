package com.naocraftlab.skins.buildlogic.probe;

import java.net.URL;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;

public final class EarsOptionalAbsenceProbe {
    private static final class WithoutEars extends ClassLoader {
        private int forbiddenLoads;
        private WithoutEars() { super(EarsOptionalAbsenceProbe.class.getClassLoader()); }
        @Override public URL getResource(String name) {
            return name.startsWith("diy/y2k/five/thermite/") || name.startsWith("com/unascribed/ears/")
                    ? null : super.getResource(name);
        }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("diy.y2k.five.thermite.") || name.startsWith("com.unascribed.ears.")) {
                forbiddenLoads++;
                throw new ClassNotFoundException(name);
            }
            if (!name.startsWith("com.naocraftlab.skins.compat.client.identifier.measurement.AvatarEarsFitMode")
                    && !name.equals("com.naocraftlab.skins.compat.client.identifier.ears.EarsMeasurementPlugin"))
                return super.loadClass(name, resolve);
            var type = findLoadedClass(name);
            if (type == null) {
                try (var in = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                    if (in == null) throw new ClassNotFoundException(name);
                    byte[] bytes = in.readAllBytes();
                    type = defineClass(name, bytes, 0, bytes.length);
                } catch (java.io.IOException failure) { throw new ClassNotFoundException(name, failure); }
            }
            if (resolve) resolveClass(type);
            return type;
        }
    }
    public static void execute() throws Exception {
        var previous = Thread.currentThread().getContextClassLoader();
        var isolated = new WithoutEars();
        try {
            Thread.currentThread().setContextClassLoader(isolated);
            var mode = isolated.loadClass("com.naocraftlab.skins.compat.client.identifier.measurement.AvatarEarsFitMode");
            PipMaterialProbe.check(!(boolean) mode.getMethod("earsAvailable").invoke(null), "optional Ears absence detected without classloading dependency");
            PipMaterialProbe.check(mode.getMethod("ears", com.naocraftlab.skins.runtime.EffectiveSkinSource.class).invoke(null, new Object[]{null}) == null,
                    "missing required Ears supplies no partial full-body producer");
            var plugin = (IMixinConfigPlugin) isolated.loadClass("com.naocraftlab.skins.compat.client.identifier.ears.EarsMeasurementPlugin").getConstructor().newInstance();
            PipMaterialProbe.check(!plugin.shouldApplyMixin("diy.y2k.five.thermite.EarsLayerRenderer$1", "EarsMeasurementMixin")
                    && isolated.forbiddenLoads == 0, "optional hook and producer do not link missing Ears classes");
        } finally { Thread.currentThread().setContextClassLoader(previous); }
        System.out.println("EARS_OPTIONAL_ABSENCE_PASS forbiddenLoads=0");
    }
}

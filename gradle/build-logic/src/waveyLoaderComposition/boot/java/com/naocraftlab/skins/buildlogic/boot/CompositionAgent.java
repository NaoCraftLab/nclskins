package com.naocraftlab.skins.buildlogic.boot;
import java.lang.instrument.Instrumentation;
public final class CompositionAgent {
    public static Instrumentation instrumentation;
    public static void premain(String arguments, Instrumentation value) { instrumentation = value; }
}

package com.naocraftlab.skins.buildlogic.probe;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Collection;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.launch.platform.container.ContainerHandleVirtual;
import org.spongepowered.asm.launch.platform.container.IContainerHandle;
import org.spongepowered.asm.service.IClassBytecodeProvider;
import org.spongepowered.asm.service.IClassProvider;
import org.spongepowered.asm.service.IClassTracker;
import org.spongepowered.asm.service.IMixinAuditTrail;
import org.spongepowered.asm.service.ITransformerProvider;
import org.spongepowered.asm.service.MixinServiceAbstract;

public final class HeadlessMixinService extends MixinServiceAbstract
        implements IClassProvider, IClassBytecodeProvider, IClassTracker, IMixinAuditTrail {
    @Override public String getName() { return "NclSkinsHeadlessProbe"; }
    @Override public boolean isValid() { return true; }
    @Override public IClassProvider getClassProvider() { return this; }
    @Override public IClassBytecodeProvider getBytecodeProvider() { return this; }
    @Override public ITransformerProvider getTransformerProvider() { return null; }
    @Override public IClassTracker getClassTracker() { return this; }
    @Override public IMixinAuditTrail getAuditTrail() { return this; }
    @Override public Collection<String> getPlatformAgents() { return List.of(); }
    @Override public IContainerHandle getPrimaryContainer() {
        return new ContainerHandleVirtual("NCL Skins headless composition probe");
    }
    @Override public InputStream getResourceAsStream(String name) {
        return Thread.currentThread().getContextClassLoader().getResourceAsStream(name);
    }
    @Override public URL[] getClassPath() {
        String[] entries = System.getProperty("java.class.path").split(java.io.File.pathSeparator);
        return java.util.Arrays.stream(entries).map(java.io.File::new).map(file -> {
            try { return file.toURI().toURL(); }
            catch (java.net.MalformedURLException failure) { throw new IllegalStateException(failure); }
        }).toArray(URL[]::new);
    }
    @Override public Class<?> findClass(String name) throws ClassNotFoundException {
        return findClass(name, true);
    }
    @Override public Class<?> findClass(String name, boolean initialize) throws ClassNotFoundException {
        return Class.forName(name, initialize, Thread.currentThread().getContextClassLoader());
    }
    @Override public Class<?> findAgentClass(String name, boolean initialize) throws ClassNotFoundException {
        return findClass(name, initialize);
    }
    @Override public ClassNode getClassNode(String name) throws ClassNotFoundException, IOException {
        return getClassNode(name, false);
    }
    @Override public ClassNode getClassNode(String name, boolean runTransformers)
            throws ClassNotFoundException, IOException {
        String path = name.replace('.', '/') + ".class";
        try (InputStream stream = getResourceAsStream(path)) {
            if (stream == null) throw new ClassNotFoundException(name);
            ClassNode node = new ClassNode();
            new ClassReader(stream).accept(node, 0);
            return node;
        }
    }
    @Override public void registerInvalidClass(String name) { }
    @Override public boolean isClassLoaded(String name) { return false; }
    @Override public String getClassRestrictions(String name) { return ""; }
    @Override public void onApply(String target, String mixin) { }
    @Override public void onPostProcess(String target) { }
    @Override public void onGenerate(String target, String generator) { }
}

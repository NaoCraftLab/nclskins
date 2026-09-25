package com.naocraftlab.skins.buildlogic.probe;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.spongepowered.asm.service.IGlobalPropertyService;
import org.spongepowered.asm.service.IPropertyKey;

public final class HeadlessProperties implements IGlobalPropertyService {
    private final Map<String, Object> values = new ConcurrentHashMap<>();
    @Override public IPropertyKey resolveKey(String name) { return new Key(name); }
    @SuppressWarnings("unchecked")
    @Override public <T> T getProperty(IPropertyKey key) { return (T) values.get(((Key) key).name); }
    @Override public void setProperty(IPropertyKey key, Object value) {
        if (value == null) values.remove(((Key) key).name);
        else values.put(((Key) key).name, value);
    }
    @Override public <T> T getProperty(IPropertyKey key, T fallback) {
        T value = getProperty(key);
        return value == null ? fallback : value;
    }
    @Override public String getPropertyString(IPropertyKey key, String fallback) {
        Object value = getProperty(key);
        return value == null ? fallback : value.toString();
    }
    private record Key(String name) implements IPropertyKey { }
}

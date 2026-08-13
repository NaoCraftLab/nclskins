package com.naocraftlab.skins.server.plugin.bukkit;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;


final class BukkitTrackingReflection {
    private static final String MOONRISE_TRACKER_ACCESSOR = "moonrise$getTrackedEntity";

    private BukkitTrackingReflection() {
    }

    static Optional<Object> directTrackedEntity(Object entity)
            throws ReflectiveOperationException {
        Optional<Method> accessor = allMethods(entity.getClass()).stream()
                .filter(candidate -> candidate.getName().equals(MOONRISE_TRACKER_ACCESSOR))
                .filter(candidate -> candidate.getParameterCount() == 0)
                .peek(candidate -> candidate.setAccessible(true))
                .findFirst();
        if (accessor.isPresent()) {
            try {
                return Optional.ofNullable(accessor.orElseThrow().invoke(entity));
            } catch (InvocationTargetException failure) {
                if (failure.getCause() instanceof ReflectiveOperationException reflective) {
                    throw reflective;
                }
                throw failure;
            }
        }
        Optional<Field> legacyFoliaTracker = allFields(entity.getClass()).stream()
                .filter(candidate -> candidate.getName().equals("tracker"))
                .filter(candidate -> candidate.getType().getName().endsWith("$EntityTracker"))
                .peek(candidate -> candidate.setAccessible(true))
                .findFirst();
        return legacyFoliaTracker.isEmpty()
                ? Optional.empty()
                : Optional.ofNullable(legacyFoliaTracker.orElseThrow().get(entity));
    }

    static Method playerMethod(Class<?> owner, Class<?> playerType, String name)
            throws NoSuchMethodException {
        List<Method> candidates = allMethods(owner).stream()
                .filter(candidate -> candidate.getName().equals(name))
                .filter(candidate -> candidate.getParameterCount() == 1)
                .filter(candidate -> candidate.getReturnType() == void.class)
                .filter(candidate -> candidate.getParameterTypes()[0] == playerType)
                .distinct()
                .toList();
        if (candidates.size() != 1) {
            throw new NoSuchMethodException(owner.getName() + '#' + name
                    + '(' + playerType.getName() + ") exact tracking method");
        }
        Method result = candidates.get(0);
        result.setAccessible(true);
        return result;
    }

    private static List<Method> allMethods(Class<?> owner) {
        List<Method> result = new ArrayList<>();
        for (Class<?> current = owner; current != null; current = current.getSuperclass()) {
            result.addAll(List.of(current.getDeclaredMethods()));
        }
        result.addAll(List.of(owner.getMethods()));
        return result;
    }

    private static List<Field> allFields(Class<?> owner) {
        List<Field> result = new ArrayList<>();
        for (Class<?> current = owner; current != null; current = current.getSuperclass()) {
            result.addAll(List.of(current.getDeclaredFields()));
        }
        return result;
    }
}

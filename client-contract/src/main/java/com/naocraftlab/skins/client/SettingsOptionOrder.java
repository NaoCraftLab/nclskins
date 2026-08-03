package com.naocraftlab.skins.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;


public final class SettingsOptionOrder {
    private SettingsOptionOrder() {
    }


    public static <T> List<T> insertAfterFirstPresent(
            List<? extends T> options, T addition, List<? extends T> anchors) {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(addition, "addition");
        Objects.requireNonNull(anchors, "anchors");

        ArrayList<T> result = new ArrayList<>(options.size() + 1);
        result.addAll(options);
        if (identityIndexOf(result, addition) >= 0) {
            return List.copyOf(result);
        }

        int insertionIndex = result.size();
        for (T anchor : anchors) {
            int anchorIndex = identityIndexOf(result, Objects.requireNonNull(anchor, "anchor"));
            if (anchorIndex >= 0) {
                insertionIndex = anchorIndex + 1;
                break;
            }
        }
        result.add(insertionIndex, addition);
        return List.copyOf(result);
    }

    private static int identityIndexOf(List<?> values, Object expected) {
        for (int index = 0; index < values.size(); index++) {
            if (values.get(index) == expected) {
                return index;
            }
        }
        return -1;
    }
}

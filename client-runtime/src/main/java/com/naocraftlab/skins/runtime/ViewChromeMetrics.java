package com.naocraftlab.skins.runtime;


public record ViewChromeMetrics(int catalogFooterHeight) {
    public static final ViewChromeMetrics STANDARD = new ViewChromeMetrics(33);

    public ViewChromeMetrics {
        if (catalogFooterHeight < 0) {
            throw new IllegalArgumentException("catalog footer height must not be negative");
        }
    }
}

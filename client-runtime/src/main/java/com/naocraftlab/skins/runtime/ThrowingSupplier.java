package com.naocraftlab.skins.runtime;

@FunctionalInterface
    interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

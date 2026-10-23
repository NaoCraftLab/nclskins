package com.naocraftlab.skins.client;

@FunctionalInterface
public interface TextureUseValidity {
    TextureUseValidity INVALID = () -> false;

    boolean isValid();
}

package com.naocraftlab.skins.server;

import java.util.Objects;


public final class ServerRefreshCommandProtocol {
    public static final String ROOT_COMMAND = "nclskin";
    public static final String REFRESH_COMMAND = "_refresh_official_profile_v1";
    public static final String COMMAND = ROOT_COMMAND + " " + REFRESH_COMMAND;
    public static final int SUCCESS = 1;
    public static final int FAILURE = 0;

    private ServerRefreshCommandProtocol() {}


    public static boolean advertised(boolean playerSource, boolean serviceRegistered) {
        return playerSource && serviceRegistered;
    }


    public static int result(Admission admission) {
        return switch (Objects.requireNonNull(admission, "admission")) {
            case ACCEPTED, COALESCED -> SUCCESS;
            case INELIGIBLE, OVERLOADED, CLOSED -> FAILURE;
        };
    }
}

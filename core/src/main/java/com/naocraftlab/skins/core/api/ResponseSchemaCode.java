package com.naocraftlab.skins.core.api;


public enum ResponseSchemaCode {
    JSON_DOCUMENT("json.document"),
    PROFILE_ROOT("profile.root"),
    PROFILE_ID("profile.id"),
    PROFILE_NAME("profile.name"),
    PROFILE_ACTIONS("profile.profile-actions"),
    PROFILE_ACTION("profile.profile-actions.entry"),
    PROFILE_MODEL("profile.model");

    private final String diagnosticName;

    ResponseSchemaCode(String diagnosticName) {
        this.diagnosticName = diagnosticName;
    }

    public String diagnosticName() {
        return diagnosticName;
    }
}

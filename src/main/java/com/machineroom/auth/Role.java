package com.machineroom.auth;

/** Application roles. Operators come from an AD group; deputy / chief from account lists in app.properties. */
public enum Role {
    OPERATOR("經辦"),
    DEPUTY("副科"),
    CHIEF("科長");

    private final String label;

    Role(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static Role fromString(String s) {
        if (s == null) return null;
        for (Role r : values()) {
            if (r.name().equalsIgnoreCase(s.trim())) return r;
        }
        return null;
    }
}

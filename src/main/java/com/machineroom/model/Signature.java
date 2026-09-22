package com.machineroom.model;

import java.time.LocalDateTime;

import com.machineroom.db.Jdbc;

/** Who signed / reviewed / submitted something, and when. Immutable. */
public final class Signature {

    public final String user;
    public final LocalDateTime time;
    /** True when an integrator overwrote another operator's entry during the final check. */
    public final boolean integratorEdit;

    public Signature(String user, LocalDateTime time, boolean integratorEdit) {
        this.user = user;
        this.time = time;
        this.integratorEdit = integratorEdit;
    }

    public Signature(String user, LocalDateTime time) {
        this(user, time, false);
    }

    public static Signature now(String user) {
        return new Signature(user, Jdbc.now(), false);
    }

    public static Signature integrator(String user) {
        return new Signature(user, Jdbc.now(), true);
    }

    /** Null-safe: builds a signature from DB columns, or null when the user column is null. */
    public static Signature of(String user, LocalDateTime time, boolean integratorEdit) {
        return user == null ? null : new Signature(user, time, integratorEdit);
    }

    public static Signature of(String user, LocalDateTime time) {
        return of(user, time, false);
    }

    public static boolean isBy(Signature s, String user) {
        return s != null && s.user != null && s.user.equalsIgnoreCase(user);
    }
}

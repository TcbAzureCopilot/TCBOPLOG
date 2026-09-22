package com.machineroom.dao;

import java.sql.Connection;
import java.time.LocalDate;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.machineroom.auth.UserPrincipal;
import com.machineroom.db.Db;
import com.machineroom.db.Jdbc;

/** Append-only audit trail (AUDIT_LOG). Failures are logged, never propagated. */
public final class AuditDao {

    private static final Logger LOG = Logger.getLogger(AuditDao.class.getName());

    private AuditDao() {
    }

    public static void record(UserPrincipal p, String ip, String action, LocalDate date, String detail) {
        record(p.getUsername(), p.getActiveRole() == null ? null : p.getActiveRole().name(), ip, action, date, detail);
    }

    public static void record(final String user, final String role, final String ip, final String action,
                              final LocalDate date, final String detail) {
        try {
            Db.tx(new Db.Work<Void>() {
                @Override
                public Void run(Connection c) throws Exception {
                    write(c, user, role, ip, action, date, detail);
                    return null;
                }
            });
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "audit write failed: " + action, e);
        }
    }

    /** Writes inside an existing transaction. */
    public static void write(Connection c, String user, String role, String ip, String action,
                             LocalDate date, String detail) throws Exception {
        String d = detail == null ? null : (detail.length() > 2000 ? detail.substring(0, 2000) : detail);
        Jdbc.update(c,
                "INSERT INTO AUDIT_LOG (TS, USER_ID, ROLE_NAME, CLIENT_IP, ACTION, LOG_DATE, DETAIL) VALUES (?,?,?,?,?,?,?)",
                Jdbc.now(), user, role, ip, action, date, d);
    }
}

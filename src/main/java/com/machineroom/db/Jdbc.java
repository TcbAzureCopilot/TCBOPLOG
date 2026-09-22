package com.machineroom.db;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Small JDBC conveniences shared by the DAOs (null-safe setters / getters, booleans as SMALLINT). */
public final class Jdbc {

    private Jdbc() {
    }

    public static void setStr(PreparedStatement ps, int i, String v) throws SQLException {
        if (v == null) ps.setNull(i, Types.VARCHAR); else ps.setString(i, v);
    }

    public static void setInt(PreparedStatement ps, int i, Integer v) throws SQLException {
        if (v == null) ps.setNull(i, Types.INTEGER); else ps.setInt(i, v);
    }

    public static void setBool(PreparedStatement ps, int i, boolean v) throws SQLException {
        ps.setShort(i, (short) (v ? 1 : 0));
    }

    public static void setDate(PreparedStatement ps, int i, LocalDate d) throws SQLException {
        if (d == null) ps.setNull(i, Types.DATE); else ps.setDate(i, Date.valueOf(d));
    }

    public static void setTs(PreparedStatement ps, int i, LocalDateTime t) throws SQLException {
        if (t == null) ps.setNull(i, Types.TIMESTAMP); else ps.setTimestamp(i, Timestamp.valueOf(t));
    }

    public static boolean getBool(ResultSet rs, String col) throws SQLException {
        return rs.getShort(col) != 0;
    }

    public static LocalDate getDate(ResultSet rs, String col) throws SQLException {
        Date d = rs.getDate(col);
        return d == null ? null : d.toLocalDate();
    }

    public static LocalDateTime getTs(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toLocalDateTime();
    }

    /** Empty string for NULL (the UI treats "" as "not filled"). */
    public static String getStrOrEmpty(ResultSet rs, String col) throws SQLException {
        String s = rs.getString(col);
        return s == null ? "" : s;
    }

    public static int update(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        }
    }

    /** Binds heterogeneous params: String, Integer, Boolean, LocalDate, LocalDateTime, null. */
    public static void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            Object p = params[i];
            int idx = i + 1;
            if (p == null) ps.setNull(idx, Types.VARCHAR);
            else if (p instanceof String) ps.setString(idx, (String) p);
            else if (p instanceof Integer) ps.setInt(idx, (Integer) p);
            else if (p instanceof Boolean) setBool(ps, idx, (Boolean) p);
            else if (p instanceof LocalDate) setDate(ps, idx, (LocalDate) p);
            else if (p instanceof LocalDateTime) setTs(ps, idx, (LocalDateTime) p);
            else if (p instanceof Long) ps.setLong(idx, (Long) p);
            else ps.setObject(idx, p);
        }
    }

    public static LocalDateTime now() {
        // strip nanos beyond millis so DB2/H2 round-trips are stable
        LocalDateTime t = LocalDateTime.now();
        return t.withNano((t.getNano() / 1_000_000) * 1_000_000);
    }
}

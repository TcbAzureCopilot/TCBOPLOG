package com.machineroom.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.machineroom.db.Jdbc;
import com.machineroom.model.CheckDef;
import com.machineroom.model.EquipDef;
import com.machineroom.model.Shift;
import com.machineroom.model.TaskDef;

/** DEF_TASK / DEF_EQUIP / DEF_CHECK access. */
public final class DefDao {

    private DefDao() {
    }

    // ------------------------------------------------------------------ tasks

    public static List<TaskDef> tasks(Connection c) throws SQLException {
        List<TaskDef> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM DEF_TASK ORDER BY SEQ, CODE");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(readTask(rs));
        }
        return out;
    }

    public static TaskDef task(Connection c, String code) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM DEF_TASK WHERE CODE=?")) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readTask(rs) : null;
            }
        }
    }

    private static TaskDef readTask(ResultSet rs) throws SQLException {
        TaskDef t = new TaskDef();
        t.code = rs.getString("CODE");
        t.seq = rs.getInt("SEQ");
        t.name = rs.getString("NAME");
        t.schedule = rs.getString("SCHEDULE");
        t.plannedStart = rs.getString("PLANNED_START");
        t.shift = rs.getString("SHIFT");
        t.hasEnd = Jdbc.getBool(rs, "HAS_END");
        t.qtyLabel = rs.getString("QTY_LABEL");
        t.enabled = Jdbc.getBool(rs, "ENABLED");
        return t;
    }

    public static void insertTask(Connection c, TaskDef t) throws SQLException {
        Jdbc.update(c, "INSERT INTO DEF_TASK (CODE,SEQ,NAME,SCHEDULE,PLANNED_START,SHIFT,HAS_END,QTY_LABEL,ENABLED) VALUES (?,?,?,?,?,?,?,?,?)",
                t.code, t.seq, t.name, t.schedule, t.plannedStart, t.shift, t.hasEnd, blankToNull(t.qtyLabel), t.enabled);
    }

    public static void updateTask(Connection c, String oldCode, TaskDef t) throws SQLException {
        Jdbc.update(c, "UPDATE DEF_TASK SET CODE=?,SEQ=?,NAME=?,SCHEDULE=?,PLANNED_START=?,SHIFT=?,HAS_END=?,QTY_LABEL=?,ENABLED=? WHERE CODE=?",
                t.code, t.seq, t.name, t.schedule, t.plannedStart, t.shift, t.hasEnd, blankToNull(t.qtyLabel), t.enabled, oldCode);
    }

    public static void deleteTask(Connection c, String code) throws SQLException {
        Jdbc.update(c, "DELETE FROM DEF_TASK WHERE CODE=?", code);
    }

    public static int nextSeq(Connection c, String table) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COALESCE(MAX(SEQ),0)+1 FROM " + table);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    // ------------------------------------------------------------------ equipment

    public static List<EquipDef> equips(Connection c) throws SQLException {
        List<EquipDef> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM DEF_EQUIP ORDER BY SEQ, DEF_ID");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(readEquip(rs));
        }
        return out;
    }

    public static EquipDef equip(Connection c, String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM DEF_EQUIP WHERE DEF_ID=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readEquip(rs) : null;
            }
        }
    }

    private static EquipDef readEquip(ResultSet rs) throws SQLException {
        EquipDef e = new EquipDef();
        e.id = rs.getString("DEF_ID");
        e.seq = rs.getInt("SEQ");
        e.name = rs.getString("NAME");
        e.type = rs.getString("ITEM_TYPE");
        e.shift = Shift.fromKey(rs.getString("SHIFT"));
        e.time = Jdbc.getStrOrEmpty(rs, "CHECK_TIME");
        e.statusOptions = splitOptions(rs.getString("STATUS_OPTIONS"));
        e.enabled = Jdbc.getBool(rs, "ENABLED");
        return e;
    }

    public static void insertEquip(Connection c, EquipDef e) throws SQLException {
        Jdbc.update(c, "INSERT INTO DEF_EQUIP (DEF_ID,SEQ,NAME,ITEM_TYPE,SHIFT,CHECK_TIME,STATUS_OPTIONS,ENABLED) VALUES (?,?,?,?,?,?,?,?)",
                e.id, e.seq, e.name, e.type, e.shift.key(), e.time, joinOptions(e.statusOptions), e.enabled);
    }

    public static void updateEquip(Connection c, EquipDef e) throws SQLException {
        Jdbc.update(c, "UPDATE DEF_EQUIP SET SEQ=?,NAME=?,ITEM_TYPE=?,SHIFT=?,CHECK_TIME=?,STATUS_OPTIONS=?,ENABLED=? WHERE DEF_ID=?",
                e.seq, e.name, e.type, e.shift.key(), e.time, joinOptions(e.statusOptions), e.enabled, e.id);
    }

    public static void setEquipEnabled(Connection c, String id, boolean enabled) throws SQLException {
        Jdbc.update(c, "UPDATE DEF_EQUIP SET ENABLED=? WHERE DEF_ID=?", enabled, id);
    }

    // ------------------------------------------------------------------ checks

    public static List<CheckDef> checks(Connection c) throws SQLException {
        List<CheckDef> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM DEF_CHECK ORDER BY SEQ, DEF_ID");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(readCheck(rs));
        }
        return out;
    }

    public static CheckDef check(Connection c, String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM DEF_CHECK WHERE DEF_ID=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readCheck(rs) : null;
            }
        }
    }

    private static CheckDef readCheck(ResultSet rs) throws SQLException {
        CheckDef d = new CheckDef();
        d.id = rs.getString("DEF_ID");
        d.seq = rs.getInt("SEQ");
        d.name = rs.getString("NAME");
        d.type = rs.getString("ITEM_TYPE");
        d.shift = Shift.fromKey(rs.getString("SHIFT"));
        d.time = Jdbc.getStrOrEmpty(rs, "CHECK_TIME");
        d.holidaySkip = Jdbc.getBool(rs, "HOLIDAY_SKIP");
        d.enabled = Jdbc.getBool(rs, "ENABLED");
        return d;
    }

    public static void insertCheck(Connection c, CheckDef d) throws SQLException {
        Jdbc.update(c, "INSERT INTO DEF_CHECK (DEF_ID,SEQ,NAME,ITEM_TYPE,SHIFT,CHECK_TIME,HOLIDAY_SKIP,ENABLED) VALUES (?,?,?,?,?,?,?,?)",
                d.id, d.seq, d.name, d.type, d.shift.key(), d.time, d.holidaySkip, d.enabled);
    }

    public static void updateCheck(Connection c, CheckDef d) throws SQLException {
        Jdbc.update(c, "UPDATE DEF_CHECK SET SEQ=?,NAME=?,ITEM_TYPE=?,SHIFT=?,CHECK_TIME=?,HOLIDAY_SKIP=?,ENABLED=? WHERE DEF_ID=?",
                d.seq, d.name, d.type, d.shift.key(), d.time, d.holidaySkip, d.enabled, d.id);
    }

    public static void setCheckEnabled(Connection c, String id, boolean enabled) throws SQLException {
        Jdbc.update(c, "UPDATE DEF_CHECK SET ENABLED=? WHERE DEF_ID=?", enabled, id);
    }

    // ------------------------------------------------------------------ helpers

    public static List<String> splitOptions(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.trim().isEmpty()) return out;
        for (String p : s.split("\\|")) {
            if (!p.trim().isEmpty()) out.add(p.trim());
        }
        return out;
    }

    public static String joinOptions(List<String> opts) {
        if (opts == null || opts.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (String o : opts) {
            if (sb.length() > 0) sb.append('|');
            sb.append(o);
        }
        return sb.toString();
    }

    public static List<String> defaultStatusOptions() {
        return new ArrayList<>(Arrays.asList("正常", "異常"));
    }

    private static String blankToNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }
}

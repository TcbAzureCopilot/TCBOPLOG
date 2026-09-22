package com.machineroom.db;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.machineroom.config.AppConfig;

/**
 * Optional start-up helpers:
 * <ul>
 *   <li>{@code db.autoInit=true}: create the tables from {@code db/01_schema_db2.sql} if they do not exist
 *       (development with H2; on DB2 the DBA normally runs the script).</li>
 *   <li>{@code db.seedDefaultsIfEmpty=true}: load the default definitions from
 *       {@code db/02_seed_db2.sql} when DEF_TASK is empty.</li>
 * </ul>
 */
public final class SchemaInitializer {

    private static final Logger LOG = Logger.getLogger(SchemaInitializer.class.getName());

    private SchemaInitializer() {
    }

    public static void run() {
        AppConfig cfg = AppConfig.get();
        try (Connection c = Db.open()) {
            if (!tableExists(c, "DEF_TASK")) {
                if (cfg.dbAutoInit()) {
                    LOG.warning("Tables not found; db.autoInit=true → creating schema");
                    execScript(c, "db/01_schema_db2.sql");
                    c.commit();
                } else {
                    LOG.severe("Table DEF_TASK not found and db.autoInit=false. Run db/01_schema_db2.sql on DB2 first.");
                    return;
                }
            }
            if (cfg.dbSeedDefaultsIfEmpty() && countRows(c, "DEF_TASK") == 0) {
                LOG.info("DEF_TASK is empty → loading default definitions");
                execScript(c, "db/02_seed_db2.sql");
                c.commit();
            }
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Schema initialisation failed", e);
        }
    }

    private static boolean tableExists(Connection c, String table) {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM " + table + " WHERE 1=0");
             ResultSet rs = ps.executeQuery()) {
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private static int countRows(Connection c, String table) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + table);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** Executes a classpath SQL script: statements terminated by ';' at end of line, '--' comments ignored. */
    public static void execScript(Connection c, String resource) throws SQLException, IOException {
        List<String> statements = readStatements(resource);
        try (Statement st = c.createStatement()) {
            for (String sql : statements) {
                st.execute(sql);
            }
        }
        LOG.info("Executed " + statements.size() + " statements from " + resource);
    }

    static List<String> readStatements(String resource) throws IOException {
        InputStream in = SchemaInitializer.class.getClassLoader().getResourceAsStream(resource);
        if (in == null) throw new IOException("Resource not found on classpath: " + resource);
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("--")) continue;
                // strip trailing inline comment (no string literal in this project's DDL contains '--')
                int cm = t.indexOf("--");
                if (cm > 0 && !t.substring(0, cm).contains("'")) t = t.substring(0, cm).trim();
                cur.append(t).append(' ');
                if (t.endsWith(";")) {
                    String sql = cur.toString().trim();
                    out.add(sql.substring(0, sql.length() - 1));
                    cur.setLength(0);
                }
            }
        }
        if (cur.toString().trim().length() > 0) out.add(cur.toString().trim());
        return out;
    }
}

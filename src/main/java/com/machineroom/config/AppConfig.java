package com.machineroom.config;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.naming.InitialContext;

/**
 * External configuration (app.properties), resolved in this order:
 * <ol>
 *   <li>JVM system property {@code machineroom.config}</li>
 *   <li>Environment variable {@code MACHINEROOM_CONFIG}</li>
 *   <li>JNDI env-entry {@code java:comp/env/machineroom/config} (web.xml, overridable in the WAS console)</li>
 *   <li>{@code ${user.home}/machineroom/app.properties}</li>
 * </ol>
 * The file is re-read automatically when its modification time changes (checked at most once per minute),
 * so AD group names / chief & deputy accounts can be edited without a restart.
 * If no file is found the application runs "fail closed": LDAP mode with no servers, i.e. nobody can log in.
 */
public final class AppConfig {

    private static final Logger LOG = Logger.getLogger(AppConfig.class.getName());
    private static final long RECHECK_MS = 60_000L;

    private static volatile AppConfig current;
    private static volatile long lastCheck;

    private final Properties props;
    private final File file;
    private final long mtime;

    private AppConfig(Properties props, File file, long mtime) {
        this.props = props;
        this.file = file;
        this.mtime = mtime;
    }

    /** Current configuration, reloading from disk if the file changed. */
    public static AppConfig get() {
        AppConfig c = current;
        long now = System.currentTimeMillis();
        if (c == null || now - lastCheck > RECHECK_MS) {
            synchronized (AppConfig.class) {
                c = current;
                if (c == null || now - lastCheck > RECHECK_MS) {
                    lastCheck = now;
                    File f = resolveFile();
                    long mt = (f != null && f.isFile()) ? f.lastModified() : -1L;
                    if (c == null || mt != c.mtime || !sameFile(f, c.file)) {
                        c = load(f, mt);
                        current = c;
                    }
                }
            }
        }
        return c;
    }

    /** Force a reload (used by tests). */
    public static synchronized void reload() {
        current = null;
        lastCheck = 0L;
        get();
    }

    private static boolean sameFile(File a, File b) {
        if (a == null || b == null) return a == b;
        return a.getAbsolutePath().equals(b.getAbsolutePath());
    }

    private static AppConfig load(File f, long mtime) {
        Properties p = new Properties();
        if (f != null && f.isFile()) {
            try (InputStream in = new FileInputStream(f);
                 Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                p.load(r);
                LOG.info("Loaded configuration from " + f.getAbsolutePath());
            } catch (IOException e) {
                LOG.log(Level.SEVERE, "Cannot read configuration file " + f.getAbsolutePath(), e);
            }
        } else {
            LOG.severe("No configuration file found (machineroom.config / MACHINEROOM_CONFIG / "
                    + "java:comp/env/machineroom/config / ~/machineroom/app.properties). "
                    + "Running fail-closed: no one can log in until a configuration file is provided.");
        }
        return new AppConfig(p, f, mtime);
    }

    private static File resolveFile() {
        String path = System.getProperty("machineroom.config");
        if (isBlank(path)) path = System.getenv("MACHINEROOM_CONFIG");
        if (isBlank(path)) {
            try {
                Object o = new InitialContext().lookup("java:comp/env/machineroom/config");
                if (o != null) path = o.toString();
            } catch (Exception ignore) {
                // not running in a container, or env-entry not set
            }
        }
        if (isBlank(path)) {
            path = System.getProperty("user.home") + File.separator + "machineroom" + File.separator + "app.properties";
        }
        return new File(path.trim());
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    // ------------------------------------------------------------------ generic accessors

    public String str(String key, String def) {
        String v = props.getProperty(key);
        return isBlank(v) ? def : v.trim();
    }

    public int intVal(String key, int def) {
        try {
            return Integer.parseInt(str(key, String.valueOf(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public boolean bool(String key, boolean def) {
        String v = str(key, null);
        if (v == null) return def;
        return v.equalsIgnoreCase("true") || v.equalsIgnoreCase("yes") || v.equals("1");
    }

    /** Comma-separated list, trimmed, blanks removed. */
    public List<String> list(String key) {
        return split(str(key, ""), ",");
    }

    private static List<String> split(String v, String sep) {
        if (isBlank(v)) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (String s : v.split(sep)) {
            if (!isBlank(s)) out.add(s.trim());
        }
        return out;
    }

    public File file() {
        return file;
    }

    // ------------------------------------------------------------------ typed accessors

    public String appTitle() { return str("app.title", "機房操作日誌系統"); }
    public String orgName() { return str("app.org", "合作金庫資訊部"); }
    public int logicalDayCutoffHour() { return Math.max(0, Math.min(23, intVal("app.logicalDayCutoffHour", 7))); }
    public int pollSeconds() { return Math.max(2, intVal("app.pollSeconds", 5)); }

    public String authMode() { return str("auth.mode", "ldap").toLowerCase(Locale.ROOT); }
    public boolean isDevAuth() { return "dev".equals(authMode()); }
    /** {@code auth.mode=bypass}: no password check at all — trial / demo installations only. */
    public boolean isBypassAuth() { return "bypass".equals(authMode()) || "none".equals(authMode()); }
    /** {@code account:ROLE1|ROLE2[:displayName]} entries; accounts not listed get {@link #bypassDefaultRoles()}. */
    public List<String> bypassUsers() { return list("bypass.users"); }
    public String bypassDefaultRoles() { return str("bypass.defaultRoles", "OPERATOR|DEPUTY|CHIEF"); }
    /** Account to log in as automatically when the login page is opened (blank = show the form). */
    public String bypassAutoLogin() { return str("bypass.autoLogin", ""); }

    public List<String> ldapUrls() { return list("ldap.urls"); }
    public String ldapUpnSuffix() { return str("ldap.upnSuffix", ""); }
    public String ldapNetbiosDomain() { return str("ldap.netbiosDomain", ""); }
    public String ldapBindFormat() { return str("ldap.bindFormat", "upn").toLowerCase(Locale.ROOT); }
    public String ldapBaseDn() { return str("ldap.baseDn", ""); }
    public String ldapServiceUser() { return str("ldap.serviceUser", ""); }
    public String ldapServicePassword() { return str("ldap.servicePassword", ""); }
    public String ldapTruststore() { return str("ldap.truststore", ""); }
    public String ldapTruststorePassword() { return str("ldap.truststorePassword", ""); }
    public String ldapTruststoreType() { return str("ldap.truststoreType", "JKS"); }
    public boolean ldapNestedGroups() { return bool("ldap.nestedGroups", true); }
    public int ldapConnectTimeoutMs() { return intVal("ldap.connectTimeoutMs", 5000); }
    public int ldapReadTimeoutMs() { return intVal("ldap.readTimeoutMs", 10000); }
    public String ldapDisplayNameAttr() { return str("ldap.displayNameAttr", "displayName"); }

    public List<String> operatorGroups() { return list("roles.operatorGroups"); }
    public List<String> deputyAccounts() { return list("roles.deputyAccounts"); }
    public List<String> chiefAccounts() { return list("roles.chiefAccounts"); }
    public List<String> adminAccounts() { return list("roles.adminAccounts"); }
    public List<String> adminGroups() { return list("roles.adminGroups"); }

    public List<String> allowedClientIps() { return list("security.allowedClientIps"); }
    public boolean trustProxyHeader() { return bool("security.trustProxyHeader", false); }

    public String dbJndiName() { return str("db.jndiName", "jdbc/MachineRoomDS"); }
    /** Direct JDBC URL (embedded / Docker build only; the WAS build uses JNDI). */
    public String dbUrl() { return str("db.url", "jdbc:h2:file:./data/tcboplog;MODE=DB2"); }
    public String dbUser() { return str("db.user", "sa"); }
    public String dbPassword() { return str("db.password", ""); }
    public boolean dbAutoInit() { return bool("db.autoInit", false); }
    public boolean dbSeedDefaultsIfEmpty() { return bool("db.seedDefaultsIfEmpty", true); }

    /** Semicolon-separated font candidates (a path may contain a comma for the TTC index). */
    public List<String> pdfFontPaths() { return split(str("pdf.fontPaths", ""), ";"); }
    public String pdfFontName() { return str("pdf.fontName", ""); }

    public List<String> devUsers() { return list("dev.users"); }
}

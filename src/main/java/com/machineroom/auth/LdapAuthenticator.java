package com.machineroom.auth;

import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.naming.AuthenticationException;
import javax.naming.CommunicationException;
import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.PartialResultException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import javax.naming.ldap.InitialLdapContext;
import javax.naming.ldap.LdapContext;

import com.machineroom.config.AppConfig;

/**
 * Active Directory authentication over LDAPS.
 * <ol>
 *   <li>Bind with the user's own credentials (UPN {@code user@suffix} or {@code DOMAIN\\user}).</li>
 *   <li>Look the user up (sAMAccountName) to obtain displayName, distinguishedName and memberOf.</li>
 *   <li>Resolve each configured operator/admin group to a DN and, if {@code ldap.nestedGroups=true},
 *       test membership with the AD matching rule {@code LDAP_MATCHING_RULE_IN_CHAIN}.</li>
 * </ol>
 * Group lookups use the optional service account if configured, otherwise the user's own connection.
 */
public class LdapAuthenticator implements Authenticator {

    private static final Logger LOG = Logger.getLogger(LdapAuthenticator.class.getName());
    private static final String IN_CHAIN = "1.2.840.113556.1.4.1941";

    @Override
    public DirectoryUser authenticate(String username, String password) throws AuthException {
        AppConfig cfg = AppConfig.get();
        String user = sanitize(username);
        if (user.isEmpty() || password == null || password.isEmpty()) {
            throw new AuthException("請輸入帳號與密碼");
        }
        List<String> urls = cfg.ldapUrls();
        if (urls.isEmpty()) {
            throw new AuthException("系統尚未設定 AD 伺服器（ldap.urls），請聯絡系統管理者");
        }

        AuthException last = null;
        for (String url : urls) {
            LdapContext userCtx = null;
            LdapContext searchCtx = null;
            try {
                userCtx = bind(cfg, url, bindName(cfg, user), password);
                searchCtx = userCtx;
                if (!cfg.ldapServiceUser().isEmpty()) {
                    try {
                        searchCtx = bind(cfg, url, bindName(cfg, cfg.ldapServiceUser()), cfg.ldapServicePassword());
                    } catch (AuthException e) {
                        LOG.warning("LDAP service account bind failed, falling back to user context: " + e.getMessage());
                        searchCtx = userCtx;
                    }
                }
                return lookup(cfg, searchCtx, user);
            } catch (AuthException e) {
                last = e;
                // credentials rejected → do not try other servers with the same password
                if (e.getCause() instanceof AuthenticationException) throw e;
            } finally {
                close(searchCtx != null && searchCtx != userCtx ? searchCtx : null);
                close(userCtx);
            }
        }
        throw last != null ? last : new AuthException("無法連線 AD 伺服器");
    }

    // ------------------------------------------------------------------ helpers

    private static String sanitize(String username) {
        if (username == null) return "";
        String u = username.trim();
        // Accept "DOMAIN\\user" or "user@domain" and reduce to the plain account name
        int bs = u.lastIndexOf('\\');
        if (bs >= 0) u = u.substring(bs + 1);
        int at = u.indexOf('@');
        if (at > 0) u = u.substring(0, at);
        // sAMAccountName may not contain LDAP filter metacharacters
        return u.replaceAll("[\\*\\(\\)\\\\\\u0000/]", "");
    }

    private static String bindName(AppConfig cfg, String user) {
        if ("netbios".equals(cfg.ldapBindFormat()) && !cfg.ldapNetbiosDomain().isEmpty()) {
            return cfg.ldapNetbiosDomain() + "\\" + user;
        }
        return cfg.ldapUpnSuffix().isEmpty() ? user : user + "@" + cfg.ldapUpnSuffix();
    }

    private static LdapContext bind(AppConfig cfg, String url, String principal, String password) throws AuthException {
        Hashtable<String, Object> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, url);
        env.put(Context.SECURITY_AUTHENTICATION, "simple");
        env.put(Context.SECURITY_PRINCIPAL, principal);
        env.put(Context.SECURITY_CREDENTIALS, password);
        env.put(Context.REFERRAL, "ignore");
        env.put("com.sun.jndi.ldap.connect.timeout", String.valueOf(cfg.ldapConnectTimeoutMs()));
        env.put("com.sun.jndi.ldap.read.timeout", String.valueOf(cfg.ldapReadTimeoutMs()));
        env.put("com.sun.jndi.ldap.connect.pool", "false");
        if (url.toLowerCase(Locale.ROOT).startsWith("ldaps://")) {
            env.put(Context.SECURITY_PROTOCOL, "ssl");
            if (!cfg.ldapTruststore().isEmpty()) {
                env.put("java.naming.ldap.factory.socket", TrustStoreSocketFactory.class.getName());
            }
        }
        try {
            return new InitialLdapContext(env, null);
        } catch (AuthenticationException e) {
            throw new AuthException("帳號或密碼錯誤", e);
        } catch (CommunicationException e) {
            LOG.log(Level.WARNING, "LDAP connection failed: " + url, e);
            throw new AuthException("無法連線 AD 伺服器 (" + url + ")", e);
        } catch (NamingException e) {
            LOG.log(Level.WARNING, "LDAP bind failed: " + url, e);
            throw new AuthException("AD 驗證失敗：" + e.getMessage(), e);
        }
    }

    private static void close(LdapContext ctx) {
        if (ctx != null) {
            try { ctx.close(); } catch (NamingException ignore) { /* nothing */ }
        }
    }

    private static DirectoryUser lookup(AppConfig cfg, LdapContext ctx, String user) throws AuthException {
        SearchControls sc = new SearchControls();
        sc.setSearchScope(SearchControls.SUBTREE_SCOPE);
        sc.setReturningAttributes(new String[] {"distinguishedName", "memberOf", cfg.ldapDisplayNameAttr(), "sAMAccountName"});
        String filter = "(&(objectClass=user)(sAMAccountName=" + escapeFilter(user) + "))";

        String dn = null;
        String display = null;
        String canonical = user;
        Set<String> groups = new HashSet<>();
        try {
            NamingEnumeration<SearchResult> res = ctx.search(cfg.ldapBaseDn(), filter, sc);
            try {
                if (res.hasMore()) {
                    SearchResult sr = res.next();
                    Attributes a = sr.getAttributes();
                    dn = firstValue(a, "distinguishedName");
                    if (dn == null) dn = sr.getNameInNamespace();
                    display = firstValue(a, cfg.ldapDisplayNameAttr());
                    String sam = firstValue(a, "sAMAccountName");
                    if (sam != null) canonical = sam;
                    Attribute mo = a.get("memberOf");
                    if (mo != null) {
                        NamingEnumeration<?> vals = mo.getAll();
                        while (vals.hasMore()) {
                            String g = String.valueOf(vals.next());
                            groups.add(g.toLowerCase(Locale.ROOT));
                            String cn = cnOf(g);
                            if (cn != null) groups.add(cn.toLowerCase(Locale.ROOT));
                        }
                    }
                }
            } finally {
                try { res.close(); } catch (NamingException ignore) { /* nothing */ }
            }
        } catch (PartialResultException e) {
            // referrals to other domain partitions are ignored on purpose
            LOG.fine("Partial result ignored: " + e.getMessage());
        } catch (NamingException e) {
            LOG.log(Level.WARNING, "LDAP user lookup failed for " + user, e);
            throw new AuthException("AD 查詢使用者失敗：" + e.getMessage(), e);
        }
        if (dn == null) {
            throw new AuthException("AD 中找不到帳號 " + user + "（請確認 ldap.baseDn）");
        }

        // Nested-group check for every configured group: add the configured spelling to the set on success,
        // so RoleResolver can match on it regardless of DN vs CN form.
        if (cfg.ldapNestedGroups()) {
            Set<String> configured = new HashSet<>();
            configured.addAll(cfg.operatorGroups());
            configured.addAll(cfg.adminGroups());
            for (String g : configured) {
                String gdn = g.contains("=") ? g : resolveGroupDn(cfg, ctx, g);
                if (gdn == null) continue;
                if (isMemberInChain(cfg, ctx, dn, gdn)) {
                    groups.add(g.toLowerCase(Locale.ROOT));
                    groups.add(gdn.toLowerCase(Locale.ROOT));
                }
            }
        }
        return new DirectoryUser(canonical, display, groups, null);
    }

    private static String resolveGroupDn(AppConfig cfg, LdapContext ctx, String name) {
        SearchControls sc = new SearchControls();
        sc.setSearchScope(SearchControls.SUBTREE_SCOPE);
        sc.setReturningAttributes(new String[] {"distinguishedName"});
        String f = "(&(objectClass=group)(|(cn=" + escapeFilter(name) + ")(sAMAccountName=" + escapeFilter(name) + ")))";
        try {
            NamingEnumeration<SearchResult> res = ctx.search(cfg.ldapBaseDn(), f, sc);
            try {
                if (res.hasMore()) {
                    SearchResult sr = res.next();
                    String dn = firstValue(sr.getAttributes(), "distinguishedName");
                    return dn != null ? dn : sr.getNameInNamespace();
                }
            } finally {
                try { res.close(); } catch (NamingException ignore) { /* nothing */ }
            }
        } catch (PartialResultException ignore) {
            // nothing
        } catch (NamingException e) {
            LOG.log(Level.WARNING, "Cannot resolve group " + name, e);
        }
        return null;
    }

    private static boolean isMemberInChain(AppConfig cfg, LdapContext ctx, String userDn, String groupDn) {
        SearchControls sc = new SearchControls();
        sc.setSearchScope(SearchControls.OBJECT_SCOPE);
        sc.setReturningAttributes(new String[] {"cn"});
        String f = "(memberOf:" + IN_CHAIN + ":=" + escapeFilter(groupDn) + ")";
        try {
            NamingEnumeration<SearchResult> res = ctx.search(userDn, f, sc);
            try {
                return res.hasMore();
            } finally {
                try { res.close(); } catch (NamingException ignore) { /* nothing */ }
            }
        } catch (PartialResultException e) {
            return false;
        } catch (NamingException e) {
            LOG.log(Level.WARNING, "Nested group check failed for " + groupDn, e);
            return false;
        }
    }

    private static String firstValue(Attributes a, String name) throws NamingException {
        if (a == null) return null;
        Attribute at = a.get(name);
        if (at == null || at.size() == 0) return null;
        Object v = at.get();
        return v == null ? null : v.toString();
    }

    /** CN part of a DN such as {@code CN=Group,OU=x,DC=y}. */
    static String cnOf(String dn) {
        if (dn == null) return null;
        String s = dn.trim();
        if (!s.regionMatches(true, 0, "CN=", 0, 3)) return null;
        int end = s.indexOf(',');
        return end < 0 ? s.substring(3) : s.substring(3, end);
    }

    /** RFC 4515 filter escaping. */
    static String escapeFilter(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '\\': sb.append("\\5c"); break;
                case '*': sb.append("\\2a"); break;
                case '(': sb.append("\\28"); break;
                case ')': sb.append("\\29"); break;
                case ' ': sb.append("\\00"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }
}

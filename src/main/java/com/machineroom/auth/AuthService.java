package com.machineroom.auth;

import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.machineroom.config.AppConfig;
import com.machineroom.model.Shift;

/** Turns credentials into a {@link UserPrincipal}: authenticate, then map directory facts to roles. */
public final class AuthService {

    private AuthService() {
    }

    private static Authenticator authenticator() {
        AppConfig cfg = AppConfig.get();
        if (cfg.isBypassAuth()) return new BypassAuthenticator();
        if (cfg.isDevAuth()) return new DevAuthenticator();
        return new LdapAuthenticator();
    }

    /**
     * @param requestedRole optional role chosen on the login page; must be one the account holds
     * @param requestedShift optional shift (operators only); defaults to the shift on duty now
     */
    public static UserPrincipal login(String username, String password, Role requestedRole,
                                      Shift requestedShift, String clientIp) throws AuthException {
        Authenticator.DirectoryUser du = authenticator().authenticate(username, password);
        AppConfig cfg = AppConfig.get();

        Set<Role> roles = du.directRoles != null ? EnumSet.copyOf(du.directRoles) : EnumSet.noneOf(Role.class);
        if (du.directRoles == null) {
            if (matchesAnyGroup(du.groups, cfg.operatorGroups())) roles.add(Role.OPERATOR);
        }
        if (containsIgnoreCase(cfg.deputyAccounts(), du.username)) roles.add(Role.DEPUTY);
        if (containsIgnoreCase(cfg.chiefAccounts(), du.username)) roles.add(Role.CHIEF);
        if (roles.isEmpty()) {
            throw new AuthException("帳號 " + du.username + " 未被授權使用本系統（不在經辦群組，也非副科 / 科長）");
        }

        boolean admin;
        if (cfg.adminAccounts().isEmpty() && cfg.adminGroups().isEmpty()) {
            admin = roles.contains(Role.DEPUTY) || roles.contains(Role.CHIEF);
        } else {
            admin = containsIgnoreCase(cfg.adminAccounts(), du.username) || matchesAnyGroup(du.groups, cfg.adminGroups());
        }

        Role active;
        if (requestedRole != null) {
            if (!roles.contains(requestedRole)) {
                throw new AuthException("帳號 " + du.username + " 沒有「" + requestedRole.label() + "」角色");
            }
            active = requestedRole;
        } else if (roles.contains(Role.OPERATOR)) {
            active = Role.OPERATOR;
        } else if (roles.contains(Role.DEPUTY)) {
            active = Role.DEPUTY;
        } else {
            active = Role.CHIEF;
        }

        Shift shift = null;
        if (active == Role.OPERATOR) {
            shift = requestedShift != null ? requestedShift : Shift.forTime(LocalTime.now());
        }
        return new UserPrincipal(du.username, du.displayName, roles, admin, active, shift, clientIp);
    }

    private static boolean containsIgnoreCase(List<String> list, String value) {
        for (String s : list) {
            if (s.equalsIgnoreCase(value)) return true;
        }
        return false;
    }

    /** groups are lower-cased DNs and CNs; configured entries may be DN or CN/sAMAccountName. */
    private static boolean matchesAnyGroup(Set<String> groups, List<String> configured) {
        for (String c : configured) {
            String lc = c.toLowerCase(Locale.ROOT);
            if (groups.contains(lc)) return true;
            String cn = LdapAuthenticator.cnOf(c);
            if (cn != null && groups.contains(cn.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }
}

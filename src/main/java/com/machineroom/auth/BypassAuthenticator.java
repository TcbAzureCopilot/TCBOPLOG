package com.machineroom.auth;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import com.machineroom.config.AppConfig;

/**
 * {@code auth.mode=bypass}: accepts any account name without a password (trial / demo use).
 * Roles come from {@code bypass.users} ({@code account:ROLE1|ROLE2[:displayName]}) or, for
 * accounts not listed there, from {@code bypass.defaultRoles}.
 */
public class BypassAuthenticator implements Authenticator {

    @Override
    public DirectoryUser authenticate(String username, String password) throws AuthException {
        AppConfig cfg = AppConfig.get();
        String u = username == null ? "" : username.trim();
        if (u.isEmpty()) u = cfg.bypassAutoLogin().isEmpty() ? "tester" : cfg.bypassAutoLogin();
        if (!u.matches("[A-Za-z0-9._\\-]{1,64}")) throw new AuthException("帳號只能包含英數字、點、底線、減號");

        for (String entry : cfg.bypassUsers()) {
            String[] p = entry.split(":", -1);
            if (p.length < 2 || !p[0].trim().equalsIgnoreCase(u)) continue;
            String display = p.length > 2 && !p[2].trim().isEmpty() ? p[2].trim() : p[0].trim();
            return new DirectoryUser(p[0].trim(), display, Collections.<String>emptySet(), roles(p[1]));
        }
        Set<Role> roles = roles(cfg.bypassDefaultRoles());
        if (roles.isEmpty()) throw new AuthException("bypass.defaultRoles 未設定任何有效角色");
        return new DirectoryUser(u, u, Collections.<String>emptySet(), roles);
    }

    private static Set<Role> roles(String spec) {
        Set<Role> roles = EnumSet.noneOf(Role.class);
        for (String r : spec.split("\\|")) {
            Role role = Role.fromString(r);
            if (role != null) roles.add(role);
        }
        return roles;
    }
}

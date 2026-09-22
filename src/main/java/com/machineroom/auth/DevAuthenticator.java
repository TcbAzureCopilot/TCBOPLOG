package com.machineroom.auth;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import com.machineroom.config.AppConfig;

/**
 * Development / SIT authenticator driven by {@code dev.users} in app.properties:
 * {@code account:password:ROLE1|ROLE2[:displayName]}. Active only when {@code auth.mode=dev}.
 */
public class DevAuthenticator implements Authenticator {

    @Override
    public DirectoryUser authenticate(String username, String password) throws AuthException {
        if (username == null || username.trim().isEmpty() || password == null) {
            throw new AuthException("請輸入帳號與密碼");
        }
        String u = username.trim();
        for (String entry : AppConfig.get().devUsers()) {
            String[] p = entry.split(":", -1);
            if (p.length < 3) continue;
            if (!p[0].trim().equalsIgnoreCase(u)) continue;
            if (!p[1].equals(password)) throw new AuthException("帳號或密碼錯誤");
            Set<Role> roles = EnumSet.noneOf(Role.class);
            for (String r : p[2].split("\\|")) {
                Role role = Role.fromString(r);
                if (role != null) roles.add(role);
            }
            String display = p.length > 3 ? p[3].trim() : p[0].trim();
            return new DirectoryUser(p[0].trim(), display, Collections.<String>emptySet(), roles);
        }
        throw new AuthException("帳號或密碼錯誤");
    }
}

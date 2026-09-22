package com.machineroom.auth;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import com.machineroom.model.Shift;

/** Authenticated user stored in the HTTP session. */
public final class UserPrincipal implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String username;
    private final String displayName;
    private final Set<Role> roles;
    private final boolean admin;
    private final Role activeRole;
    private Shift shift;
    private final LocalDateTime loginTime;
    private final String clientIp;

    public UserPrincipal(String username, String displayName, Set<Role> roles, boolean admin,
                         Role activeRole, Shift shift, String clientIp) {
        this.username = username;
        this.displayName = displayName == null || displayName.isEmpty() ? username : displayName;
        this.roles = roles.isEmpty() ? EnumSet.noneOf(Role.class) : EnumSet.copyOf(roles);
        this.admin = admin;
        this.activeRole = activeRole;
        this.shift = shift;
        this.loginTime = LocalDateTime.now();
        this.clientIp = clientIp;
    }

    public String getUsername() { return username; }
    public String getDisplayName() { return displayName; }
    public Set<Role> getRoles() { return Collections.unmodifiableSet(roles); }
    public boolean isAdmin() { return admin; }
    public Role getActiveRole() { return activeRole; }
    public Shift getShift() { return shift; }
    public void setShift(Shift shift) { this.shift = shift; }
    public LocalDateTime getLoginTime() { return loginTime; }
    public String getClientIp() { return clientIp; }

    public boolean isOperator() { return activeRole == Role.OPERATOR; }
    public boolean isDeputy() { return activeRole == Role.DEPUTY; }
    public boolean isChief() { return activeRole == Role.CHIEF; }
    /** Deputy or chief. */
    public boolean isReviewer() { return activeRole == Role.DEPUTY || activeRole == Role.CHIEF; }

    @Override
    public String toString() {
        return username + "(" + activeRole + ")";
    }
}

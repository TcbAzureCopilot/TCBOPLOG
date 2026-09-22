package com.machineroom.auth;

import java.util.Set;

/** Verifies credentials and returns the directory facts needed to resolve roles. */
public interface Authenticator {

    /** Result of a successful credential check. */
    final class DirectoryUser {
        public final String username;      // canonical sAMAccountName
        public final String displayName;
        /** Group identities the user belongs to: DNs and/or plain names, lower-cased by the resolver. */
        public final Set<String> groups;
        /** Roles granted directly by the authenticator (dev mode only); null for LDAP. */
        public final Set<Role> directRoles;

        public DirectoryUser(String username, String displayName, Set<String> groups, Set<Role> directRoles) {
            this.username = username;
            this.displayName = displayName;
            this.groups = groups;
            this.directRoles = directRoles;
        }
    }

    DirectoryUser authenticate(String username, String password) throws AuthException;
}

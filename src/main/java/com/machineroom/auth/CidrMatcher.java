package com.machineroom.auth;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/** Matches IPv4 / IPv6 addresses against a list of {@code ip} or {@code ip/prefix} entries. */
public final class CidrMatcher {

    private static final class Rule {
        final byte[] net;
        final int prefix;

        Rule(byte[] net, int prefix) {
            this.net = net;
            this.prefix = prefix;
        }
    }

    private final List<Rule> rules = new ArrayList<>();

    public CidrMatcher(List<String> entries) {
        for (String e : entries) {
            String s = e.trim();
            if (s.isEmpty()) continue;
            int slash = s.indexOf('/');
            String ip = slash < 0 ? s : s.substring(0, slash);
            try {
                byte[] b = InetAddress.getByName(ip).getAddress();
                int prefix = slash < 0 ? b.length * 8 : Integer.parseInt(s.substring(slash + 1));
                rules.add(new Rule(b, Math.max(0, Math.min(b.length * 8, prefix))));
            } catch (UnknownHostException | NumberFormatException ex) {
                throw new IllegalArgumentException("Invalid IP/CIDR entry: " + s, ex);
            }
        }
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }

    public boolean matches(String address) {
        byte[] a;
        try {
            a = InetAddress.getByName(address).getAddress();
        } catch (UnknownHostException e) {
            return false;
        }
        for (Rule r : rules) {
            if (r.net.length != a.length) {
                // allow IPv4-mapped comparisons: ::ffff:a.b.c.d vs a.b.c.d
                byte[] a4 = toV4(a), n4 = toV4(r.net);
                if (a4 == null || n4 == null) continue;
                if (prefixMatch(a4, n4, Math.min(32, r.prefix - (r.net.length == 16 ? 96 : 0)))) return true;
                continue;
            }
            if (prefixMatch(a, r.net, r.prefix)) return true;
        }
        return false;
    }

    private static byte[] toV4(byte[] b) {
        if (b.length == 4) return b;
        if (b.length == 16) {
            for (int i = 0; i < 10; i++) if (b[i] != 0) return null;
            if (b[10] != (byte) 0xff || b[11] != (byte) 0xff) return null;
            return new byte[] {b[12], b[13], b[14], b[15]};
        }
        return null;
    }

    private static boolean prefixMatch(byte[] a, byte[] net, int prefix) {
        if (prefix <= 0) return true;
        int full = prefix / 8, rem = prefix % 8;
        for (int i = 0; i < full; i++) if (a[i] != net[i]) return false;
        if (rem == 0) return true;
        int mask = 0xff << (8 - rem);
        return (a[full] & mask) == (net[full] & mask);
    }
}

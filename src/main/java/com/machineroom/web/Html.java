package com.machineroom.web;

/** HTML escaping for server-rendered pages (login, print view). */
public final class Html {

    private Html() {
    }

    public static String esc(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&#39;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Escaped value or an em-dash placeholder when empty. */
    public static String orDash(String s) {
        return s == null || s.isEmpty() ? "—" : esc(s);
    }
}

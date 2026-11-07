package io.freemytv;

/** Official tags carry the increasing build number; the visible version stays 1.0. */
final class UpdateRelease {
    private UpdateRelease() {}

    static long code(String tag) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("v1\\.0-official-([0-9]+)-[0-9]+-[0-9]+")
                .matcher(tag);
        if (!m.matches()) throw new IllegalArgumentException("Not an official OUSWIS release");
        long code = Long.parseLong(m.group(1));
        if (code < 10003 || code > Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid release build");
        return code;
    }
}

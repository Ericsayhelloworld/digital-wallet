package com.example.notification.event;

/** Section 5.8: $ + dollars with a comma between groups of three digits + a point + two digits, e.g. $1,250.00 */
public final class Money {

    private Money() {
    }

    public static String format(long cents) {
        return String.format(java.util.Locale.US, "$%,d.%02d", cents / 100, cents % 100);
    }
}

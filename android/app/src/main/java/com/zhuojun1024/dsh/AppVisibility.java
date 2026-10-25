package com.zhuojun1024.dsh;

/**
 * Process-wide foreground flag. The monitor suppresses completion notifications while any
 * Activity is started, so a turn finished on screen is not announced again.
 */
final class AppVisibility {
    private static volatile boolean foreground;

    private AppVisibility() {}

    static void setForeground(boolean value) {
        foreground = value;
    }

    static boolean isForeground() {
        return foreground;
    }
}

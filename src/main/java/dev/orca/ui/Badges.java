package dev.orca.ui;

import com.googlecode.lanterna.TextColor;

/**
 * Compact, colour-coded labels that make state readable at a glance.
 */
public final class Badges {

    private Badges() {
    }

    public static String containerState(boolean running, String status) {
        String lower = status == null ? "" : status.toLowerCase();
        if (running) {
            if (lower.contains("unhealthy")) {
                return "● BAD ";
            }
            if (lower.contains("healthy")) {
                return "● OK  ";
            }
            if (lower.contains("restarting")) {
                return "● …   ";
            }
            return "● RUN ";
        }
        if (lower.contains("paused")) {
            return "○ HOLD";
        }
        if (lower.contains("exited") || lower.contains("dead")) {
            return "○ EXIT";
        }
        if (lower.contains("created")) {
            return "○ NEW ";
        }
        return "○ STOP";
    }

    public static TextColor containerColor(boolean running, String status) {
        String lower = status == null ? "" : status.toLowerCase();
        if (running) {
            if (lower.contains("unhealthy")) {
                return Palette.STOPPED;
            }
            if (lower.contains("restarting")) {
                return Palette.WARNING;
            }
            return Palette.RUNNING;
        }
        if (lower.contains("paused")) {
            return Palette.WARNING;
        }
        return Palette.STOPPED;
    }

    /** Short uptime / exit summary from Docker's status string. */
    public static String containerDetail(String status) {
        if (status == null || status.isBlank()) {
            return "";
        }
        String trimmed = status.trim();
        // "Up 29 minutes (healthy)" → "29 minutes"
        // "Exited (0) 4 weeks ago" → "4 weeks ago"
        int paren = trimmed.indexOf(" (");
        if (paren > 0 && trimmed.endsWith(")")) {
            trimmed = trimmed.substring(0, paren);
        }
        if (trimmed.regionMatches(true, 0, "Up ", 0, 3)) {
            return trimmed.substring(3);
        }
        if (trimmed.regionMatches(true, 0, "Exited ", 0, 7)) {
            int close = trimmed.indexOf(')');
            if (close >= 0 && close + 1 < trimmed.length()) {
                return trimmed.substring(close + 1).trim();
            }
        }
        return trimmed;
    }

    public static String networkKind(boolean builtIn) {
        return builtIn ? "SYSTEM" : "USER  ";
    }

    public static TextColor networkKindColor(boolean builtIn) {
        return builtIn ? Palette.DIM : Palette.ACCENT;
    }

    /**
     * Filled bar of {@code width} characters showing {@code ratio} in [0, 1].
     */
    public static String bar(double ratio, int width) {
        int capped = Math.max(3, width);
        double clamped = Math.max(0, Math.min(1, ratio));
        int filled = (int) Math.round(clamped * capped);
        if (filled == 0 && clamped > 0) {
            filled = 1;
        }
        return "█".repeat(filled) + "░".repeat(capped - filled);
    }

    public static String refreshMeter(long elapsedSeconds, int periodSeconds) {
        int slots = 6;
        if (periodSeconds <= 0) {
            return "░░░░░░";
        }
        int filled = (int) Math.min(slots, Math.max(0, (elapsedSeconds * slots) / periodSeconds));
        return "▓".repeat(filled) + "░".repeat(slots - filled);
    }
}

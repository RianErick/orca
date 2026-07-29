package dev.orca.model;

import java.util.Locale;

/** One-shot resource sample for a running container. */
public record ContainerStatsView(
        double cpuPercent,
        long memUsageBytes,
        long memLimitBytes,
        long netRxBytes,
        long netTxBytes
) {
    public String cpuLabel() {
        if (cpuPercent < 0) {
            return "—";
        }
        if (cpuPercent < 10) {
            return String.format(Locale.ROOT, "%.1f%%", cpuPercent);
        }
        return String.format(Locale.ROOT, "%.0f%%", cpuPercent);
    }

    public String memLabel() {
        if (memUsageBytes < 0) {
            return "—";
        }
        String used = formatBytes(memUsageBytes);
        if (memLimitBytes <= 0) {
            return used;
        }
        return used + "/" + formatBytes(memLimitBytes);
    }

    public String netLabel() {
        if (netRxBytes < 0 && netTxBytes < 0) {
            return "—";
        }
        return "↓" + formatBytes(Math.max(0, netRxBytes))
                + " ↑" + formatBytes(Math.max(0, netTxBytes));
    }

    public double memRatio() {
        if (memLimitBytes <= 0 || memUsageBytes < 0) {
            return 0;
        }
        return Math.min(1.0, (double) memUsageBytes / (double) memLimitBytes);
    }

    private static String formatBytes(long bytes) {
        double value = bytes;
        String[] units = {"B", "K", "M", "G", "T"};
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        if (unit == 0) {
            return String.format(Locale.ROOT, "%.0f%s", value, units[unit]);
        }
        if (value >= 10) {
            return String.format(Locale.ROOT, "%.0f%s", value, units[unit]);
        }
        return String.format(Locale.ROOT, "%.1f%s", value, units[unit]);
    }
}

package dev.orca.model;

import java.util.List;

public record VolumeView(
        String name,
        String driver,
        String mountpoint,
        int useCount,
        List<String> usages
) {
    public String usageSummary() {
        if (useCount <= 0) {
            return "unused";
        }
        if (useCount == 1) {
            return "1 container";
        }
        return useCount + " containers";
    }
}

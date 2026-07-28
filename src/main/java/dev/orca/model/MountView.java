package dev.orca.model;

public record MountView(
        String containerName,
        String type,
        String name,
        String source,
        String destination,
        String mode,
        boolean rw
) {
    public String typeLabel() {
        if (type != null && !type.isBlank()) {
            return type;
        }
        if (name != null && !name.isBlank()) {
            return "volume";
        }
        return "bind";
    }

    public String displaySource() {
        if (name != null && !name.isBlank()) {
            return name;
        }
        return source != null ? source : "";
    }

    public String access() {
        return rw ? "rw" : "ro";
    }
}

package dev.orca.model;

public enum DependencyKind {
    CONTAINER("ctr", "container"),
    NETWORK("net", "network"),
    VOLUME("vol", "volume"),
    BIND("bind", "bind");

    private final String shortLabel;
    private final String longLabel;

    DependencyKind(String shortLabel, String longLabel) {
        this.shortLabel = shortLabel;
        this.longLabel = longLabel;
    }

    public String shortLabel() {
        return shortLabel;
    }

    public String longLabel() {
        return longLabel;
    }
}

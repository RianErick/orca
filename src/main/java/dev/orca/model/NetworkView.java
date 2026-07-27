package dev.orca.model;

public record NetworkView(
        String id,
        String shortId,
        String name,
        String driver,
        String scope
) {
}

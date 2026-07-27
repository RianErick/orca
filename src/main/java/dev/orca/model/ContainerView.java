package dev.orca.model;

public record ContainerView(
        String id,
        String shortId,
        String name,
        String image,
        String status,
        String ports,
        boolean running
) {
}

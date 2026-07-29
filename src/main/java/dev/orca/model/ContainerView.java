package dev.orca.model;

public record ContainerView(
        String id,
        String shortId,
        String name,
        String image,
        String status,
        String ports,
        boolean running,
        ContainerStatsView stats
) {
    public ContainerView withStats(ContainerStatsView next) {
        return new ContainerView(id, shortId, name, image, status, ports, running, next);
    }
}

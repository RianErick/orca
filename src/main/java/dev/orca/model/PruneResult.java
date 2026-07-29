package dev.orca.model;

/** Space reclaimed by a system prune across Docker resource types. */
public record PruneResult(
        long containersBytes,
        long imagesBytes,
        long networksBytes,
        long volumesBytes
) {
    public long totalBytes() {
        return containersBytes + imagesBytes + networksBytes + volumesBytes;
    }

    public boolean reclaimedAnything() {
        return totalBytes() > 0;
    }
}

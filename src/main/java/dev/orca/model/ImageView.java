package dev.orca.model;

public record ImageView(
        String id,
        String shortId,
        String repositoryTag,
        String size,
        long sizeBytes
) {
}

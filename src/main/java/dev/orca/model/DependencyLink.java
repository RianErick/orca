package dev.orca.model;

/**
 * One neighbour of the focused resource in a dependency ego-graph.
 */
public record DependencyLink(
        DependencyKind kind,
        String id,
        String label,
        String detail
) {
    public DependencyLink {
        if (kind == null) {
            throw new IllegalArgumentException("kind");
        }
        id = id == null ? "" : id;
        label = label == null ? "" : label;
        detail = detail == null ? "" : detail;
    }

    public String displayLabel() {
        return label.isBlank() ? (id.isBlank() ? "?" : id) : label;
    }
}

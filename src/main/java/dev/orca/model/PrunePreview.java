package dev.orca.model;

import java.util.ArrayList;
import java.util.List;

/** What a global unused-resource prune would remove, before it runs. */
public record PrunePreview(
        List<String> stoppedContainerNames,
        int danglingImageCount,
        int unusedNetworkCount,
        int unusedVolumeCount
) {
    private static final int NAME_PREVIEW_LIMIT = 8;

    public PrunePreview {
        stoppedContainerNames = List.copyOf(
                stoppedContainerNames == null ? List.of() : stoppedContainerNames
        );
        danglingImageCount = Math.max(0, danglingImageCount);
        unusedNetworkCount = Math.max(0, unusedNetworkCount);
        unusedVolumeCount = Math.max(0, unusedVolumeCount);
    }

    public int stoppedContainerCount() {
        return stoppedContainerNames.size();
    }

    public boolean anythingToPrune() {
        return stoppedContainerCount() > 0
                || danglingImageCount > 0
                || unusedNetworkCount > 0
                || unusedVolumeCount > 0;
    }

    /**
     * Strong confirmation copy: prune is host-wide, never limited to the selected row.
     */
    public String confirmMessage() {
        StringBuilder out = new StringBuilder();
        out.append("⚠  GLOBAL prune — NOT the selected row\n");
        out.append('\n');
        out.append("This permanently deletes ALL unused resources\n");
        out.append("on this Docker host. It is not a row delete.\n");
        out.append('\n');

        if (!anythingToPrune()) {
            out.append("Nothing unused right now — prune would be a no-op.\n");
            return out.toString();
        }

        out.append("Will remove:\n");
        out.append("• ").append(stoppedContainerCount())
                .append(" STOPPED container")
                .append(stoppedContainerCount() == 1 ? "" : "s")
                .append(" (every stopped one)\n");
        appendNamePreview(out, stoppedContainerNames);
        out.append("• ").append(danglingImageCount)
                .append(" dangling image")
                .append(danglingImageCount == 1 ? "" : "s").append('\n');
        out.append("• ").append(unusedNetworkCount)
                .append(" unused network")
                .append(unusedNetworkCount == 1 ? "" : "s").append('\n');
        out.append("• ").append(unusedVolumeCount)
                .append(" unused volume")
                .append(unusedVolumeCount == 1 ? "" : "s").append('\n');
        out.append('\n');
        out.append("Running containers and tagged images are kept.\n");
        out.append("This cannot be undone.\n");
        out.append('\n');
        out.append("Delete everything unused on this host?");
        return out.toString();
    }

    private static void appendNamePreview(StringBuilder out, List<String> names) {
        if (names.isEmpty()) {
            return;
        }
        int shown = Math.min(NAME_PREVIEW_LIMIT, names.size());
        for (int i = 0; i < shown; i++) {
            out.append("    - ").append(names.get(i)).append('\n');
        }
        int remaining = names.size() - shown;
        if (remaining > 0) {
            out.append("    - … and ").append(remaining).append(" more\n");
        }
    }

    public static PrunePreview empty() {
        return new PrunePreview(List.of(), 0, 0, 0);
    }

    public static PrunePreview of(
            List<String> stoppedContainerNames,
            int danglingImageCount,
            int unusedNetworkCount,
            int unusedVolumeCount
    ) {
        return new PrunePreview(
                new ArrayList<>(stoppedContainerNames),
                danglingImageCount,
                unusedNetworkCount,
                unusedVolumeCount
        );
    }
}

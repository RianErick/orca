package dev.orca.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Ego-graph centred on one Docker resource (container, network, or volume).
 * One hop only: neighbours and relation details.
 */
public record DependencyGraph(
        DependencyKind focusKind,
        String focusId,
        String focusLabel,
        String focusDetail,
        List<DependencyLink> links
) {
    public DependencyGraph {
        if (focusKind == null) {
            throw new IllegalArgumentException("focusKind");
        }
        focusId = focusId == null ? "" : focusId;
        focusLabel = focusLabel == null ? "" : focusLabel;
        focusDetail = focusDetail == null ? "" : focusDetail;
        links = List.copyOf(links == null ? List.of() : links);
    }

    public String focusDisplay() {
        return focusLabel.isBlank() ? (focusId.isBlank() ? "?" : focusId) : focusLabel;
    }

    public String render() {
        StringBuilder out = new StringBuilder();
        out.append("★ ").append(focusKind.longLabel()).append("  ").append(focusDisplay()).append('\n');
        if (!focusDetail.isBlank()) {
            out.append("  ").append(focusDetail).append('\n');
        }
        out.append('\n');

        if (links.isEmpty()) {
            out.append("No linked networks, volumes, or containers.\n");
            return out.toString();
        }

        out.append(summaryLine()).append('\n');
        out.append('\n');

        if (focusKind == DependencyKind.CONTAINER) {
            out.append(renderContainerHub());
            out.append('\n');
        }

        out.append(renderTree());
        return out.toString();
    }

    private String summaryLine() {
        long nets = count(DependencyKind.NETWORK);
        long vols = count(DependencyKind.VOLUME);
        long binds = count(DependencyKind.BIND);
        long ctrs = count(DependencyKind.CONTAINER);
        List<String> parts = new ArrayList<>();
        parts.add("1 hop · " + links.size() + " link" + (links.size() == 1 ? "" : "s"));
        if (nets > 0) {
            parts.add(nets + " network" + (nets == 1 ? "" : "s"));
        }
        if (vols > 0) {
            parts.add(vols + " volume" + (vols == 1 ? "" : "s"));
        }
        if (binds > 0) {
            parts.add(binds + " bind" + (binds == 1 ? "" : "s"));
        }
        if (ctrs > 0) {
            parts.add(ctrs + " container" + (ctrs == 1 ? "" : "s"));
        }
        return String.join("  ·  ", parts);
    }

    private long count(DependencyKind kind) {
        return links.stream().filter(link -> link.kind() == kind).count();
    }

    private String renderTree() {
        List<DependencyLink> ordered = orderedLinks();
        StringBuilder out = new StringBuilder();
        out.append(focusDisplay()).append('\n');
        for (int i = 0; i < ordered.size(); i++) {
            boolean last = i == ordered.size() - 1;
            DependencyLink link = ordered.get(i);
            String branch = last ? "└─" : "├─";
            out.append(branch)
                    .append('[')
                    .append(link.kind().shortLabel())
                    .append("] ")
                    .append(link.displayLabel());
            if (!link.detail().isBlank()) {
                out.append('\n')
                        .append(last ? "    " : "│   ")
                        .append(link.detail());
            }
            out.append('\n');
        }
        return out.toString();
    }

    /**
     * Compact two-column hub for container focus — networks left, mounts right.
     */
    private String renderContainerHub() {
        List<DependencyLink> nets = links.stream()
                .filter(link -> link.kind() == DependencyKind.NETWORK)
                .sorted(byLabel())
                .toList();
        List<DependencyLink> mounts = links.stream()
                .filter(link -> link.kind() == DependencyKind.VOLUME || link.kind() == DependencyKind.BIND)
                .sorted(byLabel())
                .toList();

        if (nets.isEmpty() && mounts.isEmpty()) {
            return "";
        }

        int leftWidth = Math.max(16, Math.min(34, nets.stream()
                .mapToInt(link -> ("[" + link.kind().shortLabel() + "] " + link.displayLabel()).length())
                .max()
                .orElse(12) + 2));
        int rightWidth = Math.max(16, Math.min(40, mounts.stream()
                .mapToInt(link -> {
                    String line = "[" + link.kind().shortLabel() + "] " + link.displayLabel();
                    if (!link.detail().isBlank()) {
                        line = line + "  " + shortDetail(link.detail());
                    }
                    return line.length();
                })
                .max()
                .orElse(12) + 2));

        StringBuilder out = new StringBuilder();
        out.append(pad("Networks", leftWidth)).append("   ").append("Volumes / binds").append('\n');
        out.append("─".repeat(leftWidth)).append("   ").append("─".repeat(Math.min(18, rightWidth))).append('\n');

        int rows = Math.max(nets.size(), mounts.size());
        for (int i = 0; i < rows; i++) {
            String left = "";
            String right = "";
            if (i < nets.size()) {
                DependencyLink net = nets.get(i);
                left = "[" + net.kind().shortLabel() + "] " + net.displayLabel();
            }
            if (i < mounts.size()) {
                DependencyLink mount = mounts.get(i);
                right = "[" + mount.kind().shortLabel() + "] " + mount.displayLabel();
                if (!mount.detail().isBlank()) {
                    right = right + "  " + shortDetail(mount.detail());
                }
            }
            out.append(pad(left, leftWidth)).append("   ").append(right).append('\n');
        }

        out.append('\n');
        String star = "★ " + focusDisplay();
        int total = leftWidth + 3 + Math.min(18, rightWidth);
        int padLeft = Math.max(0, (total - star.length()) / 2);
        out.append(" ".repeat(padLeft)).append(star).append('\n');
        return out.toString();
    }

    private List<DependencyLink> orderedLinks() {
        return links.stream()
                .sorted(Comparator
                        .comparingInt((DependencyLink link) -> kindOrder(link.kind()))
                        .thenComparing(DependencyLink::displayLabel, String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.toList());
    }

    private static Comparator<DependencyLink> byLabel() {
        return Comparator.comparing(DependencyLink::displayLabel, String.CASE_INSENSITIVE_ORDER);
    }

    private static int kindOrder(DependencyKind kind) {
        return switch (kind) {
            case NETWORK -> 0;
            case VOLUME -> 1;
            case BIND -> 2;
            case CONTAINER -> 3;
        };
    }

    private static String pad(String text, int width) {
        if (text.length() >= width) {
            return text;
        }
        return text + " ".repeat(width - text.length());
    }

    private static String shortDetail(String detail) {
        String trimmed = detail.trim();
        if (trimmed.length() <= 36) {
            return trimmed;
        }
        return trimmed.substring(0, 35) + "…";
    }

    public String metaLine() {
        return focusKind.longLabel().toUpperCase(Locale.ROOT)
                + " graph  ·  "
                + links.size()
                + " link"
                + (links.size() == 1 ? "" : "s")
                + "  ·  Esc / q to go back";
    }
}

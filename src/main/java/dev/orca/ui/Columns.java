package dev.orca.ui;

/**
 * Spreads table columns across the available width using relative weights, so the tables keep
 * filling the window instead of hugging their content.
 */
public final class Columns {

    private static final int MIN_WIDTH = 3;

    private final int[] weights;
    private final int[] minimums;

    public Columns(int[] weights, int[] minimums) {
        this.weights = weights.clone();
        this.minimums = minimums.clone();
    }

    public int count() {
        return weights.length;
    }

    /**
     * A column with weight zero keeps its minimum width; the rest share what is left.
     *
     * @param available total width the table can paint on, gaps between columns included
     */
    public int[] widths(int available) {
        int gaps = weights.length - 1;
        int[] widths = new int[weights.length];

        int totalWeight = 0;
        int fixed = 0;
        for (int i = 0; i < weights.length; i++) {
            if (weights[i] == 0) {
                widths[i] = minimums[i];
                fixed += minimums[i];
            } else {
                totalWeight += weights[i];
            }
        }

        int usable = Math.max(weights.length * MIN_WIDTH, available - gaps - fixed);
        int assigned = 0;
        int last = -1;
        for (int i = 0; i < weights.length; i++) {
            if (weights[i] == 0) {
                continue;
            }
            widths[i] = Math.max(minimums[i], usable * weights[i] / totalWeight);
            assigned += widths[i];
            last = i;
        }

        // Hand any rounding leftovers to the widest column so the row ends flush with the edge.
        int slack = usable - assigned;
        if (slack != 0 && last >= 0) {
            int widest = last;
            for (int i = 0; i < widths.length; i++) {
                if (weights[i] > 0 && widths[i] > widths[widest]) {
                    widest = i;
                }
            }
            widths[widest] = Math.max(minimums[widest], widths[widest] + slack);
        }
        return widths;
    }

    public static String fit(String value, int width) {
        String text = value == null ? "" : value;
        if (text.length() == width) {
            return text;
        }
        if (text.length() > width) {
            return width <= 1 ? text.substring(0, width) : text.substring(0, width - 1) + "…";
        }
        return text + " ".repeat(width - text.length());
    }
}

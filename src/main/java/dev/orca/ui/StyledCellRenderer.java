package dev.orca.ui;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.TextGUIGraphics;
import com.googlecode.lanterna.gui2.table.Table;
import com.googlecode.lanterna.gui2.table.TableCellRenderer;

/**
 * Draws table cells with zebra stripes, per-cell colour and a clear selection cue.
 *
 * Selection uses the strong accent background even when the table is unfocused, so a click
 * always looks selected — the previous idle colour was easy to miss.
 */
public final class StyledCellRenderer implements TableCellRenderer<String> {

    /**
     * Returns the colour for a cell, or {@code null} to keep the theme colour.
     */
    @FunctionalInterface
    public interface CellColors {
        TextColor colorAt(int row, int column);
    }

    private final CellColors colors;

    public StyledCellRenderer(CellColors colors) {
        this.colors = colors;
    }

    @Override
    public TerminalSize getPreferredSize(Table<String> table, String cell, int columnIndex, int rowIndex) {
        return new TerminalSize(cell == null ? 0 : cell.length(), 1);
    }

    @Override
    public void drawCell(Table<String> table, String cell, int columnIndex, int rowIndex, TextGUIGraphics graphics) {
        boolean selected = rowIndex == table.getSelectedRow();

        if (selected) {
            graphics.setBackgroundColor(Palette.SELECTION);
            graphics.setForegroundColor(Palette.TEXT);
        } else {
            graphics.setBackgroundColor(rowIndex % 2 == 1 ? Palette.SURFACE : Palette.BACKGROUND);
            graphics.setForegroundColor(Palette.TEXT);
        }
        graphics.fill(' ');

        String text = cell == null ? "" : cell;
        TextColor color = colors.colorAt(rowIndex, columnIndex);
        if (color != null && !selected) {
            graphics.setForegroundColor(color);
        } else if (color != null) {
            // Keep state colours readable on the selection background.
            graphics.setForegroundColor(color);
        }

        if (selected) {
            graphics.putString(0, 0, text, SGR.BOLD);
        } else {
            graphics.putString(0, 0, text);
        }
    }
}

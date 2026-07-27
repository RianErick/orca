package dev.orca.ui;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.ThemeDefinition;
import com.googlecode.lanterna.gui2.TextGUIGraphics;
import com.googlecode.lanterna.gui2.table.Table;
import com.googlecode.lanterna.gui2.table.TableCellRenderer;

/**
 * Draws table cells with zebra stripes, per-cell colour and a clear selection cue.
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
        ThemeDefinition definition = table.getTheme().getDefinition(Table.class);
        boolean selected = rowIndex == table.getSelectedRow()
                && (!table.isCellSelection() || columnIndex == table.getSelectedColumn());
        boolean focused = table.isFocused();

        if (selected) {
            graphics.applyThemeStyle(focused ? definition.getActive() : definition.getSelected());
            if (focused) {
                graphics.setBackgroundColor(Palette.SELECTION);
            } else {
                graphics.setBackgroundColor(Palette.SELECTION_IDLE);
            }
        } else {
            graphics.applyThemeStyle(definition.getNormal());
            graphics.setBackgroundColor(rowIndex % 2 == 1 ? Palette.SURFACE : Palette.BACKGROUND);
        }
        graphics.fill(' ');

        String text = cell == null ? "" : cell;
        TextColor color = colors.colorAt(rowIndex, columnIndex);

        if (color != null) {
            graphics.setForegroundColor(color);
        } else if (selected) {
            graphics.setForegroundColor(Palette.TEXT);
        }

        if (selected) {
            graphics.putString(0, 0, text, SGR.BOLD);
        } else {
            graphics.putString(0, 0, text);
        }
    }
}

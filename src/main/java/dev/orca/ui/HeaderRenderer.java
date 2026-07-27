package dev.orca.ui;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.TextGUIGraphics;
import com.googlecode.lanterna.gui2.table.Table;
import com.googlecode.lanterna.gui2.table.TableHeaderRenderer;

/**
 * Quiet uppercase headings sitting on a slightly raised strip.
 */
public final class HeaderRenderer implements TableHeaderRenderer<String> {

    @Override
    public TerminalSize getPreferredSize(Table<String> table, String label, int columnIndex) {
        return new TerminalSize(label == null ? 0 : label.length(), 1);
    }

    @Override
    public void drawHeader(Table<String> table, String label, int index, TextGUIGraphics graphics) {
        graphics.setBackgroundColor(Palette.SURFACE);
        graphics.setForegroundColor(Palette.MUTED);
        graphics.fill(' ');
        graphics.putString(0, 0, label == null ? "" : label.toUpperCase(), SGR.BOLD);
    }
}

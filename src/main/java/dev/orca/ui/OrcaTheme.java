package dev.orca.ui;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.SimpleTheme;
import com.googlecode.lanterna.gui2.Button;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.TextBox;
import com.googlecode.lanterna.gui2.table.Table;

/**
 * Dark theme tuned for a clickable terminal UI.
 */
public final class OrcaTheme {

    private OrcaTheme() {
    }

    public static SimpleTheme create() {
        SimpleTheme theme = new SimpleTheme(Palette.TEXT, Palette.BACKGROUND);

        theme.addOverride(Panel.class, Palette.TEXT, Palette.BACKGROUND);
        theme.addOverride(Label.class, Palette.MUTED, Palette.BACKGROUND);

        theme.addOverride(Button.class, Palette.TEXT, Palette.RAISED);
        theme.getDefinition(Button.class)
                .setPreLight(Palette.ACCENT, Palette.RAISED, SGR.BOLD)
                .setSelected(Palette.BACKGROUND, Palette.ACCENT, SGR.BOLD)
                .setActive(Palette.BACKGROUND, Palette.ACCENT, SGR.BOLD);

        theme.addOverride(Table.class, Palette.TEXT, Palette.BACKGROUND);
        theme.getDefinition(Table.class)
                .setSelected(Palette.TEXT, Palette.SELECTION_IDLE)
                .setActive(Palette.TEXT, Palette.SELECTION, SGR.BOLD);

        theme.addOverride(TextBox.class, Palette.TEXT, Palette.SURFACE);
        theme.getDefinition(TextBox.class)
                .setActive(Palette.TEXT, Palette.SELECTION)
                .setSelected(Palette.TEXT, Palette.SELECTION);

        return theme;
    }

    /**
     * Per-component styling for buttons. Themes are applied directly to each button because
     * Lanterna resolves overrides by concrete class, and orca uses a Button subclass.
     */
    public static SimpleTheme chip(TextColor foreground, TextColor background) {
        SimpleTheme theme = new SimpleTheme(foreground, background);
        theme.getDefaultDefinition()
                .setPreLight(Palette.ACCENT, Palette.RAISED, SGR.BOLD)
                .setSelected(Palette.BACKGROUND, Palette.ACCENT, SGR.BOLD)
                .setActive(Palette.BACKGROUND, Palette.ACCENT, SGR.BOLD);
        return theme;
    }

    /**
     * Chip styling for the view switcher; the active view gets the accent colour.
     */
    public static SimpleTheme tab(boolean active) {
        return active
                ? chip(Palette.BACKGROUND, Palette.ACCENT)
                : chip(Palette.MUTED, Palette.RAISED);
    }
}

package dev.orca.ui;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.Button;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.EmptySpace;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.Panel;

public final class UiBars {

    private UiBars() {
    }

    public static Panel horizontal(Button... buttons) {
        Panel bar = new Panel(new LinearLayout(Direction.HORIZONTAL).setSpacing(1));
        for (Button button : buttons) {
            bar.addComponent(button);
        }
        return bar;
    }

    /**
     * Flat renderer: no {@code < >} brackets, just a padded label that reads as a clickable chip.
     */
    public static Button button(String label, Runnable action) {
        return chip(label, action, Palette.TEXT);
    }

    public static Button chip(String label, Runnable action, TextColor foreground) {
        ClickableButton button = new ClickableButton(" " + label + " ", action);
        button.setRenderer(new Button.FlatButtonRenderer());
        button.setTheme(OrcaTheme.chip(foreground, Palette.RAISED));
        return button;
    }

    public static EmptySpace gap(int columns) {
        return new EmptySpace(new TerminalSize(columns, 1));
    }
}

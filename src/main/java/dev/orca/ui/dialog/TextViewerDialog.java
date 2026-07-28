package dev.orca.ui.dialog;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.Button;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.TextBox;
import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import com.googlecode.lanterna.gui2.dialogs.DialogWindow;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import dev.orca.ui.Palette;
import dev.orca.ui.UiBars;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Read-only scrollable text dialog (mounts, inspect output, etc.). */
public final class TextViewerDialog extends DialogWindow {

    private TextViewerDialog(String title, String body, String metaText, TerminalSize screenSize) {
        super(title);

        int cols = Math.max(40, Math.min(100, screenSize.getColumns() - 8));
        int rows = Math.max(10, Math.min(28, screenSize.getRows() - 10));

        String text = (body == null || body.isBlank()) ? "(empty)" : body;

        Label hint = new Label("↑↓ / PgUp PgDn scroll  ·  q or Esc to go back");
        hint.setForegroundColor(Palette.MUTED);

        Label meta = new Label(metaText != null ? metaText : "");
        meta.setForegroundColor(Palette.DIM);

        TextBox textBox = new TextBox(new TerminalSize(cols, rows), TextBox.Style.MULTI_LINE);
        textBox.setReadOnly(true);
        textBox.setText(text);
        textBox.setCaretPosition(0, 0);

        Button close = UiBars.button("Close (q)", this::close);

        Panel root = new Panel(new LinearLayout(Direction.VERTICAL).setSpacing(0));
        if (metaText != null && !metaText.isBlank()) {
            root.addComponent(meta);
        }
        root.addComponent(hint);
        root.addComponent(textBox);
        root.addComponent(UiBars.gap(1));
        root.addComponent(close);

        setComponent(root);
        setHints(Set.of(Hint.CENTERED, Hint.MODAL));

        addWindowListener(new com.googlecode.lanterna.gui2.WindowListenerAdapter() {
            @Override
            public void onInput(com.googlecode.lanterna.gui2.Window window, KeyStroke keyStroke, AtomicBoolean deliverEvent) {
                if (keyStroke.getKeyType() == KeyType.Escape) {
                    close();
                    deliverEvent.set(false);
                    return;
                }
                if (keyStroke.getKeyType() == KeyType.Character && keyStroke.getCharacter() != null) {
                    char ch = keyStroke.getCharacter();
                    if (ch == 'q' || ch == 'Q') {
                        close();
                        deliverEvent.set(false);
                    }
                }
            }
        });
    }

    public static void show(WindowBasedTextGUI gui, String title, String body, String meta) {
        TerminalSize size = gui.getScreen() != null
                ? gui.getScreen().getTerminalSize()
                : new TerminalSize(100, 30);
        new TextViewerDialog(title, body, meta, size).showDialog(gui);
    }
}

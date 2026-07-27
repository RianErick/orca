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

public final class LogViewerDialog extends DialogWindow {

    private LogViewerDialog(String containerName, String logs) {
        super("Logs — " + containerName);

        TextBox textBox = new TextBox(new TerminalSize(80, 22), TextBox.Style.MULTI_LINE);
        textBox.setReadOnly(true);
        textBox.setText(logs == null || logs.isBlank() ? "(no logs)" : logs);

        Panel root = new Panel(new LinearLayout(Direction.VERTICAL));
        root.addComponent(new Label("Last lines (read-only). Press Close when done."));
        root.addComponent(textBox);
        root.addComponent(new Button("Close", this::close));
        setComponent(root);
        setHints(java.util.Set.of(Hint.CENTERED, Hint.MODAL));
    }

    public static void show(WindowBasedTextGUI gui, String containerName, String logs) {
        new LogViewerDialog(containerName, logs).showDialog(gui);
    }
}

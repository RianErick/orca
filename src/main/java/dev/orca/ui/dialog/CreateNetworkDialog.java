package dev.orca.ui.dialog;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.Button;
import com.googlecode.lanterna.gui2.EmptySpace;
import com.googlecode.lanterna.gui2.GridLayout;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.TextBox;
import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import com.googlecode.lanterna.gui2.dialogs.DialogWindow;

import java.util.Optional;

public final class CreateNetworkDialog extends DialogWindow {

    public record Result(String name, String driver) {
    }

    private Result result;

    private CreateNetworkDialog() {
        super("Create network");

        TextBox nameBox = new TextBox(new TerminalSize(36, 1));
        TextBox driverBox = new TextBox(new TerminalSize(36, 1));
        driverBox.setText("bridge");

        Panel form = new Panel(new GridLayout(2));
        form.addComponent(new Label("Name *"));
        form.addComponent(nameBox);
        form.addComponent(new Label("Driver"));
        form.addComponent(driverBox);
        form.addComponent(new EmptySpace(TerminalSize.ONE));

        Panel buttons = new Panel(new GridLayout(2).setHorizontalSpacing(2));
        buttons.addComponent(new Button("Create", () -> {
            result = new Result(nameBox.getText(), driverBox.getText());
            close();
        }));
        buttons.addComponent(new Button("Cancel", this::close));
        form.addComponent(buttons);

        setComponent(form);
    }

    public static Optional<Result> show(WindowBasedTextGUI gui) {
        CreateNetworkDialog dialog = new CreateNetworkDialog();
        dialog.showDialog(gui);
        return Optional.ofNullable(dialog.result);
    }
}

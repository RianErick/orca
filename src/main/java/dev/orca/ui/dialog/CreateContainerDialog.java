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

public final class CreateContainerDialog extends DialogWindow {

    public record Result(String name, String image, String ports, String env, String command) {
    }

    private Result result;

    private CreateContainerDialog(String prefilledImage) {
        super(prefilledImage != null && !prefilledImage.isBlank() ? "Run image" : "Create container");

        TextBox nameBox = new TextBox(new TerminalSize(40, 1));
        TextBox imageBox = new TextBox(new TerminalSize(40, 1));
        TextBox portsBox = new TextBox(new TerminalSize(40, 1));
        TextBox envBox = new TextBox(new TerminalSize(40, 1));
        TextBox cmdBox = new TextBox(new TerminalSize(40, 1));

        if (prefilledImage != null && !prefilledImage.isBlank()) {
            imageBox.setText(prefilledImage.trim());
        }

        Panel form = new Panel(new GridLayout(2));
        form.addComponent(new Label("Name"));
        form.addComponent(nameBox);
        form.addComponent(new Label("Image *"));
        form.addComponent(imageBox);
        form.addComponent(new Label("Ports"));
        form.addComponent(portsBox);
        form.addComponent(new Label("Env"));
        form.addComponent(envBox);
        form.addComponent(new Label("Command"));
        form.addComponent(cmdBox);

        form.addComponent(new EmptySpace(TerminalSize.ONE));
        Panel buttons = new Panel(new GridLayout(2).setHorizontalSpacing(2));
        String confirmLabel = prefilledImage != null && !prefilledImage.isBlank() ? "Run" : "Create";
        buttons.addComponent(new Button(confirmLabel, () -> {
            result = new Result(
                    nameBox.getText(),
                    imageBox.getText(),
                    portsBox.getText(),
                    envBox.getText(),
                    cmdBox.getText()
            );
            close();
        }));
        buttons.addComponent(new Button("Cancel", this::close));
        form.addComponent(buttons);

        Panel hint = new Panel();
        hint.addComponent(new Label("Ports: 8080:80 or 80  |  Env: KEY=val,KEY2=val"));
        hint.addComponent(form);
        setComponent(hint);
    }

    public static Optional<Result> show(WindowBasedTextGUI gui) {
        return show(gui, null);
    }

    /** Prefills Image when running from the Images tab. */
    public static Optional<Result> show(WindowBasedTextGUI gui, String prefilledImage) {
        CreateContainerDialog dialog = new CreateContainerDialog(prefilledImage);
        dialog.showDialog(gui);
        return Optional.ofNullable(dialog.result);
    }
}

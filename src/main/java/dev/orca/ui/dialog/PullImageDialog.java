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

public final class PullImageDialog extends DialogWindow {

    private String imageRef;

    private PullImageDialog() {
        super("Pull image");

        TextBox imageBox = new TextBox(new TerminalSize(40, 1));
        imageBox.setText("nginx:alpine");

        Panel form = new Panel(new GridLayout(2));
        form.addComponent(new Label("Image *"));
        form.addComponent(imageBox);
        form.addComponent(new EmptySpace(TerminalSize.ONE));

        Panel buttons = new Panel(new GridLayout(2).setHorizontalSpacing(2));
        buttons.addComponent(new Button("Pull", () -> {
            imageRef = imageBox.getText();
            close();
        }));
        buttons.addComponent(new Button("Cancel", this::close));
        form.addComponent(buttons);

        Panel root = new Panel();
        root.addComponent(new Label("Example: nginx:alpine, redis:7"));
        root.addComponent(form);
        setComponent(root);
    }

    public static Optional<String> show(WindowBasedTextGUI gui) {
        PullImageDialog dialog = new PullImageDialog();
        dialog.showDialog(gui);
        if (dialog.imageRef == null || dialog.imageRef.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(dialog.imageRef.trim());
    }
}

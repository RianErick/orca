package dev.orca.ui.dialog;

import com.googlecode.lanterna.gui2.dialogs.MessageDialog;
import com.googlecode.lanterna.gui2.dialogs.MessageDialogButton;
import com.googlecode.lanterna.gui2.WindowBasedTextGUI;

public final class ConfirmDialog {

    private ConfirmDialog() {
    }

    public static boolean ask(WindowBasedTextGUI gui, String title, String message) {
        MessageDialogButton result = MessageDialog.showMessageDialog(
                gui,
                title,
                message,
                MessageDialogButton.Yes,
                MessageDialogButton.No
        );
        return result == MessageDialogButton.Yes;
    }
}

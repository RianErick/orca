package dev.orca.ui.dialog;

import com.googlecode.lanterna.gui2.dialogs.MessageDialog;
import com.googlecode.lanterna.gui2.dialogs.MessageDialogButton;
import com.googlecode.lanterna.gui2.WindowBasedTextGUI;

public final class ConfirmDialog {

    private ConfirmDialog() {
    }

    /** Standard confirm — Yes focused first (row deletes, small actions). */
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

    /**
     * Destructive / global actions — No is first so Enter / click-through cancels by default.
     */
    public static boolean askDangerous(WindowBasedTextGUI gui, String title, String message) {
        MessageDialogButton result = MessageDialog.showMessageDialog(
                gui,
                title,
                message,
                MessageDialogButton.No,
                MessageDialogButton.Yes
        );
        return result == MessageDialogButton.Yes;
    }
}

package dev.orca.ui;

import com.googlecode.lanterna.gui2.Button;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.MouseAction;

/**
 * Button that fires on mouse click (down), not only Enter/Space.
 *
 * Intentionally does not steal focus permanently — the main window returns focus to the table
 * after toolbar actions so row clicks and arrows keep working.
 */
public final class ClickableButton extends Button {

    public ClickableButton(String label, Runnable action) {
        super(label, action);
    }

    @Override
    public synchronized Result handleKeyStroke(KeyStroke keyStroke) {
        if (keyStroke instanceof MouseAction mouse && mouse.isMouseDown()) {
            triggerActions();
            return Result.HANDLED;
        }
        return super.handleKeyStroke(keyStroke);
    }
}

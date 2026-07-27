package dev.orca.ui;

import com.googlecode.lanterna.gui2.Interactable;
import com.googlecode.lanterna.gui2.table.Table;
import com.googlecode.lanterna.input.KeyStroke;

/**
 * Table that reports every selection change, not just row activation.
 *
 * Lanterna's select action only fires on Enter/Space, so arrow-key navigation would otherwise
 * leave the rest of the UI showing a stale selection.
 */
public final class SelectionTable<V> extends Table<V> {

    private Runnable selectionListener = () -> {
    };
    private int lastSelectedRow = -1;

    public SelectionTable(String... columnLabels) {
        super(columnLabels);
    }

    public void setSelectionListener(Runnable listener) {
        this.selectionListener = listener;
    }

    @Override
    public Interactable.Result handleKeyStroke(KeyStroke keyStroke) {
        Interactable.Result result = super.handleKeyStroke(keyStroke);
        notifyIfSelectionChanged();
        return result;
    }

    public void notifyIfSelectionChanged() {
        int current = getSelectedRow();
        if (current != lastSelectedRow) {
            lastSelectedRow = current;
            selectionListener.run();
        }
    }
}

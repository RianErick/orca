package dev.orca.ui;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.gui2.Interactable;
import com.googlecode.lanterna.gui2.table.Table;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.input.MouseActionType;

/**
 * Table that keeps selection state truthful for mouse, keyboard and programmatic updates.
 *
 * Lanterna mutates {@code selectedRow} inside {@code TableModel} listeners when rows are
 * added/removed — without going through {@link #setSelectedRow(int)}. That desyncs our
 * notification tracker and makes clicks look like they "jump" to the first row.
 */
public final class SelectionTable<V> extends Table<V> {

    private Runnable selectionListener = () -> {
    };
    private int lastNotifiedRow = -1;

    public SelectionTable(String... columnLabels) {
        super(columnLabels);
        setEscapeByArrowKey(false);
        setCellSelection(false);
    }

    public void setSelectionListener(Runnable listener) {
        this.selectionListener = listener != null ? listener : () -> {
        };
    }

    @Override
    public synchronized Table<V> setSelectedRow(int selectedRow) {
        super.setSelectedRow(selectedRow);
        invalidate();
        notifySelectionListener(false);
        return this;
    }

    @Override
    public Interactable.Result handleKeyStroke(KeyStroke keyStroke) {
        if (keyStroke instanceof MouseAction mouse) {
            return handleMouse(mouse);
        }
        Interactable.Result result = super.handleKeyStroke(keyStroke);
        notifySelectionListener(false);
        return result;
    }

    private Interactable.Result handleMouse(MouseAction mouse) {
        MouseActionType type = mouse.getActionType();
        if (type == MouseActionType.MOVE || type == MouseActionType.DRAG) {
            return Interactable.Result.UNHANDLED;
        }
        // Press selects; ignore release so we do not double-process the same click.
        if (type == MouseActionType.CLICK_RELEASE || mouse.isMouseUp()) {
            return Interactable.Result.HANDLED;
        }
        if (!isFocused()) {
            takeFocus();
        }
        if (getTableModel().getRowCount() == 0) {
            return Interactable.Result.HANDLED;
        }
        int row = getRowByMouseAction(mouse);
        setSelectedRow(row);
        return Interactable.Result.HANDLED;
    }

    /**
     * Maps a mouse Y coordinate onto a model row.
     *
     * Lanterna's built-in version forgets the scroll offset. We also resolve the table origin
     * via {@link #toGlobal(TerminalPosition)} which stays correct inside fullscreen windows.
     */
    @Override
    protected int getRowByMouseAction(MouseAction mouseAction) {
        int rowCount = getTableModel().getRowCount();
        if (rowCount == 0) {
            return 0;
        }
        int minPossible = getFirstViewedRowIndex();
        int maxPossible = getLastViewedRowIndex();
        if (maxPossible < minPossible) {
            return Math.max(0, Math.min(getSelectedRow(), rowCount - 1));
        }

        TerminalPosition origin = toGlobal(TerminalPosition.TOP_LEFT_CORNER);
        if (origin == null) {
            origin = getGlobalPosition();
        }
        if (origin == null) {
            return minPossible;
        }

        // Header is always one row with our HeaderRenderer + default border styles (None).
        int headerRows = 1;
        int relative = mouseAction.getPosition().getRow() - origin.getRow() - headerRows;
        int row = minPossible + relative;
        return Math.max(minPossible, Math.min(row, maxPossible));
    }

    /**
     * Call after Lanterna may have mutated {@code selectedRow} behind our back (model rebuilds).
     * Realigns the notification tracker without firing the listener.
     */
    public void adoptModelSelection() {
        lastNotifiedRow = currentRow();
    }

    /** Forces the listener to run with the current row (e.g. after a data reload). */
    public void forceSelectionNotification() {
        lastNotifiedRow = currentRow();
        selectionListener.run();
    }

    private void notifySelectionListener(boolean force) {
        int current = currentRow();
        if (force || current != lastNotifiedRow) {
            lastNotifiedRow = current;
            selectionListener.run();
        }
    }

    private int currentRow() {
        return getTableModel().getRowCount() == 0 ? -1 : getSelectedRow();
    }
}

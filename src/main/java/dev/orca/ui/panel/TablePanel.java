package dev.orca.ui.panel;

import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import com.googlecode.lanterna.gui2.dialogs.MessageDialog;
import com.googlecode.lanterna.gui2.dialogs.MessageDialogButton;
import com.googlecode.lanterna.gui2.table.TableModel;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import dev.orca.docker.DockerService;
import dev.orca.ui.Columns;
import dev.orca.ui.HeaderRenderer;
import dev.orca.ui.Palette;
import dev.orca.ui.SelectionTable;
import dev.orca.ui.StatusSink;
import dev.orca.ui.StyledCellRenderer;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared behaviour for the three views: a toolbar, a table that stretches to the window width,
 * colour-coded cells and a selection that survives refreshes.
 */
public abstract class TablePanel<T> extends Panel {

    /** Room taken by the table's scrollbar. */
    private static final int SCROLLBAR = 1;

    protected final WindowBasedTextGUI gui;
    protected final StatusSink status;

    private final SelectionTable<String> table;
    private final Columns columns;
    private final Label heading;
    private final List<T> items = new ArrayList<>();

    private int[] widths;
    private boolean loadFailed;

    protected TablePanel(WindowBasedTextGUI gui, StatusSink status, Columns columns, String... headers) {
        super(new LinearLayout(Direction.VERTICAL).setSpacing(0));
        this.gui = gui;
        this.status = status;
        this.columns = columns;
        this.widths = columns.widths(80);

        heading = new Label("");
        table = new SelectionTable<>(headers);
        table.setVisibleColumns(headers.length);
        table.setVisibleRows(12);
        table.setTableHeaderRenderer(new HeaderRenderer());
        table.setTableCellRenderer(new StyledCellRenderer(this::cellColor));
        table.setSelectionListener(this::onSelectionChanged);
    }

    /**
     * Called by subclasses once their toolbar is ready; keeps the component order consistent.
     */
    protected void assemble(Panel toolbar) {
        addComponent(toolbar);
        addComponent(heading);
        addComponent(table);
    }

    protected abstract List<T> load() throws Exception;

    protected abstract String[] cells(T item);

    protected abstract TextColor color(T item, int column);

    protected abstract String identity(T item);

    /** Plural noun used in headings and status messages, e.g. "containers". */
    protected abstract String noun();

    /** One-line description of the highlighted row, shown in the status bar. */
    public abstract String describeSelection();

    public boolean handleKey(KeyStroke key) {
        if (key.getKeyType() != KeyType.Character || key.getCharacter() == null) {
            return false;
        }
        return handleShortcut(key.getCharacter());
    }

    protected abstract boolean handleShortcut(char shortcut);

    public SelectionTable<String> getTable() {
        return table;
    }

    public int count() {
        return items.size();
    }

    protected List<T> items() {
        return items;
    }

    protected T selected() {
        int row = table.getSelectedRow();
        if (row < 0 || row >= items.size()) {
            return null;
        }
        return items.get(row);
    }

    public void relayout(int width, int visibleRows) {
        widths = columns.widths(Math.max(20, width - SCROLLBAR - (columns.count() - 1)));
        table.setVisibleRows(Math.max(3, visibleRows));
        render();
    }

    public void refresh() {
        String previous = selectedIdentity();
        try {
            List<T> loaded = load();
            items.clear();
            items.addAll(loaded);
            loadFailed = false;
            render();
            restoreSelection(previous);
            status.setStatus(items.size() + " " + noun() + " loaded");
        } catch (Exception e) {
            loadFailed = true;
            items.clear();
            render();
            status.setStatus("Docker error: " + DockerService.friendlyMessage(e));
        }
    }

    /** Repaints the current data without querying Docker again. */
    protected void render() {
        TableModel<String> model = table.getTableModel();
        while (model.getRowCount() > 0) {
            model.removeRow(0);
        }
        for (T item : items) {
            String[] raw = cells(item);
            String[] padded = new String[raw.length];
            for (int i = 0; i < raw.length; i++) {
                padded[i] = Columns.fit(raw[i], widths[i]);
            }
            model.addRow(padded);
        }
        updateHeading();
    }

    private void updateHeading() {
        if (loadFailed) {
            heading.setText("  ✕  Could not reach Docker — is the daemon running?");
            heading.setForegroundColor(Palette.STOPPED);
            return;
        }
        if (items.isEmpty()) {
            heading.setText("  ·  No " + noun() + " yet — use the toolbar above to create one");
            heading.setForegroundColor(Palette.MUTED);
            return;
        }
        heading.setText("  " + items.size() + " " + noun()
                + "  ·  ↑↓ move  ·  click a row to select");
        heading.setForegroundColor(Palette.DIM);
    }

    private String selectedIdentity() {
        T item = selected();
        return item == null ? null : identity(item);
    }

    private void restoreSelection(String identity) {
        if (identity != null) {
            for (int i = 0; i < items.size(); i++) {
                if (identity.equals(identity(items.get(i)))) {
                    table.setSelectedRow(i);
                    onSelectionChanged();
                    return;
                }
            }
        }
        table.setSelectedRow(Math.min(table.getSelectedRow(), Math.max(0, items.size() - 1)));
        onSelectionChanged();
    }

    private TextColor cellColor(int row, int column) {
        if (row < 0 || row >= items.size()) {
            return null;
        }
        return color(items.get(row), column);
    }

    private void onSelectionChanged() {
        status.setSelection(describeSelection());
    }

    protected void requireSelection(String action) {
        status.setStatus("Select a row first, then " + action);
    }

    protected void showError(String title, Exception e) {
        String message = DockerService.friendlyMessage(e);
        status.setStatus("Error: " + message);
        MessageDialog.showMessageDialog(gui, title, message, MessageDialogButton.OK);
    }

    protected static String bullet(boolean on) {
        return on ? "●" : "○";
    }
}

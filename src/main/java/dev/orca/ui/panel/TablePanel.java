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
    private final String[] headers;
    private final Label heading;
    private final List<T> items = new ArrayList<>();

    private int[] widths;
    private boolean loadFailed;
    /** Only the active tab may push selection text into the shared status bar. */
    private boolean active;
    /** Stable selection key — survives model rebuilds that temporarily zero {@code selectedRow}. */
    private String selectedIdentity;

    protected TablePanel(WindowBasedTextGUI gui, StatusSink status, Columns columns, String... headers) {
        super(new LinearLayout(Direction.VERTICAL).setSpacing(0));
        this.gui = gui;
        this.status = status;
        this.columns = columns;
        this.headers = headers.clone();
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

    public void setActive(boolean active) {
        this.active = active;
        if (active) {
            table.forceSelectionNotification();
            focusTable();
        }
    }

    public boolean isActive() {
        return active;
    }

    public int count() {
        return items.size();
    }

    protected List<T> items() {
        return items;
    }

    protected T selected() {
        if (selectedIdentity != null) {
            for (T item : items) {
                if (selectedIdentity.equals(identity(item))) {
                    return item;
                }
            }
        }
        int row = table.getSelectedRow();
        if (row < 0 || row >= items.size()) {
            return null;
        }
        return items.get(row);
    }

    public void relayout(int width, int visibleRows) {
        widths = columns.widths(Math.max(20, width - SCROLLBAR - (columns.count() - 1)));
        table.setVisibleRows(Math.max(3, visibleRows));
        // Re-render keeps the remembered identity; never leave the highlight stuck on row 0.
        render(selectedIdentity);
    }

    /**
     * @param announce when {@code true}, writes a status line (active tab only should announce)
     */
    public void refresh(boolean announce) {
        String previous = selectedIdentity != null ? selectedIdentity : selectedIdentityFromRow();
        try {
            List<T> loaded = load();
            items.clear();
            items.addAll(loaded);
            loadFailed = false;
            render(previous);
            if (announce && active) {
                status.setStatus(items.size() + " " + noun() + " loaded");
            }
        } catch (Exception e) {
            loadFailed = true;
            items.clear();
            render(null);
            if (announce && active) {
                status.setStatus("Docker error: " + DockerService.friendlyMessage(e));
            }
        }
    }

    public void refresh() {
        refresh(true);
    }

    /**
     * Rebuilds the table model in one shot. Avoids Lanterna's per-row listener that silently
     * walks {@code selectedRow} back to 0 while rows are cleared.
     */
    private void render(String identityToRestore) {
        TableModel<String> model = new TableModel<>(headers);
        for (T item : items) {
            String[] raw = cells(item);
            String[] padded = new String[raw.length];
            for (int i = 0; i < raw.length; i++) {
                padded[i] = Columns.fit(raw[i], widths[i]);
            }
            model.addRow(padded);
        }
        table.setTableModel(model);
        // Model swap may leave selectedRow stale; realign the tracker before restoring.
        table.adoptModelSelection();
        restoreSelection(identityToRestore);
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

    private String selectedIdentityFromRow() {
        int row = table.getSelectedRow();
        if (row < 0 || row >= items.size()) {
            return null;
        }
        return identity(items.get(row));
    }

    private void restoreSelection(String identity) {
        if (items.isEmpty()) {
            selectedIdentity = null;
            table.forceSelectionNotification();
            return;
        }
        if (identity != null) {
            for (int i = 0; i < items.size(); i++) {
                if (identity.equals(identity(items.get(i)))) {
                    selectedIdentity = identity;
                    table.setSelectedRow(i);
                    table.forceSelectionNotification();
                    return;
                }
            }
        }
        int row = Math.min(Math.max(0, table.getSelectedRow()), items.size() - 1);
        selectedIdentity = identity(items.get(row));
        table.setSelectedRow(row);
        table.forceSelectionNotification();
    }

    private TextColor cellColor(int row, int column) {
        if (row < 0 || row >= items.size()) {
            return null;
        }
        return color(items.get(row), column);
    }

    private void onSelectionChanged() {
        int row = table.getSelectedRow();
        if (row >= 0 && row < items.size()) {
            selectedIdentity = identity(items.get(row));
        } else {
            selectedIdentity = null;
        }
        if (active) {
            status.setSelection(describeSelection());
            status.noteUserInteraction();
        }
    }

    /** Returns keyboard/mouse focus to the table after toolbar actions. */
    protected void focusTable() {
        try {
            gui.getGUIThread().invokeLater(table::takeFocus);
        } catch (Exception ignored) {
            table.takeFocus();
        }
    }

    protected void requireSelection(String action) {
        status.setStatus("Select a row first, then " + action);
        focusTable();
    }

    protected void showError(String title, Exception e) {
        String message = DockerService.friendlyMessage(e);
        status.setStatus("Error: " + message);
        MessageDialog.showMessageDialog(gui, title, message, MessageDialogButton.OK);
        focusTable();
    }

    protected static String bullet(boolean on) {
        return on ? "●" : "○";
    }
}

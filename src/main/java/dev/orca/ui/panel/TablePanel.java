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
import java.util.Locale;

/**
 * Shared behaviour for the resource views: a toolbar, a table that stretches to the window width,
 * colour-coded cells, a selection that survives refreshes, and {@code /} filter like lazygit.
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
    private final List<T> allItems = new ArrayList<>();
    private final List<T> items = new ArrayList<>();

    private int[] widths;
    private boolean loadFailed;
    /** Only the active tab may push selection text into the shared status bar. */
    private boolean active;
    /** Stable selection key — survives model rebuilds that temporarily zero {@code selectedRow}. */
    private String selectedIdentity;
    /** Active filter query (substring match on {@link #searchText}). Empty means no filter. */
    private String filterQuery = "";
    /** When true, typed characters edit the filter instead of triggering shortcuts. */
    private boolean filtering;

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

    /**
     * Text used by the {@code /} filter. Default joins table cells (name, image, status, …).
     */
    protected String searchText(T item) {
        return String.join(" ", cells(item));
    }

    public boolean handleKey(KeyStroke key) {
        if (filtering) {
            return handleFilterInput(key);
        }
        if (key.getKeyType() == KeyType.Escape && hasFilter()) {
            clearFilter();
            return true;
        }
        if (key.getKeyType() == KeyType.Character && key.getCharacter() != null && key.getCharacter() == '/') {
            beginFilter();
            return true;
        }
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
        } else if (filtering) {
            // Leave edit mode when switching tabs, but keep the query.
            filtering = false;
            updateHeading();
        }
    }

    public boolean isActive() {
        return active;
    }

    public boolean isFiltering() {
        return filtering;
    }

    public boolean hasFilter() {
        return filterQuery != null && !filterQuery.isEmpty();
    }

    public String filterQuery() {
        return filterQuery;
    }

    /** Unfiltered total — used by header KPIs. */
    public int count() {
        return allItems.size();
    }

    protected List<T> items() {
        return items;
    }

    protected List<T> allItems() {
        return allItems;
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
            allItems.clear();
            allItems.addAll(loaded);
            loadFailed = false;
            applyFilter(previous);
            if (announce && active) {
                if (hasFilter()) {
                    status.setStatus(items.size() + "/" + allItems.size() + " " + noun()
                            + "  ·  /" + filterQuery);
                } else {
                    status.setStatus(items.size() + " " + noun() + " loaded");
                }
            }
        } catch (Exception e) {
            loadFailed = true;
            allItems.clear();
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
     * Replaces the loaded model without hitting Docker again (e.g. overlaying async stats).
     */
    protected void replaceItems(List<T> next) {
        String previous = selectedIdentity != null ? selectedIdentity : selectedIdentityFromRow();
        allItems.clear();
        if (next != null) {
            allItems.addAll(next);
        }
        loadFailed = false;
        applyFilter(previous);
    }

    private void beginFilter() {
        filtering = true;
        updateHeading();
        if (active) {
            status.setStatus("Filter: /" + filterQuery + "█  ·  Esc clear  ·  Enter done");
            status.setSelection(describeSelection());
        }
        focusTable();
    }

    private boolean handleFilterInput(KeyStroke key) {
        if (key.getKeyType() == KeyType.Escape) {
            clearFilter();
            return true;
        }
        if (key.getKeyType() == KeyType.Enter) {
            filtering = false;
            updateHeading();
            if (active) {
                status.setStatus(hasFilter()
                        ? "Filter: /" + filterQuery + "  ·  Esc to clear"
                        : items.size() + " " + noun());
            }
            focusTable();
            return true;
        }
        if (key.getKeyType() == KeyType.Backspace || key.getKeyType() == KeyType.Delete) {
            if (!filterQuery.isEmpty()) {
                filterQuery = filterQuery.substring(0, filterQuery.length() - 1);
                applyFilter(selectedIdentity);
                announceFilter();
            }
            return true;
        }
        if (key.getKeyType() == KeyType.Character && key.getCharacter() != null) {
            char ch = key.getCharacter();
            if (!Character.isISOControl(ch)) {
                filterQuery += ch;
                applyFilter(selectedIdentity);
                announceFilter();
            }
            return true;
        }
        // Let arrows etc. fall through to the global handler.
        return false;
    }

    public void clearFilter() {
        boolean had = hasFilter() || filtering;
        filterQuery = "";
        filtering = false;
        if (had) {
            applyFilter(selectedIdentity);
            if (active) {
                status.setStatus("Filter cleared  ·  " + items.size() + " " + noun());
            }
        }
        updateHeading();
        focusTable();
    }

    private void announceFilter() {
        if (active) {
            status.setStatus("Filter: /" + filterQuery + "█  ·  "
                    + items.size() + "/" + allItems.size() + " " + noun());
        }
    }

    private void applyFilter(String identityToRestore) {
        items.clear();
        if (!hasFilter()) {
            items.addAll(allItems);
        } else {
            String needle = filterQuery.toLowerCase(Locale.ROOT);
            for (T item : allItems) {
                String haystack = searchText(item);
                if (haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle)) {
                    items.add(item);
                }
            }
        }
        render(identityToRestore);
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
        if (filtering || hasFilter()) {
            String caret = filtering ? "█" : "";
            heading.setText("  /" + filterQuery + caret
                    + "  ·  " + items.size() + "/" + allItems.size() + " " + noun()
                    + (filtering ? "  ·  Esc clear  ·  Enter done" : "  ·  Esc clear  ·  / edit"));
            heading.setForegroundColor(Palette.ACCENT);
            return;
        }
        if (items.isEmpty()) {
            heading.setText("  ·  No " + noun() + " yet — use the toolbar above to create one");
            heading.setForegroundColor(Palette.MUTED);
            return;
        }
        heading.setText("  " + items.size() + " " + noun()
                + "  ·  ↑↓ move  ·  / filter  ·  click a row to select");
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

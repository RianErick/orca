package dev.orca.ui;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.Button;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.EmptySpace;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.Window;
import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import com.googlecode.lanterna.gui2.WindowListenerAdapter;
import com.googlecode.lanterna.gui2.dialogs.MessageDialog;
import com.googlecode.lanterna.gui2.dialogs.MessageDialogButton;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import dev.orca.docker.DockerService;
import dev.orca.model.PruneResult;
import dev.orca.ui.dialog.ConfirmDialog;
import dev.orca.ui.panel.ContainersPanel;
import dev.orca.ui.panel.ImagesPanel;
import dev.orca.ui.panel.NetworksPanel;
import dev.orca.ui.panel.TablePanel;
import dev.orca.ui.panel.VolumesPanel;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainWindow extends BasicWindow {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int AUTO_REFRESH_SECONDS = 5;
    /** How often inactive tabs are refreshed just for header KPIs. */
    private static final int KPI_REFRESH_SECONDS = 15;

    /** Rows used by everything that is not table content. */
    private static final int CHROME_ROWS = 12;

    private final WindowBasedTextGUI gui;
    private final DockerService docker;

    private final Label brand = new Label(" orca ");
    private final Label tagline = new Label(" docker control ");
    private final Label kpiRunning = new Label("");
    private final Label kpiStopped = new Label("");
    private final Label kpiImages = new Label("");
    private final Label kpiNetworks = new Label("");
    private final Label kpiVolumes = new Label("");
    private final Label clockLabel = new Label("");
    private final Label topRule = new Label("");
    private final Label middleRule = new Label("");
    private final Label bottomRule = new Label("");
    private final Label statusBar = new Label("");
    private final Label footer = new Label("");
    private final EmptySpace filler = new EmptySpace(TerminalSize.ONE);
    private final EmptySpace headerSpacer = new EmptySpace(TerminalSize.ONE);

    private final Button autoButton = UiBars.chip("Auto on", this::toggleAutoRefresh, Palette.RUNNING);
    private final Panel content;
    private final Button[] tabs;
    private final TablePanel<?>[] panels;
    private final ContainersPanel containersPanel;
    private final ImagesPanel imagesPanel;
    private final NetworksPanel networksPanel;
    private final VolumesPanel volumesPanel;

    private final ScheduledExecutorService ticker =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "orca-ticker");
                thread.setDaemon(true);
                return thread;
            });

    private String message = "Starting…";
    private String selection = "";
    private int selectedTab;
    private boolean autoRefresh = true;
    private long lastRefreshMillis = System.currentTimeMillis();
    private long lastKpiRefreshMillis = 0;
    private TerminalSize lastSize;

    public MainWindow(WindowBasedTextGUI gui, DockerService docker, boolean mouseEnabled) {
        super("orca");
        this.gui = gui;
        this.docker = docker;
        setHints(List.of(Hint.FULL_SCREEN, Hint.NO_DECORATIONS));

        StatusSink status = new StatusSink() {
            @Override
            public void setStatus(String text) {
                message = text;
                paintChrome();
            }

            @Override
            public void setSelection(String description) {
                selection = description;
                paintChrome();
            }

            @Override
            public void noteUserInteraction() {
                // Push the auto-refresh deadline forward so a click is not immediately undone
                // by a model rebuild that used to snap the highlight back to row 0.
                lastRefreshMillis = System.currentTimeMillis();
            }
        };

        containersPanel = new ContainersPanel(docker, gui, status);
        imagesPanel = new ImagesPanel(docker, gui, status);
        networksPanel = new NetworksPanel(docker, gui, status);
        volumesPanel = new VolumesPanel(docker, gui, status);
        panels = new TablePanel<?>[]{containersPanel, imagesPanel, networksPanel, volumesPanel};

        tabs = new Button[]{
                UiBars.button("Containers", () -> selectTab(0)),
                UiBars.button("Images", () -> selectTab(1)),
                UiBars.button("Networks", () -> selectTab(2)),
                UiBars.button("Volumes", () -> selectTab(3))
        };

        Panel tabBar = new Panel(new LinearLayout(Direction.HORIZONTAL).setSpacing(1));
        for (Button tab : tabs) {
            tabBar.addComponent(tab);
        }
        tabBar.addComponent(UiBars.gap(2));
        tabBar.addComponent(UiBars.button("Refresh", () -> refreshActive(true)));
        tabBar.addComponent(UiBars.chip("✂ Prune", this::pruneUnused, Palette.WARNING));
        tabBar.addComponent(autoButton);
        tabBar.addComponent(UiBars.button("Help", this::showHelp));
        tabBar.addComponent(UiBars.chip("× Quit", this::close, Palette.STOPPED));

        content = new Panel(new LinearLayout(Direction.VERTICAL).setSpacing(0));

        brand.setForegroundColor(Palette.ACCENT);
        brand.addStyle(SGR.BOLD);
        tagline.setForegroundColor(Palette.DIM);
        kpiRunning.setForegroundColor(Palette.RUNNING);
        kpiRunning.addStyle(SGR.BOLD);
        kpiStopped.setForegroundColor(Palette.STOPPED);
        kpiImages.setForegroundColor(Palette.MUTED);
        kpiNetworks.setForegroundColor(Palette.MUTED);
        kpiVolumes.setForegroundColor(Palette.MUTED);
        clockLabel.setForegroundColor(Palette.DIM);
        statusBar.setForegroundColor(Palette.TEXT);
        footer.setForegroundColor(Palette.DIM);
        for (Label rule : List.of(topRule, middleRule, bottomRule)) {
            rule.setForegroundColor(Palette.BORDER);
        }

        Panel headerBar = new Panel(new LinearLayout(Direction.HORIZONTAL).setSpacing(0));
        headerBar.addComponent(brand);
        headerBar.addComponent(tagline);
        headerBar.addComponent(headerSpacer, LinearLayout.createLayoutData(
                LinearLayout.Alignment.Fill, LinearLayout.GrowPolicy.CanGrow));
        headerBar.addComponent(kpiRunning);
        headerBar.addComponent(kpiStopped);
        headerBar.addComponent(kpiImages);
        headerBar.addComponent(kpiNetworks);
        headerBar.addComponent(kpiVolumes);
        headerBar.addComponent(clockLabel);

        Panel root = new Panel(new LinearLayout(Direction.VERTICAL).setSpacing(0));
        root.addComponent(headerBar);
        root.addComponent(topRule);
        root.addComponent(tabBar);
        root.addComponent(middleRule);
        root.addComponent(content);
        root.addComponent(filler, LinearLayout.createLayoutData(
                LinearLayout.Alignment.Fill, LinearLayout.GrowPolicy.CanGrow));
        root.addComponent(bottomRule);
        root.addComponent(statusBar);
        root.addComponent(footer);
        setComponent(root);

        message = mouseEnabled ? "Ready — click or use the keyboard" : "Ready — keyboard mode";
        for (int i = 0; i < panels.length; i++) {
            panels[i].setActive(i == 0);
        }
        selectTab(0);

        addWindowListener(new WindowListenerAdapter() {
            @Override
            public void onInput(Window basePane, KeyStroke keyStroke, AtomicBoolean deliverEvent) {
                if (handleGlobalKey(keyStroke)) {
                    deliverEvent.set(false);
                }
            }

            @Override
            public void onResized(Window window, TerminalSize oldSize, TerminalSize newSize) {
                relayout();
            }
        });
    }

    public void initialLoad() {
        try {
            docker.ping();
            message = "Connected to Docker";
        } catch (Exception e) {
            message = "Docker not reachable: " + DockerService.friendlyMessage(e);
            MessageDialog.showMessageDialog(
                    gui,
                    "Docker unavailable",
                    DockerService.friendlyMessage(e),
                    MessageDialogButton.OK
            );
        }
        relayout();
        refreshActive(true);
        ticker.scheduleAtFixedRate(this::tick, 1, 1, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        ticker.shutdownNow();
        super.close();
    }

    /**
     * Runs off the GUI thread: everything that touches components is handed back to it.
     */
    private void tick() {
        try {
            gui.getGUIThread().invokeLater(() -> {
                if (gui.getActiveWindow() != this) {
                    return;
                }
                if (autoRefresh && secondsSinceRefresh() >= AUTO_REFRESH_SECONDS) {
                    refreshActive();
                } else {
                    paintChrome();
                }
            });
        } catch (Exception ignored) {
            // the GUI is shutting down
        }
    }

    private void relayout() {
        TerminalSize size = gui.getScreen().getTerminalSize();
        lastSize = size;
        int rows = Math.max(3, size.getRows() - CHROME_ROWS);
        for (TablePanel<?> panel : panels) {
            panel.relayout(size.getColumns(), rows);
        }
        paintChrome();
    }

    private void paintChrome() {
        TerminalSize size = lastSize != null ? lastSize : gui.getScreen().getTerminalSize();
        int width = Math.max(40, size.getColumns());

        long running = containersPanel.runningCount();
        long stopped = containersPanel.stoppedCount();
        kpiRunning.setText(" ● " + running + " run ");
        kpiStopped.setText(" ○ " + stopped + " stop ");
        kpiImages.setText(" ◼ " + imagesPanel.count() + " img ");
        long dangling = imagesPanel.untaggedCount();
        if (dangling > 0) {
            kpiImages.setText(" ◼ " + imagesPanel.count() + " img · " + dangling + " dangling ");
            kpiImages.setForegroundColor(Palette.WARNING);
        } else {
            kpiImages.setForegroundColor(Palette.MUTED);
        }
        kpiNetworks.setText(" ⇄ " + networksPanel.count() + " net ");
        kpiVolumes.setText(" ▤ " + volumesPanel.count() + " vol ");
        clockLabel.setText("  " + LocalTime.now().format(CLOCK) + " ");

        String rule = "─".repeat(width);
        topRule.setText(rule);
        middleRule.setText(rule);
        bottomRule.setText(rule);

        statusBar.setText(pad(" " + selection, message + " ", width));
        footer.setText(pad(" " + shortcuts(), autoRefreshLabel() + " ", width));
    }

    private String autoRefreshLabel() {
        if (!autoRefresh) {
            return "auto ░░░░░░ off";
        }
        long elapsed = secondsSinceRefresh();
        return Badges.refreshMeter(elapsed, AUTO_REFRESH_SECONDS)
                + " " + elapsed + "s/" + AUTO_REFRESH_SECONDS + "s";
    }

    private String shortcuts() {
        return switch (selectedTab) {
            case 0 -> "1-4 · / filter · P prune · c create · s/x start/stop · l logs · m mounts · g graph · d delete · q quit";
            case 1 -> "1-4 · / filter · P prune · p pull · d delete · r refresh · a auto · ? help · q quit";
            case 2 -> "1-4 · / filter · P prune · c create · g graph · d delete · r refresh · a auto · ? help · q quit";
            default -> "1-4 · / filter · P prune · c create · m mounts · g graph · d delete · r refresh · a auto · q quit";
        };
    }

    private long secondsSinceRefresh() {
        return (System.currentTimeMillis() - lastRefreshMillis) / 1000;
    }

    private static String pad(String left, String right, int width) {
        int space = width - left.length() - right.length();
        if (space < 1) {
            return Columns.fit(left, width);
        }
        return left + " ".repeat(space) + right;
    }

    private boolean handleGlobalKey(KeyStroke key) {
        if (key.getKeyType() == KeyType.EOF) {
            close();
            return true;
        }
        // While filtering, route almost everything to the panel (including q, Esc, digits).
        if (activePanel().isFiltering()) {
            if (key.getKeyType() == KeyType.ArrowUp
                    || key.getKeyType() == KeyType.ArrowDown
                    || key.getKeyType() == KeyType.PageUp
                    || key.getKeyType() == KeyType.PageDown
                    || key.getKeyType() == KeyType.Home
                    || key.getKeyType() == KeyType.End) {
                activePanel().getTable().handleKeyStroke(key);
                return true;
            }
            return activePanel().handleKey(key);
        }
        // Arrows always drive the table, even when a button holds the focus after a click.
        switch (key.getKeyType()) {
            case ArrowUp, ArrowDown, PageUp, PageDown, Home, End -> {
                activePanel().getTable().handleKeyStroke(key);
                return true;
            }
            case Escape -> {
                if (activePanel().hasFilter()) {
                    activePanel().clearFilter();
                    return true;
                }
                return false;
            }
            default -> {
            }
        }
        if (key.getKeyType() != KeyType.Character || key.getCharacter() == null) {
            return false;
        }

        return switch (key.getCharacter()) {
            case 'q', 'Q' -> {
                close();
                yield true;
            }
            case 'r' -> {
                refreshActive(true);
                yield true;
            }
            case 'P' -> {
                pruneUnused();
                yield true;
            }
            case 'a' -> {
                toggleAutoRefresh();
                yield true;
            }
            case '1' -> {
                selectTab(0);
                yield true;
            }
            case '2' -> {
                selectTab(1);
                yield true;
            }
            case '3' -> {
                selectTab(2);
                yield true;
            }
            case '4' -> {
                selectTab(3);
                yield true;
            }
            case '/' -> activePanel().handleKey(key);
            case '?' -> {
                showHelp();
                yield true;
            }
            default -> activePanel().handleKey(key);
        };
    }

    private void pruneUnused() {
        if (!ConfirmDialog.ask(
                gui,
                "Prune unused resources",
                """
                        Remove stopped containers, dangling images,
                        unused networks and unused volumes?

                        Running containers and tagged images are kept.""")) {
            message = "Prune cancelled";
            paintChrome();
            activePanel().getTable().takeFocus();
            return;
        }
        try {
            message = "Pruning unused Docker resources…";
            paintChrome();
            PruneResult result = docker.pruneUnused();
            String summary = "Reclaimed "
                    + DockerService.formatBytes(result.totalBytes())
                    + "  ·  ctr "
                    + DockerService.formatBytes(result.containersBytes())
                    + " · img "
                    + DockerService.formatBytes(result.imagesBytes())
                    + " · net "
                    + DockerService.formatBytes(result.networksBytes())
                    + " · vol "
                    + DockerService.formatBytes(result.volumesBytes());
            message = result.reclaimedAnything() ? summary : "Prune done — nothing to reclaim";
            MessageDialog.showMessageDialog(
                    gui,
                    "Prune complete",
                    result.reclaimedAnything()
                            ? summary.replace("  ·  ", "\n")
                            : "Nothing to reclaim — Docker is already tidy.",
                    MessageDialogButton.OK
            );
            refreshActive(true);
        } catch (Exception e) {
            message = "Prune failed: " + DockerService.friendlyMessage(e);
            MessageDialog.showMessageDialog(
                    gui,
                    "Prune failed",
                    DockerService.friendlyMessage(e),
                    MessageDialogButton.OK
            );
            paintChrome();
            activePanel().getTable().takeFocus();
        }
    }

    private void toggleAutoRefresh() {
        autoRefresh = !autoRefresh;
        autoButton.setLabel(autoRefresh ? " Auto on " : " Auto off ");
        autoButton.setTheme(OrcaTheme.chip(autoRefresh ? Palette.RUNNING : Palette.MUTED, Palette.RAISED));
        message = autoRefresh ? "Auto-refresh every " + AUTO_REFRESH_SECONDS + "s" : "Auto-refresh paused";
        paintChrome();
    }

    private void selectTab(int index) {
        selectedTab = index;
        content.removeAllComponents();
        content.addComponent(panels[index]);

        String[] labels = {"Containers", "Images", "Networks", "Volumes"};
        for (int i = 0; i < tabs.length; i++) {
            tabs[i].setTheme(OrcaTheme.tab(i == index));
            tabs[i].setLabel((i == index ? " ▌" : "  ") + labels[i] + " ");
            panels[i].setActive(i == index);
        }

        if (lastSize != null) {
            relayout();
        }
        refreshActive();
    }

    private TablePanel<?> activePanel() {
        return panels[selectedTab];
    }

    /**
     * Refreshes the active tab immediately. Inactive tabs refresh on a slower cadence so
     * header KPIs stay honest without blocking the UI on every tick.
     */
    private void refreshActive() {
        refreshActive(false);
    }

    private void refreshActive(boolean forceAll) {
        TablePanel<?> active = activePanel();
        active.refresh(true);

        long now = System.currentTimeMillis();
        boolean refreshOthers = forceAll || now - lastKpiRefreshMillis >= KPI_REFRESH_SECONDS * 1000L;
        if (refreshOthers) {
            for (TablePanel<?> panel : panels) {
                if (panel != active) {
                    panel.refresh(false);
                }
            }
            lastKpiRefreshMillis = now;
        } else if (active != containersPanel) {
            // Running/stopped KPIs should stay relatively fresh even on other tabs.
            containersPanel.refresh(false);
        }

        lastRefreshMillis = now;
        paintChrome();
        active.getTable().takeFocus();
        // Deferred: a click on a toolbar button hands focus back to the button once the event
        // finishes, so we reclaim the table on the next GUI tick.
        gui.getGUIThread().invokeLater(() -> activePanel().getTable().takeFocus());
    }

    private void showHelp() {
        MessageDialog.showMessageDialog(
                gui,
                "orca — help",
                """
                        Mouse
                          Click a tab to switch views
                          Click a toolbar button to run an action
                          Click a table row to select it
                          Hold Shift to select text for copying

                        Keyboard
                          1 2 3 4  switch views         r   refresh now
                          /        filter by name…      Esc clear filter
                          P        prune unused         a   pause/resume auto-refresh
                          ↑ ↓      move the selection   ?   this help
                                                        q   quit

                        Containers   c create · s start · x stop · R restart · l logs · m mounts · g graph · d delete
                                     live CPU / MEM / NET columns refresh with Auto
                        Images       p pull · d delete
                        Networks     c create · g graph · d delete
                        Volumes      c create · m mounts · g graph · d delete

                        Graph shows a 1-hop ego map: container ↔ networks ↔ volumes/binds.

                        Prune removes stopped containers, dangling images,
                        unused networks and unused volumes (with confirmation).

                        Badges
                          ● RUN / ● OK   running   ○ EXIT / ○ STOP   stopped
                          ● BAD          unhealthy   ▓░░░░░           auto-refresh meter
                        """,
                MessageDialogButton.OK
        );
    }
}

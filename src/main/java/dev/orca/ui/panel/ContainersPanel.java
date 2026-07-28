package dev.orca.ui.panel;

import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import dev.orca.docker.DockerService;
import dev.orca.model.ContainerView;
import dev.orca.ui.Badges;
import dev.orca.ui.Columns;
import dev.orca.ui.Palette;
import dev.orca.ui.StatusSink;
import dev.orca.ui.UiBars;
import dev.orca.model.MountView;
import dev.orca.ui.dialog.ConfirmDialog;
import dev.orca.ui.dialog.CreateContainerDialog;
import dev.orca.ui.dialog.LogViewerDialog;
import dev.orca.ui.dialog.TextViewerDialog;

import java.util.List;
import java.util.stream.Collectors;

public class ContainersPanel extends TablePanel<ContainerView> {

    private static final Columns COLUMNS = new Columns(
            new int[]{0, 5, 5, 3, 4},
            new int[]{7, 14, 16, 12, 14}
    );

    private final DockerService docker;

    public ContainersPanel(DockerService docker, WindowBasedTextGUI gui, StatusSink status) {
        super(gui, status, COLUMNS, "State", "Name", "Image", "Uptime", "Ports");
        this.docker = docker;

        Panel toolbar = UiBars.horizontal(
                UiBars.chip("+ Create", this::createContainer, Palette.ACCENT),
                UiBars.chip("▶ Start", () -> runOnSelected("Started", docker::start), Palette.RUNNING),
                UiBars.chip("■ Stop", () -> runOnSelected("Stopped", docker::stop), Palette.WARNING),
                UiBars.button("↻ Restart", () -> runOnSelected("Restarted", docker::restart)),
                UiBars.button("☰ Logs", this::showLogs),
                UiBars.button("⧉ Mounts", this::showMounts),
                UiBars.chip("× Delete", this::deleteSelected, Palette.STOPPED)
        );
        assemble(toolbar);
    }

    @Override
    protected List<ContainerView> load() throws Exception {
        return docker.listContainers(true);
    }

    @Override
    protected String[] cells(ContainerView container) {
        return new String[]{
                Badges.containerState(container.running(), container.status()),
                container.name(),
                container.image(),
                Badges.containerDetail(container.status()),
                container.ports()
        };
    }

    @Override
    protected TextColor color(ContainerView container, int column) {
        return switch (column) {
            case 0 -> Badges.containerColor(container.running(), container.status());
            case 2 -> Palette.MUTED;
            case 3 -> Palette.DIM;
            case 4 -> Palette.DIM;
            default -> null;
        };
    }

    @Override
    protected String identity(ContainerView container) {
        return container.id();
    }

    @Override
    protected String searchText(ContainerView container) {
        return container.name() + " " + container.image() + " " + container.status()
                + " " + container.ports() + " " + (container.running() ? "running" : "stopped exit");
    }

    @Override
    protected String noun() {
        return "containers";
    }

    @Override
    public String describeSelection() {
        ContainerView container = selected();
        if (container == null) {
            return "No container selected";
        }
        return Badges.containerState(container.running(), container.status()).trim()
                + "  " + label(container)
                + "  ·  " + container.image()
                + (container.ports().isBlank() ? "" : "  ·  " + container.ports());
    }

    public long runningCount() {
        return allItems().stream().filter(ContainerView::running).count();
    }

    public long stoppedCount() {
        return Math.max(0, allItems().size() - runningCount());
    }

    @Override
    protected boolean handleShortcut(char shortcut) {
        switch (shortcut) {
            case 'c' -> createContainer();
            case 's' -> runOnSelected("Started", docker::start);
            case 'x' -> runOnSelected("Stopped", docker::stop);
            case 'R' -> runOnSelected("Restarted", docker::restart);
            case 'l' -> showLogs();
            case 'm' -> showMounts();
            case 'd' -> deleteSelected();
            default -> {
                return false;
            }
        }
        return true;
    }

    private void createContainer() {
        CreateContainerDialog.show(gui).ifPresent(result -> {
            try {
                status.setStatus("Creating container from " + result.image() + "…");
                String id = docker.createAndStart(
                        result.name(),
                        result.image(),
                        result.ports(),
                        result.env(),
                        result.command()
                );
                status.setStatus("Created " + id.substring(0, Math.min(12, id.length())));
                refresh();
                focusTable();
            } catch (Exception e) {
                showError("Create container failed", e);
            }
        });
        focusTable();
    }

    private void showLogs() {
        ContainerView container = selected();
        if (container == null) {
            requireSelection("open the logs");
            return;
        }
        try {
            status.setStatus("Fetching logs…");
            LogViewerDialog.show(gui, label(container), docker.logs(container.id(), 200));
            status.setStatus("Logs closed");
        } catch (Exception e) {
            showError("Logs failed", e);
        }
        focusTable();
    }

    private void showMounts() {
        ContainerView container = selected();
        if (container == null) {
            requireSelection("inspect mounts");
            return;
        }
        try {
            status.setStatus("Fetching mounts…");
            List<MountView> mounts = docker.listContainerMounts(container.id());
            String body;
            if (mounts.isEmpty()) {
                body = "No mounts on this container.";
            } else {
                body = mounts.stream()
                        .map(mount -> String.format(
                                "%-7s  %s%n         → %s  (%s)%s",
                                mount.typeLabel(),
                                mount.displaySource(),
                                mount.destination(),
                                mount.access(),
                                mount.mode().isBlank() ? "" : "  " + mount.mode()
                        ))
                        .collect(Collectors.joining("\n"));
            }
            TextViewerDialog.show(
                    gui,
                    "Mounts — " + label(container),
                    body,
                    mounts.size() + " mount(s)"
            );
            status.setStatus("Mounts closed");
        } catch (Exception e) {
            showError("Mounts failed", e);
        }
        focusTable();
    }

    private void deleteSelected() {
        ContainerView container = selected();
        if (container == null) {
            requireSelection("delete a container");
            return;
        }
        if (!ConfirmDialog.ask(gui, "Delete container", "Force remove container '" + label(container) + "'?")) {
            status.setStatus("Delete cancelled");
            focusTable();
            return;
        }
        try {
            docker.removeContainer(container.id(), true);
            status.setStatus("Removed " + label(container));
            refresh();
        } catch (Exception e) {
            showError("Delete failed", e);
        }
        focusTable();
    }

    private void runOnSelected(String pastTense, ContainerAction action) {
        ContainerView container = selected();
        if (container == null) {
            requireSelection("use this action");
            return;
        }
        String name = label(container);
        boolean wasRunning = container.running();
        try {
            action.run(container.id());
            String note = switch (pastTense) {
                case "Stopped" -> wasRunning ? "Stopped " + name : "Already stopped — " + name;
                case "Started" -> wasRunning ? "Already running — " + name : "Started " + name;
                default -> pastTense + " " + name;
            };
            status.setStatus(note);
            refresh();
        } catch (Exception e) {
            showError(pastTense + " failed", e);
        }
        focusTable();
    }

    private static String label(ContainerView container) {
        return container.name().isBlank() ? container.shortId() : container.name();
    }

    @FunctionalInterface
    private interface ContainerAction {
        void run(String id);
    }
}

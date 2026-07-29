package dev.orca.ui.panel;

import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import dev.orca.docker.DockerService;
import dev.orca.model.VolumeView;
import dev.orca.ui.Columns;
import dev.orca.ui.Palette;
import dev.orca.ui.StatusSink;
import dev.orca.ui.UiBars;
import dev.orca.ui.dialog.ConfirmDialog;
import dev.orca.ui.dialog.CreateVolumeDialog;
import dev.orca.ui.dialog.TextViewerDialog;

import java.util.List;
import java.util.stream.Collectors;

public class VolumesPanel extends TablePanel<VolumeView> {

    private static final Columns COLUMNS = new Columns(
            new int[]{5, 2, 2, 0},
            new int[]{16, 10, 12, 20}
    );

    private final DockerService docker;

    public VolumesPanel(DockerService docker, WindowBasedTextGUI gui, StatusSink status) {
        super(gui, status, COLUMNS, "Name", "Driver", "In use", "Mountpoint");
        this.docker = docker;

        Panel toolbar = UiBars.horizontal(
                UiBars.chip("+ Create", this::createVolume, Palette.ACCENT),
                UiBars.button("☰ Mounts", this::showMounts),
                UiBars.chip("× Delete", this::deleteSelected, Palette.STOPPED)
        );
        assemble(toolbar);
    }

    @Override
    protected List<VolumeView> load() throws Exception {
        // Mount usage scan is expensive — only when this tab is visible.
        return docker.listVolumes(isActive());
    }

    @Override
    protected String[] cells(VolumeView volume) {
        return new String[]{
                volume.name(),
                volume.driver(),
                volume.usageSummary(),
                volume.mountpoint()
        };
    }

    @Override
    protected TextColor color(VolumeView volume, int column) {
        return switch (column) {
            case 1 -> Palette.MUTED;
            case 2 -> volume.useCount() > 0 ? Palette.RUNNING : Palette.DIM;
            case 3 -> Palette.DIM;
            default -> null;
        };
    }

    @Override
    protected String identity(VolumeView volume) {
        return volume.name();
    }

    @Override
    protected String searchText(VolumeView volume) {
        return volume.name() + " " + volume.driver() + " " + volume.mountpoint()
                + " " + volume.usageSummary()
                + " " + String.join(" ", volume.usages());
    }

    @Override
    protected String noun() {
        return "volumes";
    }

    @Override
    public String describeSelection() {
        VolumeView volume = selected();
        if (volume == null) {
            return "No volume selected";
        }
        return volume.name() + "  ·  " + volume.driver() + "  ·  " + volume.usageSummary();
    }

    @Override
    protected boolean handleShortcut(char shortcut) {
        switch (shortcut) {
            case 'c' -> createVolume();
            case 'm' -> showMounts();
            case 'd' -> deleteSelected();
            default -> {
                return false;
            }
        }
        return true;
    }

    private void createVolume() {
        CreateVolumeDialog.show(gui).ifPresent(result -> {
            try {
                docker.createVolume(result.name(), result.driver());
                status.setStatus("Created volume " + result.name());
                refresh();
            } catch (Exception e) {
                showError("Create volume failed", e);
            }
            focusTable();
        });
        focusTable();
    }

    private void showMounts() {
        VolumeView volume = selected();
        if (volume == null) {
            requireSelection("inspect mounts");
            return;
        }
        String body;
        if (volume.usages().isEmpty()) {
            body = "No containers are mounting this volume.";
        } else {
            body = volume.usages().stream().collect(Collectors.joining("\n"));
        }
        TextViewerDialog.show(
                gui,
                "Mounts — " + volume.name(),
                body,
                volume.useCount() + " mount(s)  ·  " + volume.mountpoint()
        );
        status.setStatus("Mounts closed");
        focusTable();
    }

    private void deleteSelected() {
        VolumeView volume = selected();
        if (volume == null) {
            requireSelection("delete a volume");
            return;
        }
        if (volume.useCount() > 0) {
            status.setStatus("Volume is in use by " + volume.useCount() + " container(s)");
            if (!ConfirmDialog.ask(
                    gui,
                    "Volume in use",
                    "Volume '" + volume.name() + "' is mounted by "
                            + volume.useCount() + " container(s). Delete anyway? (may fail if still attached)")) {
                status.setStatus("Delete cancelled");
                focusTable();
                return;
            }
        } else if (!ConfirmDialog.ask(gui, "Delete volume", "Remove volume '" + volume.name() + "'?")) {
            status.setStatus("Delete cancelled");
            focusTable();
            return;
        }
        try {
            docker.removeVolume(volume.name());
            status.setStatus("Removed " + volume.name());
            refresh();
        } catch (Exception e) {
            showError("Delete failed", e);
        }
        focusTable();
    }
}

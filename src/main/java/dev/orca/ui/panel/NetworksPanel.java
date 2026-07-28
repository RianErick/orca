package dev.orca.ui.panel;

import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import com.googlecode.lanterna.gui2.dialogs.MessageDialog;
import com.googlecode.lanterna.gui2.dialogs.MessageDialogButton;
import dev.orca.docker.DockerService;
import dev.orca.model.NetworkView;
import dev.orca.ui.Badges;
import dev.orca.ui.Columns;
import dev.orca.ui.Palette;
import dev.orca.ui.StatusSink;
import dev.orca.ui.UiBars;
import dev.orca.ui.dialog.ConfirmDialog;
import dev.orca.ui.dialog.CreateNetworkDialog;

import java.util.List;
import java.util.Set;

public class NetworksPanel extends TablePanel<NetworkView> {

    private static final Set<String> BUILT_IN = Set.of("bridge", "host", "none");

    private static final Columns COLUMNS = new Columns(
            new int[]{0, 5, 2, 1, 0},
            new int[]{12, 18, 10, 8, 7}
    );

    private final DockerService docker;

    public NetworksPanel(DockerService docker, WindowBasedTextGUI gui, StatusSink status) {
        super(gui, status, COLUMNS, "Id", "Name", "Driver", "Scope", "Kind");
        this.docker = docker;

        Panel toolbar = UiBars.horizontal(
                UiBars.chip("+ Create", this::createNetwork, Palette.ACCENT),
                UiBars.chip("× Delete", this::deleteSelected, Palette.STOPPED)
        );
        assemble(toolbar);
    }

    @Override
    protected List<NetworkView> load() throws Exception {
        return docker.listNetworks();
    }

    @Override
    protected String[] cells(NetworkView network) {
        boolean builtIn = BUILT_IN.contains(network.name());
        return new String[]{
                network.shortId(),
                network.name(),
                network.driver(),
                network.scope(),
                Badges.networkKind(builtIn)
        };
    }

    @Override
    protected TextColor color(NetworkView network, int column) {
        boolean builtIn = BUILT_IN.contains(network.name());
        return switch (column) {
            case 0 -> Palette.DIM;
            case 1 -> builtIn ? Palette.MUTED : null;
            case 2, 3 -> Palette.MUTED;
            case 4 -> Badges.networkKindColor(builtIn);
            default -> null;
        };
    }

    @Override
    protected String identity(NetworkView network) {
        return network.id();
    }

    @Override
    protected String noun() {
        return "networks";
    }

    @Override
    public String describeSelection() {
        NetworkView network = selected();
        if (network == null) {
            return "No network selected";
        }
        String kind = BUILT_IN.contains(network.name()) ? "built-in" : "user-defined";
        return network.name() + "  ·  " + network.driver() + "  ·  " + kind;
    }

    public long userDefinedCount() {
        return allItems().stream().filter(network -> !BUILT_IN.contains(network.name())).count();
    }

    @Override
    protected String searchText(NetworkView network) {
        boolean builtIn = BUILT_IN.contains(network.name());
        return network.shortId() + " " + network.name() + " " + network.driver()
                + " " + network.scope() + (builtIn ? " built-in" : " user-defined");
    }

    @Override
    protected boolean handleShortcut(char shortcut) {
        switch (shortcut) {
            case 'c' -> createNetwork();
            case 'd' -> deleteSelected();
            default -> {
                return false;
            }
        }
        return true;
    }

    private void createNetwork() {
        CreateNetworkDialog.show(gui).ifPresent(result -> {
            try {
                docker.createNetwork(result.name(), result.driver());
                status.setStatus("Created network " + result.name());
                refresh();
            } catch (Exception e) {
                showError("Create network failed", e);
            }
            focusTable();
        });
        focusTable();
    }

    private void deleteSelected() {
        NetworkView network = selected();
        if (network == null) {
            requireSelection("delete a network");
            return;
        }
        if (BUILT_IN.contains(network.name())) {
            status.setStatus("'" + network.name() + "' is built-in and cannot be removed");
            MessageDialog.showMessageDialog(
                    gui,
                    "Protected network",
                    "Network '" + network.name() + "' is built-in and cannot be removed.",
                    MessageDialogButton.OK
            );
            focusTable();
            return;
        }
        if (!ConfirmDialog.ask(gui, "Delete network", "Remove network '" + network.name() + "'?")) {
            status.setStatus("Delete cancelled");
            focusTable();
            return;
        }
        try {
            docker.removeNetwork(network.id());
            status.setStatus("Removed " + network.name());
            refresh();
        } catch (Exception e) {
            showError("Delete failed", e);
        }
        focusTable();
    }
}

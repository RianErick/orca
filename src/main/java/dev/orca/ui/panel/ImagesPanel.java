package dev.orca.ui.panel;

import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import dev.orca.docker.DockerService;
import dev.orca.model.ImageView;
import dev.orca.ui.Badges;
import dev.orca.ui.Columns;
import dev.orca.ui.Palette;
import dev.orca.ui.StatusSink;
import dev.orca.ui.UiBars;
import dev.orca.ui.dialog.ConfirmDialog;
import dev.orca.ui.dialog.PullImageDialog;

import java.util.List;

public class ImagesPanel extends TablePanel<ImageView> {

    private static final String UNTAGGED = "<none>:<none>";

    private static final Columns COLUMNS = new Columns(
            new int[]{0, 6, 2, 2},
            new int[]{12, 24, 10, 10}
    );

    private final DockerService docker;
    private long maxSizeBytes;

    public ImagesPanel(DockerService docker, WindowBasedTextGUI gui, StatusSink status) {
        super(gui, status, COLUMNS, "Id", "Repository:Tag", "Size", "Usage");
        this.docker = docker;

        Panel toolbar = UiBars.horizontal(
                UiBars.chip("↓ Pull", this::pullImage, Palette.ACCENT),
                UiBars.chip("× Delete", this::deleteSelected, Palette.STOPPED)
        );
        assemble(toolbar);
    }

    @Override
    protected List<ImageView> load() throws Exception {
        List<ImageView> images = docker.listImages();
        maxSizeBytes = images.stream().mapToLong(ImageView::sizeBytes).max().orElse(0L);
        return images;
    }

    @Override
    protected String[] cells(ImageView image) {
        double ratio = maxSizeBytes <= 0 ? 0 : (double) image.sizeBytes() / maxSizeBytes;
        return new String[]{
                image.shortId(),
                image.repositoryTag(),
                image.size(),
                Badges.bar(ratio, 10)
        };
    }

    @Override
    protected TextColor color(ImageView image, int column) {
        return switch (column) {
            case 0 -> Palette.DIM;
            case 1 -> UNTAGGED.equals(image.repositoryTag()) ? Palette.WARNING : null;
            case 2 -> Palette.MUTED;
            case 3 -> UNTAGGED.equals(image.repositoryTag()) ? Palette.WARNING : Palette.ACCENT;
            default -> null;
        };
    }

    @Override
    protected String identity(ImageView image) {
        return image.id() + "|" + image.repositoryTag();
    }

    @Override
    protected String noun() {
        return "images";
    }

    @Override
    public String describeSelection() {
        ImageView image = selected();
        if (image == null) {
            return "No image selected";
        }
        String tag = UNTAGGED.equals(image.repositoryTag()) ? "untagged" : image.repositoryTag();
        return tag + "  ·  " + image.size() + "  ·  " + image.shortId();
    }

    public long untaggedCount() {
        return allItems().stream().filter(image -> UNTAGGED.equals(image.repositoryTag())).count();
    }

    @Override
    protected String searchText(ImageView image) {
        return image.shortId() + " " + image.repositoryTag() + " " + image.size()
                + (UNTAGGED.equals(image.repositoryTag()) ? " dangling untagged none" : "");
    }

    @Override
    protected boolean handleShortcut(char shortcut) {
        switch (shortcut) {
            case 'p' -> pullImage();
            case 'd' -> deleteSelected();
            default -> {
                return false;
            }
        }
        return true;
    }

    private void pullImage() {
        PullImageDialog.show(gui).ifPresent(imageRef -> {
            try {
                status.setStatus("Pulling " + imageRef + "…");
                docker.pull(imageRef, message -> status.setStatus("Pull: " + message));
                status.setStatus("Pulled " + imageRef);
                refresh();
            } catch (Exception e) {
                showError("Pull failed", e);
            }
            focusTable();
        });
        focusTable();
    }

    private void deleteSelected() {
        ImageView image = selected();
        if (image == null) {
            requireSelection("delete an image");
            return;
        }
        if (!ConfirmDialog.ask(gui, "Delete image", "Force remove image '" + image.repositoryTag() + "'?")) {
            status.setStatus("Delete cancelled");
            focusTable();
            return;
        }
        try {
            docker.removeImage(image.id(), true);
            status.setStatus("Removed " + image.repositoryTag());
            refresh();
        } catch (Exception e) {
            showError("Delete failed", e);
        }
        focusTable();
    }
}

package dev.orca.ui;

import com.googlecode.lanterna.TextColor;

/**
 * Colours shared by the theme and the table renderers.
 * Dark surface with a cool accent — readable on truecolor terminals.
 */
public final class Palette {

    public static final TextColor.RGB BACKGROUND = new TextColor.RGB(11, 15, 20);
    public static final TextColor.RGB SURFACE = new TextColor.RGB(18, 24, 32);
    public static final TextColor.RGB RAISED = new TextColor.RGB(36, 44, 56);
    public static final TextColor.RGB BORDER = new TextColor.RGB(48, 58, 72);
    public static final TextColor.RGB SELECTION = new TextColor.RGB(22, 58, 92);
    public static final TextColor.RGB SELECTION_IDLE = new TextColor.RGB(26, 34, 46);

    public static final TextColor.RGB TEXT = new TextColor.RGB(232, 238, 245);
    public static final TextColor.RGB MUTED = new TextColor.RGB(130, 140, 152);
    public static final TextColor.RGB DIM = new TextColor.RGB(82, 92, 104);

    public static final TextColor.RGB ACCENT = new TextColor.RGB(56, 189, 248);
    public static final TextColor.RGB ACCENT_SOFT = new TextColor.RGB(34, 90, 120);
    public static final TextColor.RGB RUNNING = new TextColor.RGB(52, 211, 120);
    public static final TextColor.RGB STOPPED = new TextColor.RGB(248, 113, 113);
    public static final TextColor.RGB WARNING = new TextColor.RGB(251, 191, 36);

    private Palette() {
    }
}

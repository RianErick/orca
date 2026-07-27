package dev.orca.ui;

import com.googlecode.lanterna.terminal.MouseCaptureMode;

import java.io.IOException;

/**
 * Turns on mouse reporting using the SGR protocol (CSI ?1006h).
 *
 * Lanterna only emits the legacy X10 encoding, which cannot express coordinates beyond column/row 223
 * and gets corrupted once Lanterna turns on UTF-8 mouse mode (CSI ?1005h). SGR has neither problem.
 */
public final class MouseSupport {

    private static final String ENABLE = "\u001b[?1005l"  // UTF-8 mouse off, it breaks the X10 parser
            + "\u001b[?1000h"                             // press + release reporting
            + "\u001b[?1006h";                            // SGR encoding

    private static final String DISABLE = "\u001b[?1006l\u001b[?1005l\u001b[?1000l";

    private MouseSupport() {
    }

    public static boolean enable(OrcaTerminal terminal) {
        try {
            // Lanterna filters incoming mouse events against this mode, so it has to agree with ENABLE.
            terminal.setMouseCaptureMode(MouseCaptureMode.CLICK_RELEASE);
            terminal.writeAnsi(ENABLE);
            return true;
        } catch (IOException e) {
            System.err.println("Mouse capture unavailable: " + e.getMessage());
            return false;
        }
    }

    public static void disable(OrcaTerminal terminal) {
        if (terminal == null) {
            return;
        }
        try {
            terminal.writeAnsi(DISABLE);
            terminal.setMouseCaptureMode(null);
        } catch (Exception ignored) {
            // best-effort restore
        }
    }
}

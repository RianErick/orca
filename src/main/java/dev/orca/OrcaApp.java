package dev.orca;

import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import dev.orca.docker.DockerClientFactory;
import dev.orca.docker.DockerService;
import dev.orca.ui.MainWindow;
import dev.orca.ui.MouseSupport;
import dev.orca.ui.OrcaTerminal;
import dev.orca.ui.OrcaTheme;

import java.io.IOException;

public final class OrcaApp {

    public static void main(String[] args) {
        DockerService docker = null;
        OrcaTerminal terminal = null;
        Screen screen = null;
        boolean mouseEnabled = false;
        try {
            docker = new DockerService(DockerClientFactory.create());

            terminal = new OrcaTerminal();
            screen = new TerminalScreen(terminal);
            screen.startScreen();
            screen.setCursorPosition(null);

            mouseEnabled = MouseSupport.enable(terminal);

            MultiWindowTextGUI gui = new MultiWindowTextGUI(screen);
            gui.setTheme(OrcaTheme.create());

            MainWindow window = new MainWindow(gui, docker, mouseEnabled);
            window.initialLoad();
            gui.addWindowAndWait(window);
        } catch (Exception e) {
            System.err.println("Failed to start orca: " + DockerService.friendlyMessage(e));
            System.exit(1);
        } finally {
            if (mouseEnabled) {
                MouseSupport.disable(terminal);
            }
            if (screen != null) {
                try {
                    screen.stopScreen();
                } catch (IOException ignored) {
                    // ignore shutdown errors
                }
            }
            if (docker != null) {
                docker.close();
            }
        }
    }

    private OrcaApp() {
    }
}

package dev.orca.ui;

import com.googlecode.lanterna.input.CharacterPattern;
import com.googlecode.lanterna.input.InputDecoder;
import com.googlecode.lanterna.terminal.ansi.UnixTerminal;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Unix terminal that understands SGR mouse reports and can emit raw control sequences.
 *
 * {@link com.googlecode.lanterna.terminal.ansi.StreamBasedTerminal#putCharacter(char)} silently drops
 * non-printable characters, so escape sequences written through the public API never reach the terminal.
 */
public final class OrcaTerminal extends UnixTerminal {

    private static final File CONTROLLING_TTY = new File("/dev/tty");

    /**
     * Resolved before the superclass constructor runs, which already shells out to stty.
     */
    private static final boolean CONTROLLING_TTY_USABLE = probeControllingTty();

    public OrcaTerminal() throws IOException {
        super(System.in, System.out, StandardCharsets.UTF_8, CtrlCBehaviour.CTRL_C_KILLS_APPLICATION);

        // Registered up front: probing the terminal size already consumes input, and anything
        // decoded before this point would be queued as garbage keystrokes.
        InputDecoder decoder = getInputDecoder();
        decoder.addProfile(() -> List.<CharacterPattern>of(new SgrMouseCharacterPattern()));

        // Without a timeout the decoder abandons a half-read escape sequence as soon as the input
        // stream runs dry, reporting the leading "ESC [" as Alt+[ instead.
        decoder.setTimeoutUnits(1);
    }

    public void writeAnsi(String sequence) throws IOException {
        writeToTerminal(sequence.getBytes(StandardCharsets.US_ASCII));
        flush();
    }

    /**
     * Runs stty against our own standard input when /dev/tty is unreachable.
     *
     * Lanterna always points stty at /dev/tty, which fails with "no such device or address" whenever the
     * process has no controlling terminal — the case in several embedded and multiplexed terminals.
     */
    @Override
    protected String exec(String... cmd) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(cmd);
        builder.redirectInput(CONTROLLING_TTY_USABLE
                ? ProcessBuilder.Redirect.from(CONTROLLING_TTY)
                : ProcessBuilder.Redirect.INHERIT);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);

        Process process = builder.start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream stdout = process.getInputStream()) {
            stdout.transferTo(output);
        }
        return output.toString(StandardCharsets.UTF_8).replace("\n", "").replace("\r", "");
    }

    private static boolean probeControllingTty() {
        try (FileInputStream ignored = new FileInputStream(CONTROLLING_TTY)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}

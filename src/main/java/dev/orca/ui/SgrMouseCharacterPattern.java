package dev.orca.ui;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.input.CharacterPattern;
import com.googlecode.lanterna.input.KeyDecodingProfile;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.input.MouseActionType;

import java.util.List;

/**
 * Parses modern SGR mouse sequences: ESC [ &lt; b ; x ; y M/m
 * (enabled via CSI ?1006h). Lanterna 3.1 only ships the legacy X10 parser.
 */
public final class SgrMouseCharacterPattern implements CharacterPattern {

    @Override
    public Matching match(List<Character> seq) {
        int size = seq.size();
        if (size == 0) {
            return Matching.NOT_YET;
        }
        if (seq.get(0) != KeyDecodingProfile.ESC_CODE) {
            return null;
        }
        if (size == 1) {
            return Matching.NOT_YET;
        }
        if (seq.get(1) != '[') {
            return null;
        }
        if (size == 2) {
            return Matching.NOT_YET;
        }
        if (seq.get(2) != '<') {
            return null;
        }

        int b = -1;
        int x = -1;
        int y = -1;
        int field = 0;
        int value = 0;
        boolean inNumber = false;

        for (int i = 3; i < size; i++) {
            char ch = seq.get(i);
            if (ch >= '0' && ch <= '9') {
                value = value * 10 + (ch - '0');
                inNumber = true;
                continue;
            }
            if (ch == ';') {
                if (!inNumber) {
                    return null;
                }
                if (field == 0) {
                    b = value;
                } else if (field == 1) {
                    x = value;
                } else {
                    return null;
                }
                field++;
                value = 0;
                inNumber = false;
                continue;
            }
            if (ch == 'M' || ch == 'm') {
                if (!inNumber || field != 2) {
                    return null;
                }
                y = value;
                boolean release = ch == 'm';
                MouseActionType type = decodeType(b, release);
                int button = decodeButton(b);
                // SGR reports 1-based coordinates
                TerminalPosition pos = new TerminalPosition(Math.max(0, x - 1), Math.max(0, y - 1));
                return new Matching(new MouseAction(type, button, pos));
            }
            return null;
        }
        return Matching.NOT_YET;
    }

    private static MouseActionType decodeType(int b, boolean release) {
        if (release || (b & 3) == 3) {
            return MouseActionType.CLICK_RELEASE;
        }
        if ((b & 32) != 0) {
            return MouseActionType.MOVE;
        }
        if ((b & 64) != 0) {
            return (b & 1) == 0 ? MouseActionType.SCROLL_UP : MouseActionType.SCROLL_DOWN;
        }
        return MouseActionType.CLICK_DOWN;
    }

    private static int decodeButton(int b) {
        int code = b & 3;
        if (code == 3) {
            return 0;
        }
        if ((b & 64) != 0) {
            return (b & 1) == 0 ? 4 : 5;
        }
        return code + 1;
    }
}

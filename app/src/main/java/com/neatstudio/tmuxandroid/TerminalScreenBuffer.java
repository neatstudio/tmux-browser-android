package com.neatstudio.tmuxandroid;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;

import java.util.ArrayList;
import java.util.List;

final class TerminalScreenBuffer {
    private static final int DEFAULT_FG = 0xffe6ebf2;
    private static final int DEFAULT_BG = Color.TRANSPARENT;
    private static final int TERMINAL_BG = 0xff0b0e13;

    private int cols;
    private int rows;
    private Cell[][] cells;
    private int cursorRow;
    private int cursorCol;
    private int savedRow;
    private int savedCol;
    private String pendingControl = "";
    private boolean wrapPending;
    private int fg = DEFAULT_FG;
    private int bg = DEFAULT_BG;
    private boolean bold;
    private boolean dim;

    TerminalScreenBuffer(int cols, int rows) {
        resize(cols, rows);
    }

    void resize(int nextCols, int nextRows) {
        nextCols = Math.max(1, nextCols);
        nextRows = Math.max(1, nextRows);
        Cell[][] previous = cells;
        int previousRows = rows;
        int previousCols = cols;
        cols = nextCols;
        rows = nextRows;
        cells = new Cell[rows][cols];
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                cells[row][col] = new Cell();
            }
        }
        if (previous != null) {
            int copyRows = Math.min(previousRows, rows);
            int copyCols = Math.min(previousCols, cols);
            int previousStart = Math.max(0, previousRows - copyRows);
            int nextStart = Math.max(0, rows - copyRows);
            for (int row = 0; row < copyRows; row++) {
                for (int col = 0; col < copyCols; col++) {
                    cells[nextStart + row][col].copyFrom(previous[previousStart + row][col]);
                }
            }
        }
        cursorRow = clamp(cursorRow, 0, rows - 1);
        cursorCol = clamp(cursorCol, 0, cols - 1);
        savedRow = clamp(savedRow, 0, rows - 1);
        savedCol = clamp(savedCol, 0, cols - 1);
    }

    void clear() {
        clearScreen();
        cursorRow = 0;
        cursorCol = 0;
        savedRow = 0;
        savedCol = 0;
        pendingControl = "";
        wrapPending = false;
        fg = DEFAULT_FG;
        bg = DEFAULT_BG;
        bold = false;
        dim = false;
    }

    void write(String text) {
        if (!pendingControl.isEmpty()) {
            text = pendingControl + text;
            pendingControl = "";
        }
        int index = 0;
        while (index < text.length()) {
            char item = text.charAt(index);
            if (item == '\u001b') {
                int next = handleEscape(text, index);
                if (next < 0) {
                    pendingControl = text.substring(index);
                    return;
                }
                index = next;
            } else if (item == '\r') {
                wrapPending = false;
                cursorCol = 0;
                index++;
            } else if (item == '\n') {
                wrapPending = false;
                newLine();
                index++;
            } else if (item == '\b') {
                wrapPending = false;
                cursorCol = Math.max(0, cursorCol - 1);
                index++;
            } else if (item == '\t') {
                int nextTab = ((cursorCol / 8) + 1) * 8;
                while (cursorCol < Math.min(nextTab, cols)) {
                    putChar(' ');
                }
                index++;
            } else if (isNakedDeviceAttributesTail(text, index)) {
                index = skipNakedDeviceAttributesTail(text, index);
            } else if (item >= 0x20 && item != 0x7f) {
                putChar(item);
                index++;
            } else {
                index++;
            }
        }
    }

    CharSequence render() {
        SpannableStringBuilder output = new SpannableStringBuilder();
        for (int row = 0; row < rows; row++) {
            appendRow(output, row);
            if (row + 1 < rows) {
                output.append('\n');
            }
        }
        return output;
    }

    CharSequence renderFocused() {
        List<String> visible = new ArrayList<>();
        int hiddenRows = 0;
        for (int row = 0; row < rows; row++) {
            String text = rowText(row).trim();
            if (containsHan(text)) {
                if (hiddenRows > 0) {
                    visible.add("... 已折叠 " + hiddenRows + " 行终端内容 ...");
                    hiddenRows = 0;
                }
                visible.add(text);
            } else if (!text.isEmpty()) {
                hiddenRows++;
            }
        }
        if (hiddenRows > 0) {
            visible.add("... 已折叠 " + hiddenRows + " 行终端内容 ...");
        }
        if (visible.isEmpty()) {
            for (int row = Math.max(0, rows - 4); row < rows; row++) {
                String text = rowText(row).trim();
                if (!text.isEmpty()) {
                    visible.add(text);
                }
            }
        }
        StringBuilder output = new StringBuilder();
        for (int index = 0; index < visible.size(); index++) {
            if (index > 0) {
                output.append('\n');
            }
            output.append(visible.get(index));
        }
        return output;
    }

    private int handleEscape(String text, int index) {
        if (index + 1 >= text.length()) {
            return -1;
        }
        char next = text.charAt(index + 1);
        wrapPending = false;
        if (next == '[') {
            int end = findAnsiEnd(text, index + 2);
            if (end == -1) {
                return -1;
            }
            applyCsi(text.substring(index + 2, end), text.charAt(end));
            return end + 1;
        }
        if (next == ']') {
            return skipStringEscape(text, index + 2);
        }
        if (next == 'P' || next == '^' || next == '_') {
            return skipStringEscape(text, index + 2);
        }
        if (next == '(' || next == ')' || next == '*' || next == '+' || next == '-' || next == '.') {
            if (index + 2 >= text.length()) {
                return -1;
            }
            return Math.min(index + 3, text.length());
        }
        if (next == '7') {
            saveCursor();
        } else if (next == '8') {
            restoreCursor();
        } else if (next == 'D') {
            newLine();
        } else if (next == 'E') {
            cursorCol = 0;
            newLine();
        } else if (next == 'M') {
            reverseIndex();
        } else if (next == 'c') {
            clear();
        }
        return Math.min(index + 2, text.length());
    }

    private void applyCsi(String rawParams, char command) {
        wrapPending = false;
        String params = rawParams;
        while (!params.isEmpty()) {
            char first = params.charAt(0);
            if (first == '?' || first == '>' || first == '!' || first == '=') {
                params = params.substring(1);
            } else {
                break;
            }
        }
        List<Integer> values = parseParams(params);
        switch (command) {
            case 'm':
                applySgr(values);
                break;
            case 'H':
            case 'f':
                cursorRow = clamp(param(values, 0, 1) - 1, 0, rows - 1);
                cursorCol = clamp(param(values, 1, 1) - 1, 0, cols - 1);
                break;
            case 'A':
                cursorRow = clamp(cursorRow - param(values, 0, 1), 0, rows - 1);
                break;
            case 'B':
                cursorRow = clamp(cursorRow + param(values, 0, 1), 0, rows - 1);
                break;
            case 'C':
                cursorCol = clamp(cursorCol + param(values, 0, 1), 0, cols - 1);
                break;
            case 'D':
                cursorCol = clamp(cursorCol - param(values, 0, 1), 0, cols - 1);
                break;
            case 'G':
                cursorCol = clamp(param(values, 0, 1) - 1, 0, cols - 1);
                break;
            case 'd':
                cursorRow = clamp(param(values, 0, 1) - 1, 0, rows - 1);
                break;
            case 'J':
                eraseDisplay(param(values, 0, 0));
                break;
            case 'K':
                eraseLine(param(values, 0, 0));
                break;
            case 'P':
                deleteChars(param(values, 0, 1));
                break;
            case '@':
                insertChars(param(values, 0, 1));
                break;
            case 'X':
                eraseChars(param(values, 0, 1));
                break;
            case 'L':
                insertLines(param(values, 0, 1));
                break;
            case 'M':
                deleteLines(param(values, 0, 1));
                break;
            case 's':
                saveCursor();
                break;
            case 'u':
                restoreCursor();
                break;
            case 'c':
                break;
            default:
                break;
        }
    }

    private void applySgr(List<Integer> values) {
        if (values.isEmpty()) {
            values.add(0);
        }
        for (int index = 0; index < values.size(); index++) {
            int value = values.get(index);
            if (value == 0) {
                fg = DEFAULT_FG;
                bg = DEFAULT_BG;
                bold = false;
                dim = false;
            } else if (value == 1) {
                bold = true;
            } else if (value == 2) {
                dim = true;
            } else if (value == 22) {
                bold = false;
                dim = false;
            } else if (value == 39) {
                fg = DEFAULT_FG;
            } else if (value == 49) {
                bg = DEFAULT_BG;
            } else if ((value >= 30 && value <= 37) || (value >= 90 && value <= 97)) {
                fg = ansiColor(value, false);
            } else if ((value >= 40 && value <= 47) || (value >= 100 && value <= 107)) {
                bg = ansiColor(value, true);
            } else if ((value == 38 || value == 48) && index + 2 < values.size()) {
                boolean background = value == 48;
                int mode = values.get(index + 1);
                if (mode == 5) {
                    int color = xtermColor(values.get(index + 2));
                    if (background) {
                        bg = color;
                    } else {
                        fg = color;
                    }
                    index += 2;
                } else if (mode == 2 && index + 4 < values.size()) {
                    int color = Color.rgb(
                            clamp(values.get(index + 2), 0, 255),
                            clamp(values.get(index + 3), 0, 255),
                            clamp(values.get(index + 4), 0, 255)
                    );
                    if (background) {
                        bg = color;
                    } else {
                        fg = color;
                    }
                    index += 4;
                }
            }
        }
    }

    private void putChar(char value) {
        if (wrapPending) {
            wrapPending = false;
            newLine();
        }
        int width = isWideCharacter(value) ? 2 : 1;
        if (width == 2 && cursorCol == cols - 1) {
            newLine();
        }
        cells[cursorRow][cursorCol].set(value, fg, bg, bold, dim);
        if (width == 2) {
            cells[cursorRow][cursorCol + 1].setContinuation(fg, bg, bold, dim);
        }
        if (cursorCol + width >= cols) {
            cursorCol = cols - 1;
            wrapPending = true;
        } else {
            cursorCol += width;
        }
    }

    private boolean isWideCharacter(char value) {
        return value >= '\u1100' && (value <= '\u115f'
                || value == '\u2329' || value == '\u232a'
                || (value >= '\u2e80' && value <= '\ua4cf' && value != '\u303f')
                || (value >= '\uac00' && value <= '\ud7a3')
                || (value >= '\uf900' && value <= '\ufaff')
                || (value >= '\ufe10' && value <= '\ufe19')
                || (value >= '\ufe30' && value <= '\ufe6f')
                || (value >= '\uff00' && value <= '\uff60')
                || (value >= '\uffe0' && value <= '\uffe6'));
    }

    private void newLine() {
        wrapPending = false;
        cursorRow++;
        if (cursorRow >= rows) {
            scrollUp(1);
            cursorRow = rows - 1;
        }
        cursorCol = 0;
    }

    private void reverseIndex() {
        if (cursorRow == 0) {
            scrollDown(1);
        } else {
            cursorRow--;
        }
    }

    private void eraseDisplay(int mode) {
        if (mode == 2 || mode == 3) {
            clearScreen();
        } else if (mode == 1) {
            for (int row = 0; row < cursorRow; row++) {
                clearLine(row, 0, cols - 1);
            }
            clearLine(cursorRow, 0, cursorCol);
        } else {
            clearLine(cursorRow, cursorCol, cols - 1);
            for (int row = cursorRow + 1; row < rows; row++) {
                clearLine(row, 0, cols - 1);
            }
        }
    }

    private void eraseLine(int mode) {
        if (mode == 2) {
            clearLine(cursorRow, 0, cols - 1);
        } else if (mode == 1) {
            clearLine(cursorRow, 0, cursorCol);
        } else {
            clearLine(cursorRow, cursorCol, cols - 1);
        }
    }

    private void eraseChars(int count) {
        int end = Math.min(cols - 1, cursorCol + Math.max(1, count) - 1);
        clearLine(cursorRow, cursorCol, end);
    }

    private void deleteChars(int count) {
        count = Math.max(1, count);
        Cell[] line = cells[cursorRow];
        for (int col = cursorCol; col < cols; col++) {
            int source = col + count;
            if (source < cols) {
                line[col].copyFrom(line[source]);
            } else {
                line[col].clear();
            }
        }
    }

    private void insertChars(int count) {
        count = Math.max(1, count);
        Cell[] line = cells[cursorRow];
        for (int col = cols - 1; col >= cursorCol; col--) {
            int source = col - count;
            if (source >= cursorCol) {
                line[col].copyFrom(line[source]);
            } else {
                line[col].clear();
            }
        }
    }

    private void insertLines(int count) {
        count = Math.min(Math.max(1, count), rows - cursorRow);
        for (int row = rows - 1; row >= cursorRow + count; row--) {
            copyLine(row, row - count);
        }
        for (int row = cursorRow; row < cursorRow + count; row++) {
            clearLine(row, 0, cols - 1);
        }
    }

    private void deleteLines(int count) {
        count = Math.min(Math.max(1, count), rows - cursorRow);
        for (int row = cursorRow; row + count < rows; row++) {
            copyLine(row, row + count);
        }
        for (int row = rows - count; row < rows; row++) {
            clearLine(row, 0, cols - 1);
        }
    }

    private void clearScreen() {
        for (int row = 0; row < rows; row++) {
            clearLine(row, 0, cols - 1);
        }
    }

    private void clearLine(int row, int start, int end) {
        start = clamp(start, 0, cols - 1);
        end = clamp(end, 0, cols - 1);
        for (int col = start; col <= end; col++) {
            cells[row][col].clear();
        }
    }

    private void scrollUp(int count) {
        count = Math.min(Math.max(1, count), rows);
        for (int row = 0; row + count < rows; row++) {
            copyLine(row, row + count);
        }
        for (int row = rows - count; row < rows; row++) {
            clearLine(row, 0, cols - 1);
        }
    }

    private void scrollDown(int count) {
        count = Math.min(Math.max(1, count), rows);
        for (int row = rows - 1; row - count >= 0; row--) {
            copyLine(row, row - count);
        }
        for (int row = 0; row < count; row++) {
            clearLine(row, 0, cols - 1);
        }
    }

    private void copyLine(int target, int source) {
        for (int col = 0; col < cols; col++) {
            cells[target][col].copyFrom(cells[source][col]);
        }
    }

    private void appendRow(SpannableStringBuilder output, int row) {
        int col = 0;
        while (col < cols) {
            Cell first = cells[row][col];
            int start = output.length();
            int fgColor = first.fg;
            int bgColor = first.bg;
            boolean isBold = first.bold;
            boolean isDim = first.dim;
            while (col < cols) {
                Cell cell = cells[row][col];
                if (cell.fg != fgColor || cell.bg != bgColor || cell.bold != isBold || cell.dim != isDim) {
                    break;
                }
                if (!cell.continuation) {
                    output.append(cell.value);
                }
                col++;
            }
            int end = output.length();
            if (isDim) {
                fgColor = blendColor(fgColor, bgColor == DEFAULT_BG ? TERMINAL_BG : bgColor, 0.55f);
            }
            output.setSpan(new ForegroundColorSpan(fgColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (bgColor != DEFAULT_BG) {
                output.setSpan(new BackgroundColorSpan(bgColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (isBold) {
                output.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
    }

    private String rowText(int row) {
        StringBuilder text = new StringBuilder(cols);
        for (int col = 0; col < cols; col++) {
            Cell cell = cells[row][col];
            if (!cell.continuation) {
                text.append(cell.value);
            }
        }
        return text.toString();
    }

    private boolean containsHan(String text) {
        for (int index = 0; index < text.length(); index++) {
            Character.UnicodeBlock block = Character.UnicodeBlock.of(text.charAt(index));
            if (block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                    || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                    || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS) {
                return true;
            }
        }
        return false;
    }

    private void saveCursor() {
        savedRow = cursorRow;
        savedCol = cursorCol;
    }

    private void restoreCursor() {
        cursorRow = clamp(savedRow, 0, rows - 1);
        cursorCol = clamp(savedCol, 0, cols - 1);
    }

    private int skipStringEscape(String text, int start) {
        int cursor = start;
        while (cursor < text.length()) {
            char item = text.charAt(cursor);
            if (item == '\u0007') {
                return cursor + 1;
            }
            if (item == '\u001b' && cursor + 1 < text.length() && text.charAt(cursor + 1) == '\\') {
                return cursor + 2;
            }
            cursor++;
        }
        return -1;
    }

    private boolean isNakedDeviceAttributesTail(String text, int index) {
        int cursor = index;
        boolean hasSemicolon = false;
        if (cursor < text.length() && (text.charAt(cursor) == '?' || text.charAt(cursor) == '>')) {
            cursor++;
        }
        while (cursor < text.length()) {
            char item = text.charAt(cursor);
            if (item >= '0' && item <= '9') {
                cursor++;
                continue;
            }
            if (item == ';') {
                hasSemicolon = true;
                cursor++;
                continue;
            }
            return item == 'c' && hasSemicolon && cursor > index && cursor - index <= 16;
        }
        return false;
    }

    private int skipNakedDeviceAttributesTail(String text, int index) {
        int cursor = index;
        while (cursor < text.length() && text.charAt(cursor) != 'c') {
            cursor++;
        }
        return Math.min(cursor + 1, text.length());
    }

    private int findAnsiEnd(String text, int start) {
        for (int index = start; index < text.length(); index++) {
            char item = text.charAt(index);
            if (item >= '@' && item <= '~') {
                return index;
            }
        }
        return -1;
    }

    private List<Integer> parseParams(String params) {
        List<Integer> values = new ArrayList<>();
        if (params.isEmpty()) {
            return values;
        }
        String[] parts = params.split(";", -1);
        for (String part : parts) {
            String cleaned = part.trim();
            if (cleaned.isEmpty()) {
                values.add(0);
                continue;
            }
            try {
                values.add(Integer.parseInt(cleaned));
            } catch (NumberFormatException ignored) {
                values.add(0);
            }
        }
        return values;
    }

    private int param(List<Integer> values, int index, int fallback) {
        if (index >= values.size()) {
            return fallback;
        }
        int value = values.get(index);
        return value == 0 ? fallback : value;
    }

    private int ansiColor(int code, boolean background) {
        int base = background ? (code >= 100 ? code - 100 : code - 40) : (code >= 90 ? code - 90 : code - 30);
        boolean bright = code >= 90;
        switch (base) {
            case 0:
                return bright ? Color.rgb(80, 88, 100) : Color.rgb(33, 38, 45);
            case 1:
                return bright ? Color.rgb(255, 123, 114) : Color.rgb(248, 81, 73);
            case 2:
                return bright ? Color.rgb(86, 211, 100) : Color.rgb(63, 185, 80);
            case 3:
                return bright ? Color.rgb(234, 179, 8) : Color.rgb(210, 153, 34);
            case 4:
                return bright ? Color.rgb(121, 192, 255) : Color.rgb(88, 166, 255);
            case 5:
                return bright ? Color.rgb(210, 168, 255) : Color.rgb(188, 140, 255);
            case 6:
                return bright ? Color.rgb(86, 211, 219) : Color.rgb(57, 197, 187);
            case 7:
            default:
                return bright ? Color.rgb(240, 246, 252) : Color.rgb(201, 209, 217);
        }
    }

    private int xtermColor(int value) {
        value = clamp(value, 0, 255);
        if (value < 16) {
            if (value < 8) {
                return ansiColor(30 + value, false);
            }
            return ansiColor(90 + value - 8, false);
        }
        if (value >= 232) {
            int shade = 8 + (value - 232) * 10;
            return Color.rgb(shade, shade, shade);
        }
        int index = value - 16;
        int red = xtermComponent(index / 36);
        int green = xtermComponent((index / 6) % 6);
        int blue = xtermComponent(index % 6);
        return Color.rgb(red, green, blue);
    }

    private int xtermComponent(int value) {
        return value == 0 ? 0 : 55 + value * 40;
    }

    private static int blendColor(int foreground, int background, float foregroundRatio) {
        float backgroundRatio = 1f - foregroundRatio;
        return Color.rgb(
                Math.round(Color.red(foreground) * foregroundRatio + Color.red(background) * backgroundRatio),
                Math.round(Color.green(foreground) * foregroundRatio + Color.green(background) * backgroundRatio),
                Math.round(Color.blue(foreground) * foregroundRatio + Color.blue(background) * backgroundRatio)
        );
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class Cell {
        char value = ' ';
        int fg = DEFAULT_FG;
        int bg = DEFAULT_BG;
        boolean bold;
        boolean dim;
        boolean continuation;

        void clear() {
            value = ' ';
            fg = DEFAULT_FG;
            bg = DEFAULT_BG;
            bold = false;
            dim = false;
            continuation = false;
        }

        void set(char nextValue, int nextFg, int nextBg, boolean nextBold, boolean nextDim) {
            value = nextValue;
            fg = nextFg;
            bg = nextBg;
            bold = nextBold;
            dim = nextDim;
            continuation = false;
        }

        void setContinuation(int nextFg, int nextBg, boolean nextBold, boolean nextDim) {
            value = ' ';
            fg = nextFg;
            bg = nextBg;
            bold = nextBold;
            dim = nextDim;
            continuation = true;
        }

        void copyFrom(Cell other) {
            value = other.value;
            fg = other.fg;
            bg = other.bg;
            bold = other.bold;
            dim = other.dim;
            continuation = other.continuation;
        }
    }
}

package orbis.launcher;

/**
 * The controller's text entry: four rows of characters and a last row of Space, Delete and
 * Search. Pure state, so it is tested on its own (no GL).
 */
final class OnScreenKeyboard {
    static final String[] ROWS = {"1234567890", "qwertyuiop", "asdfghjkl-", "zxcvbnm_. "};
    static final String[] SPECIAL = {"Space", "Delete", "Search"};
    /** The row index of the special keys. */
    static final int SPECIAL_ROW = ROWS.length;

    enum Result { NONE, SEARCH, CLOSE }

    private int row, col;

    int row() { return row; }
    int col() { return col; }

    private static int rowLength(int row) {
        return row == SPECIAL_ROW ? SPECIAL.length : ROWS[row].length();
    }

    /** Applies one action to the query; returns whether the keyboard should search or close. */
    Result apply(Input.Action action, StringBuilder query) {
        switch (action) {
            case UP -> row = (row + SPECIAL_ROW) % (SPECIAL_ROW + 1);
            case DOWN -> row = (row + 1) % (SPECIAL_ROW + 1);
            case LEFT -> col = Math.max(0, col - 1);
            case RIGHT -> col = col + 1;
            case CONFIRM -> {
                if (row < SPECIAL_ROW) query.append(ROWS[row].charAt(col));
                else if (col == 0) query.append(' ');
                else if (col == 1) deleteLast(query);
                else return Result.SEARCH;
            }
            case ALT, DELETE -> deleteLast(query);
            case MENU -> { return Result.SEARCH; }
            case BACK -> { return Result.CLOSE; }
            default -> { }
        }
        col = Math.min(col, rowLength(row) - 1);
        return Result.NONE;
    }

    private static void deleteLast(StringBuilder query) {
        if (query.length() > 0) query.setLength(query.length() - 1);
    }
}

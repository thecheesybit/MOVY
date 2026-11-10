package app.movy.shell;

/**
 * Pure focus state machine of the TV drawer: closed, the item list, or the Sources row. No Android classes: unit tested.
 * LEFT at the page edge opens the list; a second LEFT (or MENU) goes up to the Sources row; DOWN comes back; RIGHT or BACK
 * closes. In the Sources row LEFT/RIGHT pick a source and OK switches to it. There is no double-RIGHT anywhere.
 */
final class DrawerNav {
    enum Zone { CLOSED, LIST, SOURCES }

    enum Key { LEFT, RIGHT, UP, DOWN, OK, BACK, MENU }

    enum Act {
        /** Not ours (drawer closed). */
        NONE,
        /** Consumed; nothing visible changed. */
        STAY,
        /** Focus moved (row, column or zone): redraw. */
        MOVED,
        /** The drawer closes and the page gets its focus back. */
        CLOSE_RESTORE,
        /** OK on a list row ({@link #listIndex()}). */
        ACTIVATE_ITEM,
        /** OK on a source ({@link #sourceIndex()}): the drawer closes at once. */
        ACTIVATE_SOURCE
    }

    private Zone zone = Zone.CLOSED;
    private int listIndex;
    private int sourceIndex;
    private int listCount;
    private int sourceCount;

    Zone zone() { return zone; }

    boolean isOpen() { return zone != Zone.CLOSED; }

    int listIndex() { return listIndex; }

    int sourceIndex() { return sourceIndex; }

    /** The model changed while open (recent titles arrived, the config changed): clamp the focus into the new sizes. */
    void setCounts(int listCount, int sourceCount) {
        this.listCount = Math.max(0, listCount);
        this.sourceCount = Math.max(0, sourceCount);
        listIndex = clamp(listIndex, this.listCount);
        sourceIndex = clamp(sourceIndex, this.sourceCount);
    }

    /** LEFT at the page edge: the list opens with the focus on {@code index} (the current section); a later LEFT starts on {@code source}. */
    void openList(int index, int source) {
        zone = Zone.LIST;
        listIndex = clamp(index, listCount);
        sourceIndex = clamp(source, sourceCount);
    }

    /** MENU on a page: the Sources row opens with the focus on {@code index} (the current source). */
    void openSources(int index) {
        zone = Zone.SOURCES;
        sourceIndex = clamp(index, sourceCount);
    }

    void close() { zone = Zone.CLOSED; }

    /** Applies one key. */
    Act press(Key k) {
        switch (zone) {
            case LIST: return inList(k);
            case SOURCES: return inSources(k);
            default: return Act.NONE;
        }
    }

    private Act inList(Key k) {
        switch (k) {
            case UP:
                if (listIndex > 0) { listIndex--; return Act.MOVED; }
                return toSources();
            case DOWN:
                if (listIndex < listCount - 1) { listIndex++; return Act.MOVED; }
                return Act.STAY;
            case LEFT:
            case MENU:
                return toSources();
            case RIGHT:
            case BACK:
                zone = Zone.CLOSED;
                return Act.CLOSE_RESTORE;
            case OK:
                return listCount > 0 ? Act.ACTIVATE_ITEM : Act.STAY;
            default:
                return Act.STAY;
        }
    }

    private Act toSources() {
        if (sourceCount == 0) return Act.STAY;
        zone = Zone.SOURCES;
        return Act.MOVED;
    }

    private Act inSources(Key k) {
        switch (k) {
            case LEFT:
                if (sourceIndex > 0) { sourceIndex--; return Act.MOVED; }
                return Act.STAY;
            case RIGHT:
                if (sourceIndex < sourceCount - 1) { sourceIndex++; return Act.MOVED; }
                return Act.STAY;
            case DOWN:
                if (listCount == 0) return Act.STAY;
                zone = Zone.LIST;
                return Act.MOVED;
            case OK:
                return Act.ACTIVATE_SOURCE;
            case BACK:
            case MENU:
                zone = Zone.CLOSED;
                return Act.CLOSE_RESTORE;
            default:
                return Act.STAY;
        }
    }

    private static int clamp(int i, int count) {
        return count <= 0 ? 0 : Math.max(0, Math.min(i, count - 1));
    }
}

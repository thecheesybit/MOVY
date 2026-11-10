package app.movy.shell;

/**
 * Pure rules for the search keyboard proxy and the in-page mic: what text is mirrored into the site's search box, when a
 * mirror is needed at all, and how a string becomes a safe JS literal. No Android types, so it is unit-tested.
 */
final class SearchText {
    /** The longest query mirrored into a site (a search box is not a text editor). */
    static final int MAX_LEN = 120;
    private static final char BS = (char) 92;
    private static final char LF = (char) 10;
    private static final char CR = (char) 13;
    private static final char TAB = (char) 9;
    private static final char LS = (char) 0x2028;
    private static final char PS = (char) 0x2029;

    private SearchText() {}

    /** The text as the site should see it: control characters dropped, line breaks and tabs become spaces, capped at {@link #MAX_LEN}. */
    static String clean(CharSequence raw) {
        if (raw == null) return "";
        StringBuilder out = new StringBuilder(Math.min(raw.length(), MAX_LEN));
        for (int i = 0; i < raw.length() && out.length() < MAX_LEN; i++) {
            char c = raw.charAt(i);
            if (c == LF || c == CR || c == TAB || c == LS || c == PS) out.append(' ');
            else if (c >= 0x20 && c != 0x7f) out.append(c);
        }
        return out.toString();
    }

    /** What changed between two texts: the common prefix, the part removed and the part inserted. */
    static final class Edit {
        final int prefix;
        final String removed;
        final String inserted;

        Edit(int prefix, String removed, String inserted) {
            this.prefix = prefix;
            this.removed = removed;
            this.inserted = inserted;
        }

        boolean isNoop() {
            return removed.isEmpty() && inserted.isEmpty();
        }
    }

    /** Shortest edit that turns {@code from} into {@code to}: common prefix, then common suffix (never overlapping the prefix). */
    static Edit diff(String from, String to) {
        String a = from == null ? "" : from;
        String b = to == null ? "" : to;
        int max = Math.min(a.length(), b.length());
        int p = 0;
        while (p < max && a.charAt(p) == b.charAt(p)) p++;
        int s = 0;
        while (s < max - p && a.charAt(a.length() - 1 - s) == b.charAt(b.length() - 1 - s)) s++;
        return new Edit(p, a.substring(p, a.length() - s), b.substring(p, b.length() - s));
    }

    /** Remembers what the site box holds, so a key that changes nothing (a cursor move, a repeated commit) sends nothing. */
    static final class Mirror {
        private String sent;

        /** The cleaned text to send, or null when the site already has it. */
        String next(CharSequence typed) {
            String t = clean(typed);
            if (t.equals(sent)) return null;
            sent = t;
            return t;
        }

        /** The box was set by someone else (opening the keyboard on its current value): start from there. */
        void reset(CharSequence current) {
            sent = clean(current);
        }

        void forget() {
            sent = null;
        }
    }

    /** A JS string literal (double quotes) that cannot end the literal or the script early, whatever the text holds. */
    static String jsString(String s) {
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == BS) b.append(BS).append(c);
            else if (c == LF) b.append(BS).append('n');
            else if (c == CR) b.append(BS).append('r');
            else if (c == TAB) b.append(BS).append('t');
            else if (c < 0x20 || c == '<' || c == '>' || c == '&' || c == LS || c == PS) b.append(BS).append('u').append(String.format("%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }

    /** A call into the page's tvsearchmic.js: {@code window.__movyTv.<fn>(<args>)}, guarded so a page without the module ignores it. */
    static String call(String fn, String... stringArgs) {
        StringBuilder b = new StringBuilder("window.__movyTv&&window.__movyTv.").append(fn).append("&&window.__movyTv.").append(fn).append('(');
        for (int i = 0; i < stringArgs.length; i++) b.append(i == 0 ? "" : ",").append(jsString(clean(stringArgs[i])));
        return b.append(')').toString();
    }

    /** The same, without arguments. */
    static String callPlain(String fn, boolean... flags) {
        StringBuilder b = new StringBuilder("window.__movyTv&&window.__movyTv.").append(fn).append("&&window.__movyTv.").append(fn).append('(');
        for (int i = 0; i < flags.length; i++) b.append(i == 0 ? "" : ",").append(flags[i]);
        return b.append(')').toString();
    }
}

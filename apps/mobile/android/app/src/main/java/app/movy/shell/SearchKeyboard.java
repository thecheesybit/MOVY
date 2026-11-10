package app.movy.shell;

import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;

/**
 * The native side of "OK on a site search box opens the TV keyboard": a hidden one-pixel {@link EditText} takes the focus and the
 * input method, and reports each text change; the controller mirrors it into the site box. The site's own input is never
 * focused for typing, so the D-pad is never trapped inside it. The view is attached once, invisible, and not focusable while closed.
 */
final class SearchKeyboard {
    interface Listener {
        void onText(String text);

        /** Done / Search / Enter on the keyboard. */
        void onSubmit();

        /** BACK, a lost focus or a hidden keyboard: the proxy has already closed itself. */
        void onClosed();
    }

    private static final long IME_POLL_MS = 350;
    private static final long IME_SHOW_GRACE_MS = 1500;

    private final Context context;
    private final Listener listener;
    private final Proxy edit;
    private boolean open;
    private boolean silent;
    private long openedAt;
    private boolean sawIme;
    private final Runnable poll = this::pollIme;

    SearchKeyboard(Context context, ViewGroup parent, Listener listener) {
        this.context = context;
        this.listener = listener;
        edit = new Proxy(context);
        edit.setBackground(null);
        edit.setTextColor(0x00000000);
        edit.setCursorVisible(false);
        edit.setAlpha(0.01f);
        edit.setSingleLine(true);
        edit.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        edit.setFocusable(false);
        edit.setFocusableInTouchMode(false);
        edit.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        edit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                if (open && !silent) listener.onText(s.toString());
            }
        });
        edit.setOnEditorActionListener((v, action, ev) -> {
            boolean enter = ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER;
            if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE || action == EditorInfo.IME_ACTION_GO
                    || action == EditorInfo.IME_ACTION_SEND || enter) {
                if (ev == null || ev.getAction() == KeyEvent.ACTION_DOWN) listener.onSubmit();
                return true;
            }
            return false;
        });
        edit.setOnFocusChangeListener((v, has) -> { if (!has && open) close(); });
        edit.back = this::close;
        parent.addView(edit, new FrameLayout.LayoutParams(1, 1));
    }

    boolean isOpen() {
        return open;
    }

    /** Takes the focus, shows the keyboard with {@code initial} in it (the box's current text). */
    void open(String initial) {
        if (open) return;
        open = true;
        sawIme = false;
        openedAt = System.currentTimeMillis();
        silent = true;
        edit.setFocusable(true);
        edit.setFocusableInTouchMode(true);
        edit.setText(initial == null ? "" : initial);
        edit.setSelection(edit.length());
        silent = false;
        edit.requestFocus();
        edit.postDelayed(this::showIme, 60);
        edit.postDelayed(this::showIme, 500);
        edit.removeCallbacks(poll);
        edit.postDelayed(poll, IME_POLL_MS);
    }

    private void showIme() {
        if (!open) return;
        InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(edit, 0);
    }

    /** Hides the keyboard and gives the focus back; reports once. */
    void close() {
        if (!open) return;
        open = false;
        edit.removeCallbacks(poll);
        hideIme();
        edit.clearFocus();
        edit.setFocusable(false);
        edit.setFocusableInTouchMode(false);
        listener.onClosed();
        // The page's box takes the focus next, and an input method stays up when focus moves from one editor to another: hide again.
        edit.postDelayed(this::hideImeIfClosed, 150);
        edit.postDelayed(this::hideImeIfClosed, 600);
    }

    private void hideImeIfClosed() {
        if (!open) hideIme();
    }

    private void hideIme() {
        InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(edit.getWindowToken(), 0);
    }

    /** The user hid the keyboard with its own key: the proxy follows. */
    private void pollIme() {
        if (!open) return;
        boolean visible = imeVisible();
        if (visible) sawIme = true;
        if (!visible && sawIme && System.currentTimeMillis() - openedAt > IME_SHOW_GRACE_MS) { close(); return; }
        edit.postDelayed(poll, IME_POLL_MS);
    }

    private boolean imeVisible() {
        androidx.core.view.WindowInsetsCompat in = androidx.core.view.ViewCompat.getRootWindowInsets(edit);
        return in != null && in.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime());
    }

    /** The edit view's own BACK (before the input method sees it) closes the proxy. */
    private static final class Proxy extends EditText {
        Runnable back;

        Proxy(Context c) {
            super(c);
        }

        @Override public boolean onKeyPreIme(int keyCode, KeyEvent event) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.getAction() == KeyEvent.ACTION_UP && back != null) back.run();
                return true;
            }
            return super.onKeyPreIme(keyCode, event);
        }
    }
}

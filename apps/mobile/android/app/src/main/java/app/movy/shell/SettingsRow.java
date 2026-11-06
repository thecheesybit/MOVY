package app.movy.shell;

import android.graphics.drawable.Drawable;

import java.util.function.Consumer;

/** One entry of the Settings list model. Immutable; sections are just header rows followed by their rows. */
final class SettingsRow {
    enum Type { HEADER, RADIO, TOGGLE, VALUE, STATUS, ACTION, ABOUT }

    final Type type;
    final String title;
    /** Value text, status text or about tagline. */
    final String value;
    final boolean checked;
    /** Accent / dot colour. */
    final int color;
    final boolean danger;
    final Runnable onClick;
    final Consumer<Boolean> onChange;
    final Drawable icon;
    /** Extra about lines. */
    final String[] lines;

    private SettingsRow(Type type, String title, String value, boolean checked, int color, boolean danger,
                        Runnable onClick, Consumer<Boolean> onChange, Drawable icon, String[] lines) {
        this.type = type;
        this.title = title;
        this.value = value;
        this.checked = checked;
        this.color = color;
        this.danger = danger;
        this.onClick = onClick;
        this.onChange = onChange;
        this.icon = icon;
        this.lines = lines;
    }

    static SettingsRow header(String title) {
        return new SettingsRow(Type.HEADER, title, "", false, 0, false, null, null, null, null);
    }

    static SettingsRow radio(String title, int accent, boolean checked, Runnable onClick) {
        return new SettingsRow(Type.RADIO, title, "", checked, accent, false, onClick, null, null, null);
    }

    static SettingsRow toggle(String title, boolean checked, Consumer<Boolean> onChange) {
        return new SettingsRow(Type.TOGGLE, title, "", checked, 0, false, null, onChange, null, null);
    }

    static SettingsRow value(String title, String value) {
        return new SettingsRow(Type.VALUE, title, value, false, 0, false, null, null, null, null);
    }

    static SettingsRow status(String title, String value, int dotColor) {
        return new SettingsRow(Type.STATUS, title, value, false, dotColor, false, null, null, null, null);
    }

    static SettingsRow action(String title, boolean danger, Runnable onClick) {
        return new SettingsRow(Type.ACTION, title, "", false, 0, danger, onClick, null, null, null);
    }

    static SettingsRow about(Drawable icon, String name, String tagline, String... lines) {
        return new SettingsRow(Type.ABOUT, name, tagline, false, 0, false, null, null, icon, lines);
    }
}

package app.movy.shell;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.format.Formatter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import app.movy.adblock.BlockStats;

/** Builds the Settings list: Source, Ad blocking, Sources status, Storage, About. Add a section = add a method. */
final class SettingsModel {
    /** Things the rows can ask the sheet to do. */
    interface Actions {
        void rebuild();

        void checkSources();

        void confirmClearCache();

        void confirmSignOut();

        void setAdblock(boolean on);
    }

    /** Live inputs; read each time the list is rebuilt. */
    static final class Inputs {
        final SourceRepository repo;
        final Prefs prefs;
        final BlockStats stats;
        final HealthChecker health;
        final boolean adblockConfigured;
        final boolean adblockOn;
        final String appName;
        final boolean checking;
        /** Null until measured. */
        final Long storageBytes;
        final Drawable icon;

        Inputs(SourceRepository repo, Prefs prefs, BlockStats stats, HealthChecker health, boolean adblockConfigured,
               boolean adblockOn, String appName, boolean checking, Long storageBytes, Drawable icon) {
            this.repo = repo;
            this.prefs = prefs;
            this.stats = stats;
            this.health = health;
            this.adblockConfigured = adblockConfigured;
            this.adblockOn = adblockOn;
            this.appName = appName;
            this.checking = checking;
            this.storageBytes = storageBytes;
            this.icon = icon;
        }
    }

    private SettingsModel() {}

    static List<SettingsRow> build(Context ctx, Inputs in, Actions act) {
        List<SettingsRow> rows = new ArrayList<>();
        LiveConfig cfg = in.repo.config();
        sourceSection(ctx, in, act, rows, cfg);
        if (in.adblockConfigured) adblockSection(ctx, in, act, rows);
        statusSection(ctx, in, act, rows, cfg);
        storageSection(ctx, in, act, rows);
        aboutSection(ctx, in, rows, cfg);
        return Collections.unmodifiableList(rows);
    }

    private static void sourceSection(Context ctx, Inputs in, Actions act, List<SettingsRow> rows, LiveConfig cfg) {
        rows.add(SettingsRow.header(ctx.getString(R.string.movy_sec_source)));
        rows.add(SettingsRow.value(ctx.getString(R.string.movy_default_source), ""));
        String def = in.prefs.defaultSourceId();
        List<Source> enabled = cfg.enabledSources();
        if (def == null || cfg.find(def) == null) def = in.repo.preselected().id;
        for (Source s : enabled) {
            final String id = s.id;
            rows.add(SettingsRow.radio(s.name, s.accent, id.equals(def), () -> {
                in.prefs.setDefaultSourceId(id);
                act.rebuild();
            }));
        }
        rows.add(SettingsRow.toggle(ctx.getString(R.string.movy_ask_every_launch), in.prefs.askEveryLaunch(),
                on -> in.prefs.setAskEveryLaunch(on)));
    }

    private static void adblockSection(Context ctx, Inputs in, Actions act, List<SettingsRow> rows) {
        rows.add(SettingsRow.header(ctx.getString(R.string.movy_sec_adblock)));
        rows.add(SettingsRow.toggle(ctx.getString(R.string.movy_adblock_toggle), in.adblockOn, act::setAdblock));
        rows.add(SettingsRow.value(ctx.getString(R.string.movy_ads_blocked), String.valueOf(in.stats.adsBlocked())));
        rows.add(SettingsRow.value(ctx.getString(R.string.movy_redirects_killed), String.valueOf(in.stats.redirectsKilled())));
    }

    private static void statusSection(Context ctx, Inputs in, Actions act, List<SettingsRow> rows, LiveConfig cfg) {
        rows.add(SettingsRow.header(ctx.getString(R.string.movy_sec_status)));
        rows.add(SettingsRow.action(ctx.getString(R.string.movy_check_now), false, act::checkSources));
        for (Source s : cfg.enabledSources()) {
            HealthChecker.Status st = in.health.status(s.id);
            String text = in.checking ? ctx.getString(R.string.movy_status_checking) : statusText(ctx, st);
            rows.add(SettingsRow.status(s.name, text, IslandPainter.healthColor(st)));
        }
    }

    static String statusText(Context ctx, HealthChecker.Status st) {
        switch (st) {
            case UP: return ctx.getString(R.string.movy_status_up);
            case SLOW: return ctx.getString(R.string.movy_status_slow);
            case DOWN: return ctx.getString(R.string.movy_status_down);
            default: return ctx.getString(R.string.movy_status_unknown);
        }
    }

    private static void storageSection(Context ctx, Inputs in, Actions act, List<SettingsRow> rows) {
        rows.add(SettingsRow.header(ctx.getString(R.string.movy_sec_storage)));
        String used = in.storageBytes == null ? "…" : Formatter.formatShortFileSize(ctx, in.storageBytes);
        rows.add(SettingsRow.value(ctx.getString(R.string.movy_storage_used), used));
        rows.add(SettingsRow.action(ctx.getString(R.string.movy_clear_cache), false, act::confirmClearCache));
        rows.add(SettingsRow.action(ctx.getString(R.string.movy_sign_out), true, act::confirmSignOut));
    }

    private static void aboutSection(Context ctx, Inputs in, List<SettingsRow> rows, LiveConfig cfg) {
        rows.add(SettingsRow.header(ctx.getString(R.string.movy_sec_about)));
        String ver = ctx.getString(R.string.movy_version, BuildConfig.VERSION_NAME, BuildConfig.MOVY_BUILD);
        if (BuildConfig.MOVY_REMOTE && cfg.contentVersion > 0) ver += ctx.getString(R.string.movy_cfg_suffix, cfg.contentVersion);
        rows.add(SettingsRow.about(in.icon, in.appName, cfg.tagline, ctx.getString(R.string.movy_by_credit, cfg.credit), ver));
    }
}

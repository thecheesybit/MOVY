package app.movy.shell;

import org.json.JSONException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Disk storage of the shell snapshots: {@code root/<source>/<route>/<tag>.html} + {@code <tag>.json}. Writes are atomic
 * (temp file, then rename; the meta is written last, so a version exists only once complete). Each route keeps a ring of the
 * newest {@code keep} versions; older ones are deleted. Only metadata is held in memory (dropped by {@link #releaseMemory}).
 */
final class ShellStore {
    private final File root;
    private final Map<String, List<ShellSnapshot>> memo = new HashMap<>();

    ShellStore(File root) {
        this.root = root;
        //noinspection ResultOfMethodCallIgnored
        root.mkdirs();
    }

    private File routeDir(String source, String routeKey) {
        return new File(new File(root, safe(source)), safe(routeKey));
    }

    private static String safe(String s) {
        return s.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static String memoKey(String source, String routeKey) {
        return source + "/" + routeKey;
    }

    /** Versions of a route, newest first (bad ones included). */
    synchronized List<ShellSnapshot> list(String source, String routeKey) {
        String mk = memoKey(source, routeKey);
        List<ShellSnapshot> cached = memo.get(mk);
        if (cached != null) return cached;
        List<ShellSnapshot> out = new ArrayList<>();
        File[] files = routeDir(source, routeKey).listFiles();
        if (files != null) {
            for (File f : files) {
                String n = f.getName();
                if (!n.endsWith(".json")) continue;
                File body = new File(f.getParentFile(), n.substring(0, n.length() - 5) + ".html");
                if (!body.isFile()) continue;
                try {
                    out.add(ShellSnapshot.fromJson(source, routeKey, readText(f)));
                } catch (JSONException | IOException ignored) {
                    // a corrupt meta: the version does not exist (cleaned up on the next push)
                }
            }
        }
        Collections.sort(out, (a, b) -> Integer.compare(b.tag, a.tag));
        List<ShellSnapshot> frozen = Collections.unmodifiableList(out);
        memo.put(mk, frozen);
        return frozen;
    }

    synchronized ShellSnapshot newestGood(String source, String routeKey) {
        for (ShellSnapshot s : list(source, routeKey)) if (!s.bad) return s;
        return null;
    }

    synchronized ShellSnapshot newest(String source, String routeKey) {
        List<ShellSnapshot> l = list(source, routeKey);
        return l.isEmpty() ? null : l.get(0);
    }

    byte[] readBody(ShellSnapshot s) throws IOException {
        File f = new File(routeDir(s.source, s.routeKey), s.tag + ".html");
        try (FileInputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream((int) Math.min(f.length(), ShellPolicy.MAX_BYTES));
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    /** Stores a new version at the front of the ring and deletes the versions that fell out of it. Returns them. */
    synchronized List<ShellSnapshot> push(ShellSnapshot s, byte[] body, int keep) throws IOException {
        File dir = routeDir(s.source, s.routeKey);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File html = new File(dir, s.tag + ".html");
        File htmlTmp = new File(dir, s.tag + ".html.tmp");
        writeBytes(htmlTmp, body);
        if (html.exists() && !html.delete()) throw new IOException("cannot replace body");
        if (!htmlTmp.renameTo(html)) throw new IOException("rename failed");
        writeMeta(s);
        List<ShellSnapshot> before = new ArrayList<>(list(s.source, s.routeKey));
        List<Integer> tags = new ArrayList<>();
        for (ShellSnapshot b : before) tags.add(b.tag);
        List<Integer> kept = ShellPolicy.ringAfterPush(tags, s.tag, keep);
        List<ShellSnapshot> evicted = new ArrayList<>();
        List<ShellSnapshot> now = new ArrayList<>();
        now.add(s);
        for (ShellSnapshot b : before) {
            if (b.tag == s.tag) continue;
            if (kept.contains(b.tag)) now.add(b); else evicted.add(b);
        }
        for (ShellSnapshot e : evicted) deleteVersion(e);
        Collections.sort(now, (a, b) -> Integer.compare(b.tag, a.tag));
        memo.put(memoKey(s.source, s.routeKey), Collections.unmodifiableList(now));
        return evicted;
    }

    /** Replaces the meta of an existing version (verified again, marked bad...). */
    synchronized void update(ShellSnapshot s) throws IOException {
        writeMeta(s);
        List<ShellSnapshot> now = new ArrayList<>();
        for (ShellSnapshot b : list(s.source, s.routeKey)) now.add(b.tag == s.tag ? s : b);
        memo.put(memoKey(s.source, s.routeKey), Collections.unmodifiableList(now));
    }

    private void writeMeta(ShellSnapshot s) throws IOException {
        File dir = routeDir(s.source, s.routeKey);
        File tmp = new File(dir, s.tag + ".json.tmp");
        try {
            writeBytes(tmp, s.toJson().getBytes(StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException(e);
        }
        File meta = new File(dir, s.tag + ".json");
        if (meta.exists() && !meta.delete()) throw new IOException("cannot replace meta");
        if (!tmp.renameTo(meta)) throw new IOException("meta rename failed");
    }

    private void deleteVersion(ShellSnapshot s) {
        File dir = routeDir(s.source, s.routeKey);
        //noinspection ResultOfMethodCallIgnored
        new File(dir, s.tag + ".html").delete();
        //noinspection ResultOfMethodCallIgnored
        new File(dir, s.tag + ".json").delete();
    }

    /** Assets referenced by any retained, good version: exempt from the asset cache's eviction so an old version can boot. */
    synchronized Set<String> pinnedUrls() {
        Set<String> out = new LinkedHashSet<>();
        File[] sources = root.listFiles();
        if (sources == null) return out;
        for (File s : sources) {
            File[] routes = s.listFiles();
            if (routes == null) continue;
            for (File r : routes) {
                for (ShellSnapshot v : list(s.getName(), r.getName())) if (!v.bad) out.addAll(v.assets);
            }
        }
        return out;
    }

    synchronized void releaseMemory() {
        memo.clear();
    }

    synchronized void clear() {
        memo.clear();
        deleteTree(root);
        //noinspection ResultOfMethodCallIgnored
        root.mkdirs();
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        if (!f.getPath().isEmpty()) f.delete();
    }

    private static String readText(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    private static void writeBytes(File f, byte[] b) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(b);
        }
    }
}

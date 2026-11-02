package app.revanced.extension.riffixes;

import android.net.Uri;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Reddit blocked its bulk endpoints for rif in Oct 2026 (403 "Blocked" on /api/info, /by_id and
 * /api/morechildren, any method or suffix). /comments/... still works, so both features are rebuilt on it:
 *
 * - Galleries (and rif's other /api/info lookups): url() sends /api/info?id=t3_X to /comments/X.json?limit=1
 *   and body() hands rif the first element of the reply, the same Listing /api/info returned.
 * - "Load more comments": moreBegin()/moreUri() turn the morechildren request into a fetch of the parent's
 *   subtree, and body() picks out the wanted comments in the flat {"json":{"data":{"things":[...]}}} shape.
 *
 * Requests we rewrite carry riffixes=<kind> so forceGet() can make them GET and body() can find them.
 */
@SuppressWarnings("unused")
public final class Reddit {
    private static final String TAG = "riffixes";
    private static final String MARK = "riffixes=";
    private static final Pattern INFO =
            Pattern.compile("^https://(oauth|www)\\.reddit\\.com/api/info(?:\\.json)?/?\\?(.*)$");
    private static final Pattern INFO_ID = Pattern.compile("(?:^|&)id=t3_([a-z0-9]+)(?:&|$)");
    private static final ThreadLocal<String> RESPONSE_URL = new ThreadLocal<>();
    private static final ThreadLocal<String[]> MORE = new ThreadLocal<>();
    private static final Map<String, String> WANTED = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT_KEY = new AtomicInteger();

    private Reddit() {
    }

    /** Hooked into OkHttp's Request.Builder.url(String): every rif request URL. */
    public static String url(String url) {
        if (url == null) {
            return null;
        }
        Matcher m = INFO.matcher(url);
        if (!m.matches()) {
            return url;
        }
        String query = m.group(2);
        Matcher id = INFO_ID.matcher(query);
        if (!id.find()) {
            return url;  // several ids or a url= lookup: nothing we can map, leave it
        }
        String raw = query.contains("raw_json=1") ? "raw_json=1&" : "";
        return "https://" + m.group(1) + ".reddit.com/comments/" + id.group(1) + ".json?" + raw
                + "limit=1&" + MARK + "info";
    }

    /**
     * Hooked into Request.Builder.build() with its HttpUrl: our rewritten requests are listing fetches, so they go
     * out as GET without rif's form body.
     */
    public static boolean forceGet(Object httpUrl) {
        return ours(String.valueOf(httpUrl));
    }

    private static boolean ours(String url) {
        return url != null && url.contains(MARK);
    }

    /** Hooked into the "load more comments" task's constructor, right before {@link #moreUri}. */
    public static void moreBegin(String link, String parent, String[] children) {
        MORE.set(new String[]{link, parent, join(children)});
    }

    /** Replaces the morechildren URI the "load more comments" task posts to. */
    public static Uri moreUri(String sort) {
        String[] more = MORE.get();
        MORE.remove();
        String link = more == null ? null : more[0];
        String parent = more == null ? null : more[1];
        String linkId = link != null && link.startsWith("t3_") ? link.substring(3) : link;
        StringBuilder sb = new StringBuilder("https://www.reddit.com/comments/").append(linkId);
        boolean topLevel = parent == null || !parent.startsWith("t1_");
        if (!topLevel) {
            sb.append("/_/").append(parent.substring(3));
        }
        // Reddit caps a fetch at 500 comments. At the top level, spend them on new top-level comments: deeper
        // replies come back as their own "load more" (the nested case above, which loads fully).
        sb.append(".json?limit=500&depth=").append(topLevel ? 2 : 10);
        if (sort != null) {
            sb.append("&sort=").append(sort);
        }
        // The id list can run to hundreds of ids (Reddit answers 414), so the URL only carries a key to it.
        if (WANTED.size() > 50) {
            WANTED.clear();
        }
        String key = Integer.toString(NEXT_KEY.incrementAndGet());
        WANTED.put(key, more == null ? "" : more[2]);
        sb.append('&').append(MARK).append("more&parent=").append(parent).append("&want=").append(key);
        return Uri.parse(sb.toString());
    }

    /** Hooked into rif's request task with every response, on the thread that reads its body next. */
    public static void response(Object response) {
        String s = String.valueOf(response);  // Response{protocol=.., code=.., message=.., url=...}
        int i = s.indexOf("url=");
        RESPONSE_URL.set(i < 0 ? null : s.substring(i + 4, s.length() - 1));
    }

    /** Hooked into rif's request task with the body stream of a successful response. */
    public static InputStream body(InputStream in) throws IOException {
        String url = RESPONSE_URL.get();
        RESPONSE_URL.remove();
        if (in == null || !ours(url)) {
            return in;
        }
        String text = read(in);
        String out = text;
        try {
            if (url.contains(MARK + "info")) {
                out = new JSONArray(text).getJSONObject(0).toString();
            } else if (url.contains(MARK + "more")) {
                String want = WANTED.get(param(url, "want"));
                out = more(text, param(url, "parent"), want == null ? "" : want);
            }
        } catch (JSONException e) {
            Log.w(TAG, "could not convert " + url, e);
        }
        return new ByteArrayInputStream(out.getBytes("UTF-8"));
    }

    private static String more(String text, String parent, String want) throws JSONException {
        Set<String> wanted = new HashSet<>(Arrays.asList(want.split(",")));
        Set<String> shown = new HashSet<>();
        shown.add(parent);
        JSONArray things = new JSONArray();
        JSONArray top = new JSONArray(text).getJSONObject(1).getJSONObject("data").getJSONArray("children");
        int comments = walk(top, parent, wanted, shown, things);
        if (comments == 0 && hasMoreUnder(top, parent)) {
            // Nothing new within Reddit's 500-comment window (bottom of a huge thread). Say so instead of
            // handing rif the same "load more" again.
            return "{\"json\":{\"errors\":[[\"RIF_FIXES\",\"Reddit no longer lets rif load these comments\","
                    + "\"\"]]}}";
        }
        return new JSONObject().put("json", new JSONObject()
                .put("errors", new JSONArray())
                .put("data", new JSONObject().put("things", things))).toString();
    }

    /** Pre-order walk, like morechildren: a parent always comes before its replies. Returns comments added. */
    private static int walk(JSONArray children, String parent, Set<String> wanted, Set<String> shown,
                            JSONArray things) throws JSONException {
        int added = 0;
        for (int i = 0; i < children.length(); i++) {
            JSONObject child = children.optJSONObject(i);
            if (child == null) {
                continue;
            }
            JSONObject data = child.optJSONObject("data");
            if (data == null) {
                continue;
            }
            String parentId = data.optString("parent_id");
            if ("t1".equals(child.optString("kind"))) {
                JSONObject replies = data.optJSONObject("replies");
                // Like morechildren: the asked-for comments plus everything under them.
                boolean underAdded = shown.contains(parentId) && !parentId.equals(parent);
                if (underAdded || (wanted.contains(data.optString("id")) && shown.contains(parentId))) {
                    data.put("replies", "");
                    things.put(child);
                    shown.add(data.optString("name"));
                    added++;
                }
                if (replies != null && replies.optJSONObject("data") != null) {
                    JSONArray sub = replies.optJSONObject("data").optJSONArray("children");
                    if (sub != null) {
                        added += walk(sub, parent, wanted, shown, things);
                    }
                }
            } else if ("more".equals(child.optString("kind")) && shown.contains(parentId)) {
                // Deeper "load more" under a comment we added: keep it. One directly under the parent is
                // only useful if it still holds comments that were asked for.
                if (!parentId.equals(parent) || overlaps(data.optJSONArray("children"), wanted)) {
                    things.put(child);
                }
            }
        }
        return added;
    }

    /**
     * Whether Reddit held back comments directly under the parent. If not, the asked-for comments are gone (removed
     * or deleted): empty things make rif drop the row and show its own "no more comments" toast. "more" objects
     * elsewhere in the reply don't count: a top-level fetch has plenty under other comments.
     */
    private static boolean hasMoreUnder(JSONArray children, String parent) {
        for (int i = 0; i < children.length(); i++) {
            JSONObject child = children.optJSONObject(i);
            JSONObject data = child == null ? null : child.optJSONObject("data");
            if (data == null) {
                continue;
            }
            if ("more".equals(child.optString("kind"))) {
                JSONArray ids = data.optJSONArray("children");
                if (parent.equals(data.optString("parent_id")) && ids != null && ids.length() > 0) {
                    return true;
                }
                continue;
            }
            JSONObject replies = data.optJSONObject("replies");
            JSONArray sub = replies == null || replies.optJSONObject("data") == null
                    ? null : replies.optJSONObject("data").optJSONArray("children");
            if (sub != null && hasMoreUnder(sub, parent)) {
                return true;
            }
        }
        return false;
    }

    private static boolean overlaps(JSONArray ids, Set<String> wanted) {
        if (ids == null) {
            return false;
        }
        for (int i = 0; i < ids.length(); i++) {
            if (wanted.contains(ids.optString(i))) {
                return true;
            }
        }
        return false;
    }

    private static String param(String url, String name) {
        Matcher m = Pattern.compile("[?&]" + name + "=([^&]*)").matcher(url);
        return m.find() ? m.group(1) : "";
    }

    private static String join(String[] parts) {
        StringBuilder sb = new StringBuilder();
        if (parts != null) {
            for (String p : parts) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(p);
            }
        }
        return sb.toString();
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) >= 0; ) {
            out.write(buf, 0, n);
        }
        in.close();
        return out.toString("UTF-8");
    }
}

package tricatch.oehostsapp.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import tricatch.oehostsapp.OeHostsApp;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What changed in a release, for the dialog shown on the first start after an update: the first
 * section of the release's notes on GitHub (the "바뀐 점" list), without the install and upgrade
 * sections that follow it. The notes are Markdown; only what the release notes use is understood
 * (bullets, nesting by indentation, {@code **bold**}, {@code `code`}, links shown as their text).
 */
public final class ReleaseNotes {

    private static final Pattern GITHUB_REPO = Pattern.compile("https://github\\.com/([^/]+)/([^/]+?)/?");
    private static final Pattern BULLET = Pattern.compile("^(\\s*)[-*+]\\s+(.*)$");
    private static final Pattern INLINE = Pattern.compile("\\*\\*(.+?)\\*\\*|`([^`]+)`|\\[([^\\]]+)]\\([^)]*\\)");
    /** Keeps a broken or hostile release body from filling the dialog. */
    static final int MAX_LINES = 200;

    private ReleaseNotes() {}

    /** A run of text in one style. */
    public record Span(String text, boolean bold, boolean code) {}

    /**
     * One line of the list: {@code level} 0 for a top bullet, 1 for one under it, ...; {@code bullet}
     * false for plain text; {@code detail} for the description split off a "<b>title</b>: description"
     * bullet, shown under its title at the bullet's text position.
     */
    public record Line(int level, boolean bullet, boolean detail, List<Span> spans) {}

    /**
     * Where the release of {@code tag} is described as JSON: GitHub's REST API for a GitHub
     * repository, {@code <repo>/api/releases/tags/<tag>} for a test server
     * ({@code -Doehostsapp.update.repo}).
     */
    static String apiUrl(String repoUrl, String tag) {
        Matcher m = GITHUB_REPO.matcher(repoUrl);
        if (m.matches()) return "https://api.github.com/repos/" + m.group(1) + "/" + m.group(2) + "/releases/tags/" + tag;
        return repoUrl + "/api/releases/tags/" + tag;
    }

    /** The changes of release {@code version}, fetched from the app's repository. */
    public static List<Line> fetch(String version) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(apiUrl(Update.repo, version))).timeout(Duration.ofSeconds(20))
                .header("User-Agent", "oeHostsApp/" + OeHostsApp.version())
                .header("Accept", "application/vnd.github+json").GET().build();
        HttpResponse<String> r;
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build()) {
            r = http.send(req, HttpResponse.BodyHandlers.ofString());
        }
        if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode());
        JsonNode body = new ObjectMapper().readTree(r.body()).path("body");
        if (!body.isTextual()) throw new IOException("no release notes");
        List<Line> lines = changes(body.asText());
        if (lines.isEmpty()) throw new IOException("empty release notes");
        return lines;
    }

    /**
     * The first section of {@code markdown}: the lines after its first {@code ##} heading up to the
     * next heading (all of it when there is no heading), parsed into list lines. Blank lines,
     * fenced code and headings are left out.
     */
    static List<Line> changes(String markdown) {
        String[] src = markdown.replace("\r", "").split("\n");
        int from = 0;
        for (int i = 0; i < src.length; i++) {
            if (src[i].startsWith("#")) {
                from = i + 1;
                break;
            }
        }
        List<Line> out = new ArrayList<>();
        boolean fence = false;
        for (int i = from; i < src.length && out.size() < MAX_LINES; i++) {
            String s = src[i];
            if (s.strip().startsWith("```")) {
                fence = !fence;
                continue;
            }
            if (fence) continue;
            if (s.startsWith("#")) break;
            if (s.isBlank()) continue;
            Matcher b = BULLET.matcher(s);
            if (b.matches()) {
                int indent = b.group(1).replace("\t", "  ").length();
                addBullet(out, indent / 2, spans(b.group(2)));
            } else {
                out.add(new Line(0, false, false, spans(s.strip())));
            }
        }
        return out;
    }

    /**
     * A bullet; one that starts with a bold title followed by a colon ({@code **title**: text}, the
     * release notes' style) becomes the title line and a detail line under it.
     */
    private static void addBullet(List<Line> out, int level, List<Span> spans) {
        if (spans.size() >= 2 && spans.get(0).bold() && !spans.get(1).bold() && !spans.get(1).code()
                && spans.get(1).text().startsWith(":")) {
            List<Span> rest = new ArrayList<>(spans.subList(1, spans.size()));
            String after = rest.get(0).text().substring(1).stripLeading();
            if (after.isEmpty()) rest.remove(0);
            else rest.set(0, new Span(after, false, false));
            out.add(new Line(level, true, false, List.of(spans.get(0))));
            if (!rest.isEmpty()) out.add(new Line(level, false, true, rest));
            return;
        }
        out.add(new Line(level, true, false, spans));
    }

    /** {@code **bold**}, {@code `code`} and {@code [text](url)} (its text) in one line. */
    static List<Span> spans(String s) {
        List<Span> out = new ArrayList<>();
        Matcher m = INLINE.matcher(s);
        int at = 0;
        while (m.find()) {
            if (m.start() > at) out.add(new Span(s.substring(at, m.start()), false, false));
            if (m.group(1) != null) out.add(new Span(m.group(1), true, false));
            else if (m.group(2) != null) out.add(new Span(m.group(2), false, true));
            else out.add(new Span(m.group(3), false, false));
            at = m.end();
        }
        if (at < s.length()) out.add(new Span(s.substring(at), false, false));
        return out;
    }
}

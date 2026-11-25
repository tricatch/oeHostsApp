package tricatch.oehostsapp.update;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReleaseNotesTest {

    private static final String NOTES = """
            ## 바뀐 점

            - **가져오기**: 설정 → 일반에서 `.json` 파일을 가져옵니다.
              - 예: `java -jar app.jar help`
            - [문서](https://example.test/doc)를 참고하세요.

            ## 업데이트할 때

            - 그대로 쓸 수 있습니다.

            ## 설치

            ```sh
            curl -fsSL https://example.test/install.sh | sh
            ```
            """;

    @Test
    void onlyTheFirstSectionIsKept() {
        List<ReleaseNotes.Line> lines = ReleaseNotes.changes(NOTES);
        assertEquals(4, lines.size());
        assertEquals(0, lines.get(0).level());
        assertEquals(1, lines.get(2).level());
        assertTrue(lines.get(2).bullet());
        assertEquals("문서를 참고하세요.", text(lines.get(3)));
    }

    @Test
    void aBoldTitleWithColonGetsItsDescriptionOnTheNextLine() {
        List<ReleaseNotes.Line> lines = ReleaseNotes.changes(NOTES);
        assertEquals(List.of(new ReleaseNotes.Span("가져오기", true, false)), lines.get(0).spans());
        assertTrue(lines.get(0).bullet());
        assertFalse(lines.get(1).bullet());
        assertTrue(lines.get(1).detail());
        assertEquals(0, lines.get(1).level());
        assertEquals("설정 → 일반에서 .json 파일을 가져옵니다.", text(lines.get(1)));
        // Bold that is not followed by a colon stays on its line.
        List<ReleaseNotes.Line> plain = ReleaseNotes.changes("- **굵게** 쓴 문장\n");
        assertEquals(1, plain.size());
        assertFalse(plain.get(0).detail());
    }

    @Test
    void boldCodeAndLinksBecomeSpans() {
        List<ReleaseNotes.Span> s = ReleaseNotes.spans("**가져오기**: `.json` 파일, [문서](https://x.test)");
        assertEquals(new ReleaseNotes.Span("가져오기", true, false), s.get(0));
        assertEquals(new ReleaseNotes.Span(": ", false, false), s.get(1));
        assertEquals(new ReleaseNotes.Span(".json", false, true), s.get(2));
        assertEquals(new ReleaseNotes.Span("문서", false, false), s.get(4));
    }

    @Test
    void notesWithoutHeadingAreKeptWholeAndCodeFencesDropped() {
        List<ReleaseNotes.Line> lines = ReleaseNotes.changes("첫 줄\r\n```\ncode\n```\n- 항목\n");
        assertEquals(2, lines.size());
        assertFalse(lines.get(0).bullet());
        assertEquals("항목", text(lines.get(1)));
    }

    @Test
    void apiUrlFollowsTheRepository() {
        assertEquals("https://api.github.com/repos/tricatch/oeHostsApp/releases/tags/0.4.3",
                ReleaseNotes.apiUrl("https://github.com/tricatch/oeHostsApp", "0.4.3"));
        assertEquals("http://127.0.0.1:8000/api/releases/tags/0.4.3",
                ReleaseNotes.apiUrl("http://127.0.0.1:8000", "0.4.3"));
    }

    private static String text(ReleaseNotes.Line line) {
        StringBuilder b = new StringBuilder();
        for (ReleaseNotes.Span s : line.spans()) b.append(s.text());
        return b.toString();
    }
}

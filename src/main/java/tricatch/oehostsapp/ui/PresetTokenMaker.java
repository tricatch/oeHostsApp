package tricatch.oehostsapp.ui;

import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenMakerBase;

import javax.swing.text.Segment;
import java.util.function.Predicate;

/**
 * Colours a list editor (the presets, the SSL proxy's domains) line by line: a comment is one
 * {@link Token#COMMENT_EOL} token, a line that is not a valid entry one
 * {@link Token#ERROR_IDENTIFIER} token, and an entry plain text. The screen gives the rule it
 * saves by, so red means "not saved".
 */
final class PresetTokenMaker extends TokenMakerBase {

    private final Predicate<String> valid;

    PresetTokenMaker(Predicate<String> valid) {
        this.valid = valid;
    }

    @Override
    public Token getTokenList(Segment text, int initialTokenType, int startOffset) {
        resetTokenList();
        if (text.count == 0) {
            addNullToken();
            return firstToken;
        }
        String line = new String(text.array, text.offset, text.count);
        int type;
        if (line.isBlank()) type = Token.WHITESPACE;
        else if (PresetsPage.isComment(line)) type = Token.COMMENT_EOL;
        else if (!valid.test(line)) type = Token.ERROR_IDENTIFIER;
        else type = Token.IDENTIFIER;
        addToken(text, text.offset, text.offset + text.count - 1, type, startOffset);
        addNullToken();
        return firstToken;
    }
}

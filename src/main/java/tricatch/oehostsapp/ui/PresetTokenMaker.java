package tricatch.oehostsapp.ui;

import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenMakerBase;

import javax.swing.text.Segment;

/**
 * Colours the preset editor line by line: a comment is one {@link Token#COMMENT_EOL} token, a
 * line that is not a valid "name | value" entry one {@link Token#ERROR_IDENTIFIER} token, and an
 * entry plain text. The rules are {@link SettingsDialog#presetLine}'s, so red means "ignored on save".
 */
final class PresetTokenMaker extends TokenMakerBase {

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
        else if (SettingsDialog.isPresetComment(line)) type = Token.COMMENT_EOL;
        else if (SettingsDialog.presetLine(line) == null) type = Token.ERROR_IDENTIFIER;
        else type = Token.IDENTIFIER;
        addToken(text, text.offset, text.offset + text.count - 1, type, startOffset);
        addNullToken();
        return firstToken;
    }
}

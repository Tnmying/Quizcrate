package pdfprobe;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict tokenization: a partially understood answer row is never partially applied. */
public final class AnswerTableParser {
    private static final String LETTER = "A-Za-zＡ-Ｚａ-ｚ";
    private static final Pattern ENTRY = Pattern.compile(
        "(?:[（(][\\t \u3000]*)?([0-9０-９]{1,4})[\\t \u3000]*(?:[.．、:：)）]|[\\t \u3000]+)[\\t \u3000]*"
        + "([" + LETTER + "]+(?:[\\t \u3000、,，/]+[" + LETTER + "]+)*|正确|错误|对|错|[√×✓✗✔✘])");

    private AnswerTableParser() {}

    public static final class Entry {
        public final String number;
        public final String answer;
        Entry(String number, String answer) { this.number = number; this.answer = answer; }
    }

    /** Null means the complete row could not be explained by the table grammar. */
    public static List<Entry> parse(String line) {
        List<Entry> entries = new ArrayList<>();
        int cursor = 0;
        while (cursor < line.length()) {
            while (cursor < line.length() && (Character.isWhitespace(line.charAt(cursor)) || ",，;；|｜".indexOf(line.charAt(cursor)) >= 0)) cursor++;
            if (cursor == line.length()) break;
            Matcher matcher = ENTRY.matcher(line);
            matcher.region(cursor, line.length());
            if (!matcher.lookingAt()) return null;
            if ((line.charAt(cursor) == '(' || line.charAt(cursor) == '（')
                    && !matcher.group().matches("^[（(][\\t \u3000]*[0-9０-９]+[\\t \u3000]*[）)].*")) return null;
            entries.add(new Entry(ascii(matcher.group(1)), ascii(matcher.group(2)).replaceAll("[\\s\u3000、,，/]", "")));
            cursor = matcher.end();
        }
        return entries.isEmpty() ? null : entries;
    }

    /** Normalize structural digits/letters only; never normalize the question's text or offsets. */
    public static String ascii(String token) {
        StringBuilder result = new StringBuilder(token.length());
        for (char value : token.toCharArray()) {
            if ((value >= '０' && value <= '９') || (value >= 'Ａ' && value <= 'Ｚ') || (value >= 'ａ' && value <= 'ｚ')) value -= 0xFEE0;
            result.append(value);
        }
        return result.toString();
    }
}

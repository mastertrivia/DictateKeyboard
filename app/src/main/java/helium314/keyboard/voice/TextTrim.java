// Ported from SpeechNotes' c.c.b.b, plus the spoken-punctuation layer that the
// newer note app (n.b) added on top. The v2.0.2 keyboard built the language
// tables but never used them; the note app wired them up so that a spoken
// punctuation phrase at the end of a segment is converted to the real symbol.
// Only that active layer is ported here — the keyboard's trim behavior and the
// whole composing/commit flow are unchanged.
package helium314.keyboard.voice;

import java.util.Arrays;
import java.util.List;

/** Trim helpers (live) + language-specific spoken-punctuation dictionary. */
public class TextTrim {
    /** Spoken phrases that map to punctuation, per language (was c.c.b.b tables). */
    private final List<String> spokenPunctuationPhrases;
    /** The punctuation symbols the phrases map to, same order as the phrases. */
    private final List<String> spokenPunctuationSymbols;

    @SuppressWarnings("unused")
    private final String language;

    public TextTrim(String str, Boolean bool) {
        this.language = str;
        String lang = str.split("-", -1)[0];
        switch (lang) {
            case "ar":
                spokenPunctuationPhrases = Arrays.asList("فترة", "فاصلة مفاصلة", "علامة استفهام", "نقطتان", "نقوطة", "طة التعجب", "علامة تعجب،", "خط جديد", "فقرة جديدة", "افتح القوسان", "أغلق القوسان", "الشرطة", "مبتسم", "وجه حزين", "الشرطة");
                spokenPunctuationSymbols = Arrays.asList(".", ";", "?", ":", ",", "!", "!", "\n", "\n\n", "(", ")", "-", ":-)", ":-(", "-");
                break;
            case "da":
                spokenPunctuationPhrases = Arrays.asList("punktum", "komma", "spørgsmålstegn", "udråbstegn", "tankestreg", "kolon", "ny linie", "nyt afsnit", "venstre parantes", "højre parantes");
                spokenPunctuationSymbols = Arrays.asList(".", ",", "?", "!", "-", ":", "\n", "\n\n", "(", ")");
                break;
            case "de":
                spokenPunctuationPhrases = Arrays.asList("punkt", "komma", "fragezeichen", "doppelpunkt", "semikolon", "semikolon", "semikolon", "ausrufezeichen", "ausrufezeichen", "neue zeile", "neuer absatz", "klammer öffnen", "klammer schließen", "bindestrich", "smiley", "trauriges gesicht", "Bindestrich");
                spokenPunctuationSymbols = Arrays.asList(".", ",", "?", ":", ";", ";", ";", "!", "!", "\n", "\n\n", "(", ")", "-", ":-)", ":-(", "-");
                break;
            case "es":
                spokenPunctuationPhrases = Arrays.asList("coma", "signo de interrogación", "dos puntos", "2 puntos", "punto y coma", "punto y,", "punto y ,", ". y coma", ". y,", ". y ,", "punto", "signo de exclamación", "exclamación", "nueva línea", "nuevo apartado", "abrir paréntesis", "cerrar paréntesis", "guión", "cara sonriente", "cara triste", "guión");
                spokenPunctuationSymbols = Arrays.asList(",", "?", ":", ":", ";", ";", ";", ";", ";", ";", ".", "!", "!", "\n", "\n\n", "(", ")", "-", ":-)", ":-(", "-");
                break;
            case "fr":
                spokenPunctuationPhrases = Arrays.asList("virgule", "point d'interrogation", "deux-points", "deux points", "2 points", "point-virgule", "point virgule", "point ,", "point,", "point d'exclamation", "point", "nouvelle ligne", "nouveau paragraphe", "ouvrir la parenthèse", "fermer la parenthèse", "tiret", "smiley", "visage triste", "tiret");
                spokenPunctuationSymbols = Arrays.asList(",", "?", ":", ":", ":", ";", ";", ";", ";", "!", ".", "\n", "\n\n", "(", ")", "-", ":-)", ":-(", "-");
                break;
            case "it":
                spokenPunctuationPhrases = Arrays.asList("virgula", "punto interrogativo", "due punti", "2 punti", "punto e virgola", "punto e,", "punto e ,", "esclamativo", "punto esclamativo", "punto", "nuova riga", "nuovo paragrafo", "apri parentesi", "chiudi parentesi", "trattino", "smiley", "faccina sorridente", "faccina triste", "trattino");
                spokenPunctuationSymbols = Arrays.asList(",", "?", ":", ":", ";", ";", ";", "!", "!", ".", "\n", "\n\n", "(", ")", "-", ":-)", ":-)", ":-(", "-");
                break;
            case "ja":
                spokenPunctuationPhrases = Arrays.asList("ピリオド", "コンマ", "疑問符", "コロン", "セミコロン", "感嘆符", "感嘆符記号", "改行", "新しい段落", "括弧開き", "括弧閉じ", "ダッシュ", "スマイリー", "悲しい顔", "ダッシュ");
                spokenPunctuationSymbols = Arrays.asList(".", ",", "?", ":", ";", "!", "!", "\n", "\n\n", "(", ")", "-", ":-)", ":-(", "-");
                break;
            case "nl":
                spokenPunctuationPhrases = Arrays.asList("punt", "komma", "vraagteken", "uitroepteken");
                spokenPunctuationSymbols = Arrays.asList(".", ",", "?", "!");
                break;
            case "pt":
                spokenPunctuationPhrases = Arrays.asList("interrogação", "dois pontos", "2 pontos", "ponto e vírgula", "ponto e,", "ponto e ,", "ponto", "vírgula", "exclamação", "nova linha", "parágrafo", "abre parêntese", "fecha parêntese", "hífen", "smiley", "rosto triste", "hífen");
                spokenPunctuationSymbols = Arrays.asList("?", ":", ":", ";", ";", ";", ".", ",", "!", "\n", "\n\n", "(", ")", "-", ":-)", ":-(", "-");
                break;
            case "ru":
                spokenPunctuationPhrases = Arrays.asList("запятая", "вопросительный знак", "двоеточие", "точка с запятой", "точка с,", "точка с ,", "точка", "восклицательный символ", "восклицательный знак", "новая строка", "новый параграф", "открывающаяся скобка", "закрывающаяся скобка", "тире", "смайлик", "улыбочка", "грустное лицо", "тире");
                spokenPunctuationSymbols = Arrays.asList(",", "?", ":", ";", ";", ";", ".", "!", "!", "\n", "\n\n", "(", ")", "-", ":-)", ":-)", ":-(", "-");
                break;
            case "cmn":
                spokenPunctuationPhrases = Arrays.asList("句号", "逗号", "问号", "冒号", "分号", "感叹号", "换行", "新段落", "左圆括号", "右圆括号", "破折号", "笑脸", "悲伤的脸", "破折号");
                spokenPunctuationSymbols = Arrays.asList(".", ",", "?", ":", ";", "!", "\n", "\n\n", "(", ")", "——", ":-)", ":-(", "——");
                break;
            default:
                spokenPunctuationPhrases = Arrays.asList("period", "comma", "question mark", "colon", "semicolon", "semi colon", "semi:", "semi :", "exclamation mark", "exclamation point", "new line", "new paragraph", "open parenthesis", "open parentheses", "close parenthesis", "close parentheses", "hyphen", "smiley", "smiley face", "sad face", "dash", "open quotation", "close quotation", "quotation");
                spokenPunctuationSymbols = Arrays.asList(".", ",", "?", ":", ";", ";", ";", ";", "!", "!", "\n", "\n\n", "(", "(", ")", ")", "-", ":-)", ":-)", ":-(", "-", "\u201C", "\u201D", "\"");
                break;
        }
    }

    /** Trim trailing spaces. (was a(String)) */
    public static String trimEnd(String str) {
        if (str.length() <= 0) {
            return str;
        }
        while (str.substring(str.length() - 1).equals(" ") && str.length() > 0) {
            str = str.substring(0, str.length() - 1);
            if (str.length() == 0) {
                return "";
            }
        }
        return str;
    }

    /** Trim leading spaces. (was b(String)) */
    public static String trimStart(String str) {
        if (str.length() > 0) {
            while (str.substring(0).equals(" ") && str.length() > 0) {
                str = str.substring(1);
            }
        }
        return str;
    }

    /** Convert a spoken punctuation phrase at the end of the text into the real
     *  punctuation symbol, per the language table. Text without a trailing
     *  spoken-punctuation phrase is returned unchanged (trimmed), exactly like
     *  plain trim. (was n.b.a — the accuracy layer the newer note app added) */
    public String formatSpoken(String str) {
        String punctuation = "";
        int phraseLength = 0;
        String trimmed = trimEnd(str);
        int length = trimmed.length();
        String lower = trimmed.toLowerCase();
        for (int i = 0; i < spokenPunctuationPhrases.size(); i++) {
            String phrase = spokenPunctuationPhrases.get(i);
            int diff = length - phrase.length();
            String suffix;
            if (diff > 0) {
                suffix = " " + phrase;
            } else if (diff < 0) {
                continue;
            } else {
                suffix = phrase;
            }
            if (lower.endsWith(suffix)) {
                punctuation = spokenPunctuationSymbols.get(i);
                phraseLength = suffix.length();
                break;
            }
        }
        return trimEnd(trimmed.substring(0, trimmed.length() - phraseLength)) + punctuation;
    }
}

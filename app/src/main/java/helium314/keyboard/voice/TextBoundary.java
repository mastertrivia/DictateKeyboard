// Ported from SpeechNotes' c.c.b.a (text spacing/capitalization/boundary algorithm).
// Logic identical to the original; only names were made readable.
package helium314.keyboard.voice;

import android.util.Log;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/** Text post-processing: leading-space and capitalization decisions for voice input. */
public class TextBoundary {

    private static final List<String> f1057a = Arrays.asList(" ", "\n", " (", " {", " [", "“", " '");
    private static final List<String> f1058b = Arrays.asList(" ", "\n", ".", ",", "?", "!", ":", ";", ")", "}", "]", "”", "'", "%");
    private static final List<String> f1059c = Arrays.asList("\n", ".", ":", "?", "!", ":-)", ":-(");
    private static final List<String> d = Arrays.asList("I ", "I'");
    public static final List<String> e = Arrays.asList("cmn-Hans-CN", "cmn-Hans-HK", "cmn-Hant-TW", "yue-Hant-HK", "ja-JP", "ko-KR");

    static {
        Arrays.asList(".", "\n", "(", "{", "[", "“", "'");
    }

    /** Main merge algorithm: decide leading space + capitalization of incoming text. (was a) */
    public static String process(String str, String str2, String str3, String str4) {
        Boolean bool;
        Boolean bool2;
        if (e.contains(str4)) {
            return str;
        }
        Log.d("RonenSpeechkeys", str2 + ";" + str3);
        if (str == null || str.length() < 1) {
            return "";
        }
        if (str.equals(" ")) {
            return str;
        }
        String a2 = TextTrim.trimStart(TextTrim.trimEnd(str));
        if (a2.length() == 0) {
            return "";
        }
        if (str2.length() == 0) {
            bool2 = Boolean.TRUE;
            bool = Boolean.FALSE;
        } else {
            Boolean bool3 = endsWithSentenceEnding(str2) ? Boolean.TRUE : Boolean.FALSE;
            bool = (endsWithSpaceOrOpening(str2) || startsWithClosingPunctuation(a2) || bothDigitsOrCurrency(Character.valueOf(str2.charAt(str2.length() - 1)), Character.valueOf(a2.charAt(0)))) ? Boolean.FALSE : Boolean.TRUE;
            bool2 = bool3;
        }
        String b2 = bool2.booleanValue() ? capitalizeFirst(a2) : decapitalizeFirst(a2);
        if (!bool.booleanValue()) {
            return b2;
        }
        return " " + b2;
    }

    /** Uppercase first character. (was b) */
    public static String capitalizeFirst(String str) {
        if (str.length() <= 0) {
            return "";
        }
        if (str.length() <= 1) {
            return str.substring(0, 1).toUpperCase();
        }
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }

    /** Lowercase first character, unless it starts with "I " or "I'". (was c) */
    public static String decapitalizeFirst(String str) {
        if (str.length() <= 0 || startsWithAnyOf(str, d)) {
            return str;
        }
        if (str.length() <= 1) {
            return str.substring(0, 1).toLowerCase();
        }
        return str.substring(0, 1).toLowerCase() + str.substring(1);
    }

    public static Boolean startsWithAnyOf(String str, List<String> list) {
        Boolean bool = Boolean.FALSE;
        Iterator<String> it = list.iterator();
        while (it.hasNext()) {
            if (str.indexOf(it.next()) == 0) {
                return Boolean.TRUE;
            }
        }
        return bool;
    }

    /** Number of characters to delete for word-boundary backspace. (was e) */
    public static int deleteBoundaryLength(String str) {
        if (str == null || str.length() == 0) {
            return 0;
        }
        if (str.length() == 1) {
            return 1;
        }
        Character valueOf = Character.valueOf(str.charAt(str.length() - 1));
        if (!Character.isLetter(valueOf.charValue())) {
            return (Character.isLowSurrogate(valueOf.charValue()) || Character.isHighSurrogate(valueOf.charValue())) ? 2 : 1;
        }
        if (str.lastIndexOf("\n") != -1) {
            str = str.substring(str.lastIndexOf("\n"));
        }
        int i = 0;
        while (str.length() > 0 && str.substring(str.length() - 1).equals(" ")) {
            i++;
            str = str.substring(0, str.length() - 1);
        }
        if (i > 0 || i != 0) {
            return i;
        }
        String[] split = str.split("\\W");
        return split.length > 0 ? split[split.length - 1].length() : i;
    }

    /** Whether the text ends with a sentence ender (., :, ?, !, smileys). (was f) */
    public static Boolean endsWithSentenceEnding(String str) {
        Boolean bool = Boolean.FALSE;
        String a2 = TextTrim.trimEnd(str);
        int length = a2.length();
        for (String str2 : f1059c) {
            if (length >= str2.length() && a2.indexOf(str2, a2.length() - str2.length()) != -1) {
                return Boolean.TRUE;
            }
        }
        return bool;
    }

    /** Slice the trailing context before the composing region. (was g) */
    public static String sliceBeforeComposing(String str, int i, int i2) {
        return (str != null && str.length() != 0 && i < i2 && i2 > (-str.length())) ? substringRange(str, str.length() + i, str.length() + i2) : "";
    }

    public static String substringRange(String str, int i, int i2) {
        return (str == null || str.length() == 0 || i >= i2) ? "" : str.substring(Math.max(0, i), Math.min(str.length(), i2));
    }

    /** Whether text ends with a space or an opening bracket/quotation. (was i) */
    public static Boolean endsWithSpaceOrOpening(String str) {
        Boolean bool = Boolean.FALSE;
        int length = str.length();
        for (String str2 : f1057a) {
            if (length >= str2.length() && str.indexOf(str2, str.length() - str2.length()) != -1) {
                return Boolean.TRUE;
            }
        }
        return bool;
    }

    /** Whether text starts with closing punctuation. (was j) */
    public static Boolean startsWithClosingPunctuation(String str) {
        Boolean bool = Boolean.FALSE;
        Iterator<String> it = f1058b.iterator();
        while (it.hasNext()) {
            if (str.indexOf(it.next()) == 0) {
                return Boolean.TRUE;
            }
        }
        return bool;
    }

    /** Whether both chars are digits or $ (no space between them). (was k) */
    public static Boolean bothDigitsOrCurrency(Character ch, Character ch2) {
        return ("$.1234567890".indexOf(ch.charValue()) == -1 || "$.1234567890".indexOf(ch2.charValue()) == -1) ? Boolean.FALSE : Boolean.TRUE;
    }
}

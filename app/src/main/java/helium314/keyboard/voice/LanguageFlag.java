// Ported from SpeechNotes' c.c.a.c. Logic identical.
package helium314.keyboard.voice;

/** Language code -> regional flag emoji helper. */
public class LanguageFlag {
    static String a(String str) {
        if (str == null || str.length() != 2) {
            return "";
        }
        String upperCase = str.toUpperCase();
        StringBuffer stringBuffer = new StringBuffer();
        stringBuffer.append(Character.toChars(upperCase.charAt(0) + 61861));
        stringBuffer.append(Character.toChars(upperCase.charAt(1) + 61861));
        return stringBuffer.toString();
    }

    public static String b(String str) {
        if (str == null || str.length() < 5) {
            return "";
        }
        str.replaceAll("-", "_");
        return a(str.contains("_") ? str.split("_")[1] : str.substring(3, 5));
    }
}

// Ported from SpeechNotes' c.c.a.d. Logic identical.
package helium314.keyboard.voice;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** BCP-47 language table with display names (used by the language picker). */
public class LanguageTable {

    public static final String[][] f1053a = {new String[]{"af-ZA", "Afrikaans"}, new String[]{"am-ET", "አማርኛ, Amharic"}, new String[]{"az-AZ", "Azərbaycan"}, new String[]{"id-ID", "Bahasa, Indonesia"}, new String[]{"ms-MY", "Bahasa, Melayu"}, new String[]{"bg-BG", "Bulgarian"}, new String[]{"ca-ES", "Catalan"}, new String[]{"cs-CZ", "Czech"}, new String[]{"da-DK", "Dansk (Danish)"}, new String[]{"de-DE", "Deutsch"}, new String[]{"nl-NL", "Dutch, Netherlands"}, new String[]{"el-GR", "Ελληνικά"}, new String[]{"en-AU", "English, Australia"}, new String[]{"en-CA", "English, Canada"}, new String[]{"en-GH", "English (Ghana)"}, new String[]{"en-IN", "English, India"}, new String[]{"en-IE", "English (Ireland)"}, new String[]{"en-KE", "English (Kenya)"}, new String[]{"en-NZ", "English, New Zealand"}, new String[]{"en-NG", "English (Nigeria)"}, new String[]{"en-PH", "English (Philippines)"}, new String[]{"en-ZA", "English, S. Africa"}, new String[]{"en-TZ", "English (Tanzania)"}, new String[]{"en-GB", "English, UK"}, new String[]{"en-US", "English, US", "default"}, new String[]{"es-AR", "español, Argentina"}, new String[]{"es-BO", "español, Bolivia"}, new String[]{"es-CL", "español, Chile"}, new String[]{"es-CO", "español, Colombia"}, new String[]{"es-CR", "español, Costa Rica"}, new String[]{"es-EC", "español, Ecuador"}, new String[]{"es-SV", "español, El Salvador"}, new String[]{"es-ES", "español, España", "default"}, new String[]{"es-US", "español, Estados Unidos"}, new String[]{"es-GT", "español, Guatemala"}, new String[]{"es-HN", "español, Honduras"}, new String[]{"es-MX", "español, México"}, new String[]{"es-NI", "español, Nicaragua"}, new String[]{"es-PA", "español, Panamá"}, new String[]{"es-PY", "español, Paraguay"}, new String[]{"es-PE", "español, Perú"}, new String[]{"es-PR", "español, Puerto Rico"}, new String[]{"es-DO", "español, R. Dominicana"}, new String[]{"es-UY", "español, Uruguay"}, new String[]{"es-VE", "español, Venezuela"}, new String[]{"fil-PH", "Filipino"}, new String[]{"fr-FR", "français"}, new String[]{"gl-ES", "Galego"}, new String[]{"hi-IN", "Hindi हिन्दी"}, new String[]{"hr-HR", "Hrvatski"}, new String[]{"is-IS", "Icelandic"}, new String[]{"zu-ZA", "IsiZulu"}, new String[]{"it-IT", "italiano", "default"}, new String[]{"it-CH", "italiano, Svizzera"}, new String[]{"jv-ID", "Jawa (Indonesia)"}, new String[]{"lv-LV", "Latviešu"}, new String[]{"lt-LT", "Lietuvių"}, new String[]{"hu-HU", "Magyar"}, new String[]{"nb-NO", "Norwegian"}, new String[]{"pl-PL", "Polski"}, new String[]{"pt-BR", "Português, Brasil", "default"}, new String[]{"pt-PT", "Português, Portugal"}, new String[]{"ro-RO", "română"}, new String[]{"ru-RU", "России"}, new String[]{"sr-RS", "Српски, Serbian"}, new String[]{"sk-SK", "Slovak"}, new String[]{"sl-SI", "Slovenščina"}, new String[]{"fi-FI", "Suomi"}, new String[]{"sv-SE", "Svenska"}, new String[]{"sw-TZ", "Swahili (Tanzania)", "default"}, new String[]{"sw-KE", "Swahili (Kenya)"}, new String[]{"tr-TR", "Turkish"}, new String[]{"uk-UA", "Українська (Ukrainian)"}, new String[]{"su-ID", "Urang (Indonesia)"}, new String[]{"hy-AM", "Հայ (Հայաստան)"}, new String[]{"ka-GE", "ქართული"}, new String[]{"bn-BD", "াংলা (বাংলাদেশ)", "default"}, new String[]{"bn-IN", "বাংলা (ভারত)"}, new String[]{"gu-IN", "ગુજરાતી (ભારત)"}, new String[]{"hi-IN", "हिन्दी (Hindi)", "default"}, new String[]{"kn-IN", "ಕನ್ನಡ (ಭಾರತ)\tKannada"}, new String[]{"km-KH", "ភាសាខ្មែរ (កម្ពុជា) (Cambodia)"}, new String[]{"lo-LA", "ລາວ (ລາວ) Lao"}, new String[]{"ml-IN", "മലയാളം (ഇന്ത്യ) Malayalam"}, new String[]{"mr-IN", "मराठी (भारत) Marathi"}, new String[]{"ne-NP", "नेपाली (नेपाल) Nepali"}, new String[]{"si-LK", "සිංහල (ශ්රී ලංකාව) (Srilanka)"}, new String[]{"ta-IN", "தமிழ் (இந்தியா) Tamil", "default"}, new String[]{"ta-SG", "தமிழ் (சிங்கப்பூர்) Tamil"}, new String[]{"ta-LK", "தமிழ் (இலங்கை) Tamil"}, new String[]{"ta-MY", "தமிழ் (மலேசியா) Tamil"}, new String[]{"te-IN", "తెలుగు (భారతదేశం) Telugu"}, new String[]{"th-TH", "ไทย (Thai)"}, new String[]{"vi-VN", "Tiếng Việt"}, new String[]{"cmn-Hans-CN", "普通话 (中国大陆)", "default"}, new String[]{"cmn-Hans-HK", "普通话 (香港)"}, new String[]{"cmn-Hant-TW", "中文 (台灣)"}, new String[]{"yue-Hant-HK", "粵語 (香港)"}, new String[]{"ja-JP", "日本語 (Japanese)"}, new String[]{"ko-KR", "한국어, (Korean)"}, new String[]{"he-IL", "עברית"}, new String[]{"ar-DZ", "العربية, Algeria"}, new String[]{"ar-BH", "العربية (البحرين)"}, new String[]{"ar-EG", "العربية, Egypt", "default"}, new String[]{"ar-IQ", "العربية, Iraq"}, new String[]{"ar-IL", "العربية (إسرائيل)"}, new String[]{"ar-JO", "العربية, Jordan"}, new String[]{"ar-KW", "العربية, Kuwait"}, new String[]{"ar-LB", "العربية, Lebanon"}, new String[]{"ar-MA", "العربية, Morocco"}, new String[]{"ar-OM", "العربية (عُمان)"}, new String[]{"ar-PS", "العربية (فلسطين)"}, new String[]{"ar-QA", "العربية, Qatar"}, new String[]{"ar-SA", "العربية, Saudi Arabia"}, new String[]{"ar-TN", "العربية (تونس)"}, new String[]{"ar-AE", "العربية, UAE"}, new String[]{"fa-IR", "فارسی (ایران) Persian"}, new String[]{"ur-PK", "اردو (پاکستان)Urdu"}, new String[]{"ur-IN", "اردو (بھارت) Urdu"}};

    private static String[] f1054b;
    private static String[] f1055c;
    private static ArrayList<String> d;

    static {
        a();
        String[][] strArr = f1053a;
        f1054b = new String[strArr.length];
        f1055c = new String[strArr.length];
        d = new ArrayList<>();
    }

    public LanguageTable() {
        int i = 0;
        while (true) {
            String[][] strArr = f1053a;
            if (i >= strArr.length) {
                return;
            }
            String[] strArr2 = f1055c;
            strArr2[i] = strArr[i][0];
            f1054b[i] = strArr[i][1];
            String str = strArr2[i].split("-", -1)[0];
            if (!d.contains(str)) {
                d.add(str);
            }
            i++;
        }
    }

    private static Map<String, String> a() {
        HashMap hashMap = new HashMap();
        hashMap.put("ja", "日本語");
        hashMap.put("ko", "한국어");
        hashMap.put("he", "עב");
        hashMap.put("ar", "العربية");
        hashMap.put("hi", "हिन्दी");
        hashMap.put("ru", "России");
        hashMap.put("cmn", "普通话");
        return hashMap;
    }

    /** Display name for a BCP-47 code. (was b) */
    public static String displayName(String str) {
        int i = 0;
        while (true) {
            String[][] strArr = f1053a;
            if (i >= strArr.length) {
                return "";
            }
            if (strArr[i][0].equals(str)) {
                return f1053a[i][1];
            }
            i++;
        }
    }

    /** All display names. (was c) */
    public static String[] c() {
        return f1054b;
    }

    /** BCP-47 code for a display name. (was d) */
    public static String codeForDisplayName(String str) {
        int i = 0;
        while (true) {
            String[][] strArr = f1053a;
            if (i >= strArr.length) {
                return "";
            }
            if (strArr[i][1].equals(str)) {
                return f1053a[i][0];
            }
            i++;
        }
    }
}

// Ported from SpeechNotes' c.c.a.e. Logic identical.
package helium314.keyboard.voice;

import android.util.Log;

/** SpeechNotes' SmartLog logging toggle. */
public class SmartLog {
    static boolean f1056a = true;

    public static void a(String str, String str2) {
        if (f1056a) {
            Log.d("SmartLog:" + str, str2);
        }
    }

    public static void b(boolean z) {
        f1056a = z;
    }
}

// Ported from SpeechNotes' c.c.a.f. Logic identical.
package helium314.keyboard.voice;

import android.media.AudioManager;
import android.os.Build;

/** AudioManager stream helpers: save/restore/mute music (3) and system (5) streams. */
public class AudioStreamHelper {
    public static int getMusicVolume(AudioManager audioManager) {
        try {
            return audioManager.getStreamVolume(3);
        } catch (Exception e) {
            e.printStackTrace();
            return -1;
        }
    }

    public static int getSystemVolume(AudioManager audioManager) {
        try {
            return audioManager.getStreamVolume(5);
        } catch (Exception e) {
            e.printStackTrace();
            return -1;
        }
    }

    public static void restoreMusicVolume(AudioManager audioManager, int i) {
        if (i <= 0) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                audioManager.setStreamVolume(3, i, 0);
            } else {
                audioManager.setStreamMute(3, false);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void restoreSystemVolume(AudioManager audioManager, int i) {
        if (i <= 0) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                audioManager.setStreamVolume(5, i, 0);
            } else {
                audioManager.setStreamMute(5, false);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void muteStreams(AudioManager audioManager) {
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                audioManager.setStreamVolume(3, -100, 0);
                audioManager.setStreamVolume(5, -100, 0);
            } else {
                audioManager.setStreamMute(3, true);
                audioManager.setStreamMute(5, true);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

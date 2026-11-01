package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.view.InputDevice;
import android.view.KeyEvent;

/**
 * App buttons on TV remotes: Netflix, Prime Video, YouTube, etc. Some remotes send a standard keycode (BUTTON_3,
 * BUTTON_4, BUTTON_9); some send a vendor-specific one (TCL's YouTube is 294, STEM_1 is 265).
 *
 * <p>They are filtered by an accessibility service (RemoteKeyService). The ones not assigned in Settings open their
 * standard app.
 */
public class RemoteKeys {
    // Key codes found on TV remotes (the standard Android ones first, then manufacturer extras).
    static final int KEY_NETFLIX = KeyEvent.KEYCODE_BUTTON_4;     // Android 11 standard, also TCL, Sony, Hisense
    static final int KEY_PRIME = KeyEvent.KEYCODE_BUTTON_3;       // Android 11 standard, also TCL
    static final int KEY_STEM_1 = 265;                            // Vestel, Formovie (Netflix)
    static final int KEY_YOUTUBE_TCL = 294;                       // TCL
    static final int KEY_CHANNELS_TCL = 300;                      // TCL Channel
    static final int KEY_MEDIA_TCL = 297;                         // TCL Media Center
    static final int KEY_GUARD_TCL = 302;                         // TCL Safety Guard
    static final int KEY_BROWSER_TCL = 299;                       // TCL Browser
    static final int KEY_TCL_HOME = 303;                          // TCL Home

    static final boolean TCL = is("TCL"), VESTEL = is("Vestel"), KTC = is("KTC"), SONY = is("Sony"), FORMOVIE = is("Formovie");
    /** Brands whose Netflix button is STEM_1, a global key (MediaTek): only that button is ours. */
    static final boolean STEM_NETFLIX = VESTEL || FORMOVIE;

    static boolean supported() {
        return true;
    }

    private static boolean is(String maker) {
        return maker.equalsIgnoreCase(Build.MANUFACTURER) || maker.equalsIgnoreCase(Build.BRAND);
    }

    /** Opens the app for this key on its first press. True if the key is ours and in use (it is then consumed). */
    static boolean onKey(Context c, KeyEvent e) {
        // BUTTON_3 is also a game controller's button: keys from a gamepad or a joystick are left alone.
        int source = e.getSource();
        if ((source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) return false;
        int keyCode = e.getKeyCode() == KEY_YOUTUBE_TCL ? Prefs.KEY_YOUTUBE : e.getKeyCode();
        // Sony sends BUTTON_9 for five app buttons (BUTTON_4 for Netflix): only the scan code tells them apart.
        if (SONY && (keyCode == KeyEvent.KEYCODE_BUTTON_4 || keyCode == KeyEvent.KEYCODE_BUTTON_9)) {
            keyCode = Prefs.SONY_KEY + e.getScanCode();
        }
        boolean ours = false;
        for (int key : Prefs.KEYS) ours |= key == keyCode;
        if (!ours) return false;
        if (e.getAction() != KeyEvent.ACTION_UP || e.getRepeatCount() > 0) return true;
        String pkg = Prefs.keyApp(keyCode);
        if (pkg.isEmpty()) return false;
        Intent it = c.getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (it == null) it = c.getPackageManager().getLaunchIntentForPackage(pkg);
        if (it != null) {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(it);
        }
        return true;
    }
}

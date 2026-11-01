package io.freemytv;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

public class RemoteKeyService extends AccessibilityService {
    private static final String TAG = "RemoteKeyService";
    private static final String STOCK_LAUNCHER = "com.google.android.tvlauncher";

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        CharSequence pkg = event.getPackageName();
        if (pkg != null) {
            String p = pkg.toString();
            if (STOCK_LAUNCHER.equals(p) || "com.ebsproject.drawer".equals(p) || p.contains("tvlauncher")) {
                Log.i(TAG, "Launcher detected (" + p + "), redirecting to HomeActivity");
                launchHome();
            }
        }
    }

    private void launchHome() {
        Intent intent = new Intent(this, HomeActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch HomeActivity: " + e.getMessage());
        }
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event != null && event.getKeyCode() == KeyEvent.KEYCODE_HOME) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                launchHome();
            }
            return true;
        }
        return RemoteKeys.onKey(this, event);
    }

    @Override
    public void onInterrupt() {}
}

package com.dnialify.musicstream;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/** Ducks native playback when an outside notification with sound arrives. */
public class NotificationDuckService extends NotificationListenerService {
    private static NotificationDuckService instance;

    @Override
    public void onListenerConnected() {
        instance = this;
        Log.d("NDS", "[NDS] connected, instance assigned");
    }

    @Override
    public void onListenerDisconnected() {
        instance = null;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        Log.d("NDS", "[NDS] onNotificationPosted fired, pkg="
                + (sbn != null ? sbn.getPackageName() : "null"));
        if (sbn == null) {
            Log.d("NDS", "[NDS] SKIP reason=null_notification pkg=null");
            return;
        }
        if (sbn.getPackageName() == null) {
            Log.d("NDS", "[NDS] SKIP reason=null_package pkg=null");
            return;
        }
        String pkg = sbn.getPackageName();
        // Ignore our own notifications
        if ("com.dnialify.musicstream".equals(pkg)) {
            Log.d("NDS", "[NDS] SKIP reason=own_package pkg=" + pkg);
            return;
        }
        // Ignore ongoing / media notifications (they don't play sound)
        if (sbn.isOngoing()) {
            Log.d("NDS", "[NDS] SKIP reason=ongoing pkg=" + pkg);
            return;
        }

        // Duck
        Log.d("NDS", "[NDS] forwarding to engine: " + pkg);
        NativeAudioEngine.getInstance().onExternalNotification(sbn.getPackageName());
    }

    public static boolean isPermissionGranted() { return instance != null; }
}

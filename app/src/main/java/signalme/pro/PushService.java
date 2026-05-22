package signalme.pro;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import androidx.core.app.NotificationCompat;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

public class PushService extends FirebaseMessagingService {
    @Override
    public void onNewToken(String token) {
        // Persist token locally
        getSharedPreferences("fcm", MODE_PRIVATE)
            .edit().putString("token", token).apply();

        // Broadcast to any open WebView via LocalBroadcastManager
        android.content.Intent i = new android.content.Intent("FCM_TOKEN");
        i.putExtra("token", token);
        androidx.localbroadcastmanager.content.LocalBroadcastManager
            .getInstance(this).sendBroadcast(i);
    }

    @Override
    public void onMessageReceived(RemoteMessage message) {
        String title = getString(R.string.app_name);
        String body = "";
        String clickUrl = null;
        String tag = null;        // stable tag → groups messages per chat / signal
        String notifId = null;    // optional stable id from server

        // Notification payload (rare — server uses data-only, but be defensive)
        if (message.getNotification() != null) {
            if (message.getNotification().getTitle() != null)
                title = message.getNotification().getTitle();
            if (message.getNotification().getBody() != null)
                body = message.getNotification().getBody();
        }

        // Data payload — always delivered, even when app is killed / swiped away
        if (!message.getData().isEmpty()) {
            if (message.getData().containsKey("title"))
                title = message.getData().get("title");
            if (message.getData().containsKey("body"))
                body = message.getData().get("body");
            if (message.getData().containsKey("url"))
                clickUrl = message.getData().get("url");
            if (message.getData().containsKey("notification_id"))
                notifId = message.getData().get("notification_id");
        }

        // Group notifications by chat / signal URL so a chat with 5 unread
        // messages shows as one updating entry, not 5 (WhatsApp-style).
        tag = clickUrl != null ? clickUrl : (notifId != null ? notifId : "default");

        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (clickUrl != null) intent.putExtra("open_url", clickUrl);
        // Use tag.hashCode() as requestCode so each chat keeps its own PendingIntent
        PendingIntent pi = PendingIntent.getActivity(this, tag.hashCode(),
            intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        String channelId = getString(R.string.default_notification_channel_id);
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(pi);

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        // notify(tag, id) → same tag replaces the previous entry, so chats collapse
        if (nm != null) nm.notify(tag, 1, b.build());
    }
}

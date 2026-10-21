package dev.siconnect.notificationfixture;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;

/** Disposable fixture only. No network permission, contacts, messages, accounts or personal data. */
public final class FixtureActivity extends Activity {
    public static final int ID = 170061;
    public static final String TAG = "siconnect-disposable-notification";
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            TextView view = new TextView(this);
            view.setText("Disposable test fixture: notification permission must be granted explicitly by the test operator.");
            setContentView(view);
            return;
        }
        getSharedPreferences("receipt", MODE_PRIVATE).edit().clear().commit();
        post(this, "Disposable fixture ready");
        finish();
    }
    public static void post(Context context, String body) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("fixture", "Disposable SIConnect fixture", NotificationManager.IMPORTANCE_LOW));
        PendingIntent reply = PendingIntent.getBroadcast(context, 1, new Intent(context, FixtureReceiver.class)
            .setAction("fixture.REPLY").setData(Uri.parse("siconnect-fixture://reply")), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        PendingIntent mark = PendingIntent.getBroadcast(context, 2, new Intent(context, FixtureReceiver.class)
            .setAction("fixture.MARK").setData(Uri.parse("siconnect-fixture://mark")), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent auth = PendingIntent.getBroadcast(context, 3, new Intent(context, FixtureReceiver.class)
            .setAction("fixture.AUTH").setData(Uri.parse("siconnect-fixture://auth")), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Action replyAction = new Notification.Action.Builder(android.R.drawable.ic_menu_send, "Fixture reply", reply)
            .setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
            .addRemoteInput(new RemoteInput.Builder("fixture_text").setLabel("Disposable test input").build()).build();
        Notification.Action markAction = new Notification.Action.Builder(android.R.drawable.ic_menu_view, "Fixture mark", mark)
            .setSemanticAction(Notification.Action.SEMANTIC_ACTION_MARK_AS_READ).build();
        Notification.Action authAction = new Notification.Action.Builder(android.R.drawable.ic_lock_lock, "Fixture requires owner", auth)
            .setAuthenticationRequired(true).build();
        manager.notify(TAG, ID, new Notification.Builder(context, "fixture").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("SIConnect disposable action fixture").setContentText(body).setOnlyAlertOnce(true)
            .addAction(replyAction).addAction(markAction).addAction(authAction).build());
    }
}

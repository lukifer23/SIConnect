package dev.siconnect.notificationfixture;

import android.app.RemoteInput;
import android.content.*;
import android.os.Bundle;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Stores only receipt counters and a digest; never sends a message or retains outgoing content. */
public final class FixtureReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        try {
            Bundle results = RemoteInput.getResultsFromIntent(intent);
            CharSequence text = results == null ? null : results.getCharSequence("fixture_text");
            String digest = "";
            if (text != null) {
                byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8));
                StringBuilder out = new StringBuilder();
                for (byte value : bytes) out.append(String.format("%02x", value & 255));
                digest = out.toString();
            }
            SharedPreferences prefs = context.getSharedPreferences("receipt", Context.MODE_PRIVATE);
            prefs.edit().putInt("count", prefs.getInt("count", 0) + 1).putString("kind", intent.getAction())
                .putString("digest", digest).putBoolean("remoteInput", text != null).commit();
            FixtureActivity.post(context, "Disposable fixture action received");
        } catch (Exception error) { throw new IllegalStateException("Disposable fixture receipt failed", error); }
    }
}

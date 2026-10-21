package dev.siconnect.notificationfixture;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Separate disposable app UI: no account, network, personal data or SIConnect owner controls. */
public final class SemanticFixtureActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(24, 24, 24, 24);
        content.setFocusableInTouchMode(true);
        TextView title = new TextView(this); title.setText("SIConnect semantic fixture"); title.setTextSize(20);
        content.addView(title);
        EditText input = new EditText(this); input.setContentDescription("Fixture entry"); input.setHint("Disposable fixture text"); input.setInputType(InputType.TYPE_CLASS_TEXT);
        content.addView(input);
        EditText password = new EditText(this); password.setContentDescription("Fixture protected input"); password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); password.setText("fixture-secret-value");
        content.addView(password);
        LinearLayout protectedGroup = new LinearLayout(this) {
            @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(info); info.setPassword(true);
            }
        };
        protectedGroup.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        TextView child = new TextView(this); child.setText("fixture-protected-descendant"); protectedGroup.addView(child); content.addView(protectedGroup);
        CheckBox checkbox = new CheckBox(this); checkbox.setText("Fixture checkbox"); checkbox.setContentDescription("Fixture checkbox"); content.addView(checkbox);
        TextView status = new TextView(this); status.setText("Fixture waiting"); status.setContentDescription("Fixture status");
        Button button = new Button(this); button.setText("Complete fixture"); button.setOnClickListener(v -> status.setText("Fixture complete")); content.addView(button); content.addView(status);
        LinearLayout rows = new LinearLayout(this); rows.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < 60; i++) { TextView row = new TextView(this); row.setText("Disposable fixture row " + i); row.setPadding(12, 12, 12, 12); rows.addView(row); }
        ScrollView scroll = new ScrollView(this); scroll.setContentDescription("Fixture scroll region"); scroll.addView(rows); content.addView(scroll, new LinearLayout.LayoutParams(-1, 400));
        setContentView(content); content.requestFocus();
    }
}

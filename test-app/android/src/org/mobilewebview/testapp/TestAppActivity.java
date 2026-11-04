package org.mobilewebview.testapp;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import org.qtproject.qt.android.bindings.QtActivity;

/**
 * Test-app activity: hands every intent it is started or re-entered with to
 * {@link OpenedFileProbe}, which the Opened file screen drains.
 */
public class TestAppActivity extends QtActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        OpenedFileProbe.setContext(getApplicationContext());
        OpenedFileProbe.enqueue(getIntent(), referrer(),
                savedInstanceState == null ? "onCreate" : "onCreate(recreated)");
    }

    @Override
    public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        OpenedFileProbe.enqueue(intent, referrer(), "onNewIntent");
    }

    private String referrer() {
        Uri referrer = getReferrer();
        return referrer != null ? referrer.toString() : "";
    }
}

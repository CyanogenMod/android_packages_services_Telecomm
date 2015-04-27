package com.android.server.telecom.testapps;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.CallLog.Calls;
import android.telecom.PhoneAccount;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.EditText;
import android.widget.Toast;

import com.android.server.telecom.testapps.R;

public class TestDialerActivity extends Activity {
    private EditText mNumberView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.testdialer_main);
        findViewById(R.id.set_default_button).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                setDefault();
            }
        });
        findViewById(R.id.place_call_button).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                placeCall();
            }
        });

        findViewById(R.id.test_voicemail_button).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                testVoicemail();
            }
        });

        mNumberView = (EditText) findViewById(R.id.number);
        updateEditTextWithNumber();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        updateEditTextWithNumber();
    }

    private void updateEditTextWithNumber() {
        Intent intent = getIntent();
        if (intent != null) {
            mNumberView.setText(intent.getDataString());
        }
    }

    private void setDefault() {
        // TODO: Send a request to become the default dialer application
    }

    private void placeCall() {
        final Intent intent = new Intent(Intent.ACTION_CALL,
                Uri.fromParts(PhoneAccount.SCHEME_TEL, mNumberView.getText().toString(), null));
        startActivityForResult(intent, 0);
    }

    private void testVoicemail() {
        try {
            // Test read
            getContentResolver().query(Calls.CONTENT_URI_WITH_VOICEMAIL, null, null, null, null);
            // Test write
            final ContentValues values = new ContentValues();
            values.put(Calls.CACHED_NAME, "hello world");
            getContentResolver().update(Calls.CONTENT_URI_WITH_VOICEMAIL, values, "1=0", null);
        } catch (SecurityException e) {
            Toast.makeText(this, "Permission check failed", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "Permission check succeeded", Toast.LENGTH_SHORT).show();
    }
}

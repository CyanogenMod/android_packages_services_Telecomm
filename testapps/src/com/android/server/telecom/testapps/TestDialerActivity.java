package com.android.server.telecom.testapps;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.telecom.PhoneAccount;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.EditText;

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
}

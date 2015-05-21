/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.telecom.settings;

import android.app.ActionBar;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.telecom.Log;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.view.MenuItem;

public class EnableAccountPreferenceActivity extends Activity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);


        if (handleDirectChangeRequest()) {
            finish();
            return;
        }

        getFragmentManager().beginTransaction()
                .replace(android.R.id.content, new EnableAccountPreferenceFragment())
                .commit();

        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
    }

    private boolean handleDirectChangeRequest() {
        // Here we check to see if the intent action is the protected version which allows
        // for immediate enabling/disabling of phone accounts. If it is, take the phone account
        // handle and value and simply enable/disable the account. If any part is missing, then
        // open the setting screen as normal.
        Intent intent = getIntent();
        String action = intent.getAction();
        if (!TelecomManager.ACTION_ENABLE_PHONE_ACCOUNT_SETTING.equals(action)) {
            return false;
        }

        if (!intent.hasExtra(TelecomManager.EXTRA_ENABLE_PHONE_ACCOUNT_VALUE)) {
            Log.w(this, "Boolean extra value not found in %s",
                    TelecomManager.EXTRA_ENABLE_PHONE_ACCOUNT_VALUE);
            return false;
        }

        String desc = intent.getStringExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_DESCRIPTION);
        if (desc == null) {
            Log.w(this, "Extra value not found or is null in %s",
                    TelecomManager.EXTRA_PHONE_ACCOUNT_DESCRIPTION);
            return false;
        }

        String[] parts = desc.split(";");
        if (parts.length < 2) {
            Log.w(this, "Description not split into two parts with semi-colon: %s", desc);
            return false;
        }

        ComponentName component = ComponentName.unflattenFromString(parts[0]);
        if (component == null) {
            Log.w(this, "Value does not unflatten to ComponentName: %s", parts[0]);
            return false;
        }

        PhoneAccountHandle handle = new PhoneAccountHandle(component, parts[1]);
        boolean enabled = intent.getBooleanExtra(
                TelecomManager.EXTRA_ENABLE_PHONE_ACCOUNT_VALUE, false);
        try {
            TelecomManager.from(this).enablePhoneAccount(handle, enabled);
            return true;
        } catch (Exception e) {
            Log.e(this, e, "Exception enabling account %s, %s", parts[0], parts[1]);
            return false;
        }
    }

    /** ${inheritDoc} */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                onBackPressed();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

}

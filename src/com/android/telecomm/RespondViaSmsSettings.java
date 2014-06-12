/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.telecomm;

import android.app.ActionBar;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.preference.EditTextPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.view.Menu;
import android.view.MenuItem;

/**
 * Helper class to manage the "Respond via SMS Message" feature for incoming calls.
 */
public class RespondViaSmsSettings {
    /** SharedPreferences file name for our persistent settings. */
    private static final String SHARED_PREFERENCES_NAME = "respond_via_sms_prefs";

    // Preference keys for the 4 "canned responses"; see RespondViaSmsManager$Settings.
    // Since (for now at least) the number of messages is fixed at 4, and since
    // SharedPreferences can't deal with arrays anyway, just store the messages
    // as 4 separate strings.
    private static final int NUM_CANNED_RESPONSES = 4;
    private static final String KEY_CANNED_RESPONSE_PREF_1 = "canned_response_pref_1";
    private static final String KEY_CANNED_RESPONSE_PREF_2 = "canned_response_pref_2";
    private static final String KEY_CANNED_RESPONSE_PREF_3 = "canned_response_pref_3";
    private static final String KEY_CANNED_RESPONSE_PREF_4 = "canned_response_pref_4";
    private static final String KEY_PREFERRED_PACKAGE = "preferred_package_pref";
    private static final String KEY_INSTANT_TEXT_DEFAULT_COMPONENT = "instant_text_def_component";

    // TODO: This class is newly copied into Telecomm (com.android.telecomm) from it previous
    // location in Telephony (com.android.phone). User's preferences stored in the old location
    // will be lost. We need code here to migrate KLP -> LMP settings values.

    /**
     * Settings activity under "Call settings" to let you manage the
     * canned responses; see respond_via_sms_settings.xml
     */
    public static class Settings extends PreferenceActivity
            implements Preference.OnPreferenceChangeListener {
        @Override
        protected void onCreate(Bundle icicle) {
            super.onCreate(icicle);
            Log.d(this, "Settings: onCreate()...");

            getPreferenceManager().setSharedPreferencesName(SHARED_PREFERENCES_NAME);

            // This preference screen is ultra-simple; it's just 4 plain
            // <EditTextPreference>s, one for each of the 4 "canned responses".
            //
            // The only nontrivial thing we do here is copy the text value of
            // each of those EditTextPreferences and use it as the preference's
            // "title" as well, so that the user will immediately see all 4
            // strings when they arrive here.
            //
            // Also, listen for change events (since we'll need to update the
            // title any time the user edits one of the strings.)

            addPreferencesFromResource(R.xml.respond_via_sms_settings);

            EditTextPreference pref;
            pref = (EditTextPreference) findPreference(KEY_CANNED_RESPONSE_PREF_1);
            pref.setTitle(pref.getText());
            pref.setOnPreferenceChangeListener(this);

            pref = (EditTextPreference) findPreference(KEY_CANNED_RESPONSE_PREF_2);
            pref.setTitle(pref.getText());
            pref.setOnPreferenceChangeListener(this);

            pref = (EditTextPreference) findPreference(KEY_CANNED_RESPONSE_PREF_3);
            pref.setTitle(pref.getText());
            pref.setOnPreferenceChangeListener(this);

            pref = (EditTextPreference) findPreference(KEY_CANNED_RESPONSE_PREF_4);
            pref.setTitle(pref.getText());
            pref.setOnPreferenceChangeListener(this);

            ActionBar actionBar = getActionBar();
            if (actionBar != null) {
                // android.R.id.home will be triggered in onOptionsItemSelected()
                actionBar.setDisplayHomeAsUpEnabled(true);
            }
        }

        // Preference.OnPreferenceChangeListener implementation
        @Override
        public boolean onPreferenceChange(Preference preference, Object newValue) {
            Log.d(this, "onPreferenceChange: key = %s", preference.getKey());
            Log.d(this, "  preference = '%s'", preference);
            Log.d(this, "  newValue = '%s'", newValue);

            EditTextPreference pref = (EditTextPreference) preference;

            // Copy the new text over to the title, just like in onCreate().
            // (Watch out: onPreferenceChange() is called *before* the
            // Preference itself gets updated, so we need to use newValue here
            // rather than pref.getText().)
            pref.setTitle((String) newValue);

            return true;  // means it's OK to update the state of the Preference with the new value
        }

        @Override
        public boolean onOptionsItemSelected(MenuItem item) {
            final int itemId = item.getItemId();
            switch (itemId) {
                case android.R.id.home:
                    goUpToTopLevelSetting(this);
                    return true;
                case R.id.respond_via_message_reset:
                    // Reset the preferences settings
                    SharedPreferences prefs = getSharedPreferences(
                            SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE);
                    SharedPreferences.Editor editor = prefs.edit();
                    editor.remove(KEY_INSTANT_TEXT_DEFAULT_COMPONENT);
                    editor.apply();

                    return true;
                default:
            }
            return super.onOptionsItemSelected(item);
        }

        @Override
        public boolean onCreateOptionsMenu(Menu menu) {
            getMenuInflater().inflate(R.menu.respond_via_message_settings_menu, menu);
            return super.onCreateOptionsMenu(menu);
        }
    }

    /**
     * Finish current Activity and go up to the top level Settings.
     */
    public static void goUpToTopLevelSetting(Activity activity) {
        Intent intent = new Intent();
        try {
            intent.setClassName(
                    activity.createPackageContext("com.android.phone", 0),
                    "com.android.phone.CallFeaturesSetting");
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(RespondViaSmsSettings.class,
                    "Exception building package context com.android.phone", e);
            return;
        }
        intent.setAction(Intent.ACTION_MAIN);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        activity.startActivity(intent);
        activity.finish();
     }
}

/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.app.Activity;
import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.telecomm.PhoneAccountHandle;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class PhoneAccountPreferencesActivity extends Activity {

    private static final String KEY_DEFAULT_OUTGOING_ACCOUNT = "default_outgoing_account";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.phone_account_preferences);
    }

    public static class PreferencesFragment extends PreferenceFragment
            implements ListPreference.OnPreferenceChangeListener {
        private ListPreference mDefaultOutgoingAccount;
        private PhoneAccountRegistrar mRegistrar;
        private Map<String, PhoneAccountHandle> mAccountByValue = new HashMap<>();

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            addPreferencesFromResource(R.xml.phone_account_preferences);
            mDefaultOutgoingAccount = (ListPreference) findPreference(KEY_DEFAULT_OUTGOING_ACCOUNT);

            mRegistrar = TelecommApp.getInstance().getPhoneAccountRegistrar();
            List<PhoneAccountHandle> accountHandles = mRegistrar.getEnabledPhoneAccounts();
            PhoneAccountHandle currentDefault = mRegistrar.getDefaultOutgoingPhoneAccount();

            String[] entryValues = new String[accountHandles.size() + 1];
            String[] entries = new String[accountHandles.size() + 1];

            int selectedIndex = accountHandles.size();  // Points to "ask every time" by default
            int i = 0;
            for ( ; i < accountHandles.size(); i++) {
                entryValues[i] = Integer.toString(i);
                entries[i] = mRegistrar
                        .getPhoneAccountMetadata(accountHandles.get(i))
                        .getLabel();
                if (Objects.equals(currentDefault, accountHandles.get(i))) {
                    selectedIndex = i;
                }
                mAccountByValue.put(entryValues[i], accountHandles.get(i));
            }
            entryValues[i] = Integer.toString(i);
            entries[i] = getString(R.string.account_ask_every_time);
            mAccountByValue.put(entryValues[i], null);

            mDefaultOutgoingAccount.setEntryValues(entryValues);
            mDefaultOutgoingAccount.setEntries(entries);
            mDefaultOutgoingAccount.setValueIndex(selectedIndex);
            mDefaultOutgoingAccount.setOnPreferenceChangeListener(this);
        }

        @Override
        public boolean onPreferenceChange(Preference p, Object o) {
            if (p == mDefaultOutgoingAccount) {
                mRegistrar.setDefaultOutgoingPhoneAccount(mAccountByValue.get(o));
                return true;
            }
            return false;
        }
    }
}

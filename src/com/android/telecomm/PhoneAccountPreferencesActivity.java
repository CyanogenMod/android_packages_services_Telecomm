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
import android.preference.PreferenceFragment;
import android.telecomm.PhoneAccount;
import android.telecomm.PhoneAccountHandle;

import java.util.ArrayList;
import java.util.List;

public class PhoneAccountPreferencesActivity extends Activity {

    private static final String KEY_DEFAULT_OUTGOING_ACCOUNT = "default_outgoing_account";
    private static final String KEY_SIM_CALL_MANAGER_ACCOUNT = "sim_call_manager_account";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.phone_account_preferences);
    }

    public static class PreferencesFragment extends PreferenceFragment
            implements AccountSelectionPreference.AccountSelectionListener {
        private AccountSelectionPreference mDefaultOutgoingAccount;
        private AccountSelectionPreference mSimCallManagerAccount;

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            addPreferencesFromResource(R.xml.phone_account_preferences);

            mDefaultOutgoingAccount = (AccountSelectionPreference)
                    findPreference(KEY_DEFAULT_OUTGOING_ACCOUNT);
            mSimCallManagerAccount = (AccountSelectionPreference)
                    findPreference(KEY_SIM_CALL_MANAGER_ACCOUNT);

            PhoneAccountRegistrar registrar = TelecommApp.getInstance().getPhoneAccountRegistrar();

            mDefaultOutgoingAccount.setModel(
                    registrar,
                    registrar.getOutgoingPhoneAccounts(),
                    registrar.getUserSelectedOutgoingPhoneAccount(),
                    getString(R.string.account_ask_every_time));

            mSimCallManagerAccount.setModel(
                    registrar,
                    registrar.getAllConnectionManagerPhoneAccounts(),
                    registrar.getSimCallManager(),
                    getString(R.string.do_not_use_sim_call_manager));

            mDefaultOutgoingAccount.setListener(this);
            mSimCallManagerAccount.setListener(this);
        }

        @Override
        public boolean onAccountSelected(
                AccountSelectionPreference p, PhoneAccountHandle account) {
            PhoneAccountRegistrar registrar = TelecommApp.getInstance().getPhoneAccountRegistrar();
            if (p == mDefaultOutgoingAccount) {
                registrar.setDefaultOutgoingPhoneAccount(account);
                return true;
            } else if (p == mSimCallManagerAccount) {
                registrar.setSimCallManager(account);
                return true;
            }
            return false;
        }
    }
}

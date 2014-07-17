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

import android.content.ComponentName;
import android.content.Context;

import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.net.Uri;
import android.provider.Settings;
import android.telecomm.PhoneAccount;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Handles writing and reading PhoneAccount registration entries.
 * TODO(santoscordon): Replace this implementation with a proper database stored in a Telecomm
 * provider.
 */
final class PhoneAccountRegistrar {
    private static final int VERSION = 1;
    private static final String TELECOMM_PREFERENCES = "telecomm_prefs";
    private static final String PREFERENCE_PHONE_ACCOUNTS = "phone_accounts";

    private final Context mContext;

    private final class DeserializationToken {
        int currentIndex = 0;
        final String source;

        DeserializationToken(String source) {
            this.source = source;
        }
    }

    PhoneAccountRegistrar(Context context) {
        mContext = context;
    }

    /**
     * Adds a new phone account entry or updates an existing one.
     */
    boolean addAccount(PhoneAccount account) {
        List<PhoneAccount> allAccounts = getAllAccounts();
        // Should we implement an artificial limit for # of accounts associated with a single
        // ComponentName?
        allAccounts.add(account);

        // Search for duplicates and remove any that are found.
        for (int i = 0; i < allAccounts.size() - 1; i++) {
            if (account.equalsComponentAndId(allAccounts.get(i))) {
                // replace existing entry.
                allAccounts.remove(i);
                break;
            }
        }

        return writeAllAccounts(allAccounts);
    }

    /**
     * Removes an existing phone account entry.
     */
    boolean removeAccount(PhoneAccount account) {
        List<PhoneAccount> allAccounts = getAllAccounts();

        for (int i = 0; i < allAccounts.size(); i++) {
            if (account.equalsComponentAndId(allAccounts.get(i))) {
                allAccounts.remove(i);
                return writeAllAccounts(allAccounts);
            }
        }

        return false;
    }

    /**
     * Returns a list of all accounts which the user has enabled.
     */
    List<PhoneAccount> getEnabledAccounts() {
        List<PhoneAccount> allAccounts = getAllAccounts();
        // TODO: filter list
        return allAccounts;
    }

    /**
     * Returns the list of all accounts registered with the system, whether or not the user
     * has explicitly enabled them.
     */
    List<PhoneAccount> getAllAccounts() {
        String value = getPreferences().getString(PREFERENCE_PHONE_ACCOUNTS, null);
        return deserializeAllAccounts(value);
    }

    /**
     * Returns the registered version of the account matching the component name and ID of the
     * specified account.
     */
    PhoneAccount getRegisteredAccount(PhoneAccount account) {
        for (PhoneAccount registeredAccount : getAllAccounts()) {
            if (registeredAccount.equalsComponentAndId(account)) {
                return registeredAccount;
            }
        }
        return null;
    }

    /**
     * Replaces the contents of our list of accounts with this new list.
     */
    private boolean writeAllAccounts(List<PhoneAccount> allAccounts) {
        Editor editor = getPreferences().edit();
        editor.putString(PREFERENCE_PHONE_ACCOUNTS, serializeAllAccounts(allAccounts));
        return editor.commit();
    }

    // Serialization implementation
    // Serializes all strings into the format "len:string-value"
    // Example, we will serialize the following PhoneAccount.
    //   PhoneAccount
    //     ComponentName: "abc"
    //     Id:            "def"
    //     Handle:        "555"
    //     Capabilities:  1
    //
    //  Each value serializes into (spaces added for readability)
    //    3:abc 3:def 3:555 1:1
    //
    //  Two identical accounts would likewise be serialized as a list of strings with a prepended
    //  size of 2.
    //    1:2 3:abc 3:def 3:555 1:1 3:abc 3:def 3:555 1:1
    //
    //  The final result with a prepended version ("1:1") would be:
    //    "1:11:23:abc3:def3:5551:13:abc3:def3:5551:1"

    private String serializeAllAccounts(List<PhoneAccount> allAccounts) {
        StringBuilder buffer = new StringBuilder();

        // Version
        serializeIntValue(VERSION, buffer);

        // Number of accounts
        serializeIntValue(allAccounts.size(), buffer);

        // The actual accounts
        for (int i = 0; i < allAccounts.size(); i++) {
            PhoneAccount account = allAccounts.get(i);
            serializeStringValue(account.getComponentName().flattenToShortString(), buffer);
            serializeStringValue(account.getId(), buffer);
            serializeStringValue(account.getHandle().toString(), buffer);
            serializeIntValue(account.getCapabilities(), buffer);
        }

        return buffer.toString();
    }

    private List<PhoneAccount> deserializeAllAccounts(String source) {
        List<PhoneAccount> accounts = new ArrayList<PhoneAccount>();

        if (source != null) {
            DeserializationToken token = new DeserializationToken(source);
            int version = deserializeIntValue(token);
            if (version == 1) {
                int size = deserializeIntValue(token);

                for (int i = 0; i < size; i++) {
                    String strComponentName = deserializeStringValue(token);
                    String strId = deserializeStringValue(token);
                    String strHandle = deserializeStringValue(token);
                    int capabilities = deserializeIntValue(token);

                    accounts.add(new PhoneAccount(
                            ComponentName.unflattenFromString(strComponentName),
                            strId,
                            Uri.parse(strHandle),
                            capabilities));
                }
            }
        }

        return accounts;
    }

    private void serializeIntValue(int value, StringBuilder buffer) {
        serializeStringValue(String.valueOf(value), buffer);
    }

    private void serializeStringValue(String value, StringBuilder buffer) {
        buffer.append(value.length()).append(":").append(value);
    }

    private int deserializeIntValue(DeserializationToken token) {
        return Integer.parseInt(deserializeStringValue(token));
    }

    private String deserializeStringValue(DeserializationToken token) {
        int colonIndex = token.source.indexOf(':', token.currentIndex);
        int valueLength = Integer.parseInt(token.source.substring(token.currentIndex, colonIndex));
        int endIndex = colonIndex + 1 + valueLength;
        token.currentIndex = endIndex;
        return token.source.substring(colonIndex + 1, endIndex);
    }

    private SharedPreferences getPreferences() {
        return mContext.getSharedPreferences(TELECOMM_PREFERENCES, Context.MODE_PRIVATE);
    }
}

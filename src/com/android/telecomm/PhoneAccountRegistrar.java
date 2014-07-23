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

import android.telecomm.PhoneAccount;
import android.telecomm.PhoneAccountHandle;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import android.content.ComponentName;
import android.content.Context;

import android.content.SharedPreferences;
import android.net.Uri;
import android.telecomm.TelecommManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Handles writing and reading PhoneAccountHandle registration entries. This is a simple verbatim
 * delegate for all the account handling methods on {@link TelecommManager} as implemented in
 * {@link TelecommServiceImpl}, with the notable exception that {@link TelecommServiceImpl} is
 * responsible for security checking to make sure that the caller has proper authority over
 * the {@code ComponentName}s they are declaring in their {@code PhoneAccountHandle}s.
 *
 * TODO(santoscordon): Replace this implementation with a proper database stored in a Telecomm
 * provider.
 */
final class PhoneAccountRegistrar {
    private static final String TELECOMM_PREFERENCES = "telecomm_prefs";
    private static final String PREFERENCE_PHONE_ACCOUNTS = "phone_accounts";

    private final Context mContext;

    PhoneAccountRegistrar(Context context) {
        mContext = context;
    }

    public PhoneAccountHandle getDefaultOutgoingPhoneAccount() {
        State s = read();

        if (s.defaultOutgoingHandle != null) {
            // Return the registered outgoing default iff it still exists (we keep a sticky
            // default to survive account deletion and re-addition)
            for (int i = 0; i < s.accounts.size(); i++) {
                if (s.accounts.get(i).getAccountHandle().equals(s.defaultOutgoingHandle)) {
                    return s.defaultOutgoingHandle;
                }
            }
            // At this point, there was a registered default but it has been deleted; remember
            // it for the future, but return null from this method
            return null;
        } else {
            List<PhoneAccountHandle> enabled = getEnabledPhoneAccounts();
            switch (enabled.size()) {
                case 0:
                    // There are no accounts, so there can be no default
                    return null;
                case 1:
                    // There is only one account, which is by definition the default
                    return enabled.get(0);
                default:
                    // There are multiple accounts with no selected default
                    return null;
            }
        }
    }

    public void setDefaultOutgoingPhoneAccount(PhoneAccountHandle accountHandle) {
        State s = read();

        if (accountHandle == null) {
            // Asking to clear the default outgoing is a valid request
            s.defaultOutgoingHandle = null;
        } else {
            boolean found = false;
            for (PhoneAccount m : s.accounts) {
                if (Objects.equals(accountHandle, m.getAccountHandle())) {
                    found = true;
                    break;
                }
            }

            if (!found) {
                Log.w(this, "Trying to set nonexistent default outgoing phone accountHandle %s",
                        accountHandle);
                return;
            }

            s.defaultOutgoingHandle = accountHandle;
        }

        write(s);
    }

    public List<PhoneAccountHandle> getEnabledPhoneAccounts() {
        State s = read();
        return simSubscriptionAccountHandles(s);
    }

    public PhoneAccount getPhoneAccount(PhoneAccountHandle accountHandle) {
        State s = read();
        for (PhoneAccount m : s.accounts) {
            if (Objects.equals(accountHandle, m.getAccountHandle())) {
                return m;
            }
        }
        return null;
    }

    // TODO: Should we implement an artificial limit for # of accounts associated with a single
    // ComponentName?
    public void registerPhoneAccount(PhoneAccount metadata) {
        State s = read();

        s.accounts.add(metadata);
        // Search for duplicates and remove any that are found.
        for (int i = 0; i < s.accounts.size() - 1; i++) {
            if (Objects.equals(metadata.getAccountHandle(), s.accounts.get(i).getAccountHandle())) {
                // replace existing entry.
                s.accounts.remove(i);
                break;
            }
        }

        write(s);
    }

    public void unregisterPhoneAccount(PhoneAccountHandle accountHandle) {
        State s = read();

        for (int i = 0; i < s.accounts.size(); i++) {
            if (Objects.equals(accountHandle, s.accounts.get(i).getAccountHandle())) {
                s.accounts.remove(i);
                break;
            }
        }

        write(s);
    }

    public void clearAccounts(String packageName) {
        State s = read();

        for (int i = 0; i < s.accounts.size(); i++) {
            if (Objects.equals(
                    packageName,
                    s.accounts.get(i).getAccountHandle().getComponentName().getPackageName())) {
                s.accounts.remove(i);
            }
        }

        write(s);
    }

    private List<PhoneAccountHandle> simSubscriptionAccountHandles(State s) {
        List<PhoneAccountHandle> accountHandles = new ArrayList<>();
        for (PhoneAccount m : s.accounts) {
            if ((m.getCapabilities() & PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION) != 0) {
                accountHandles.add(m.getAccountHandle());
            }
        }
        return accountHandles;
    }

    private State read() {
        try {
            String serialized = getPreferences().getString(PREFERENCE_PHONE_ACCOUNTS, null);
            Log.d(this, "read() obtained serialized state: %s", serialized);
            State state = serialized == null
                    ? new State()
                    : deserializeState(serialized);
            Log.d(this, "read() obtained state: %s", state);
            return state;
        } catch (JSONException e) {
            Log.e(this, e, "read");
            return new State();
        }
    }

    private boolean write(State state) {
        try {
            Log.d(this, "write() writing state: %s", state);
            String serialized = serializeState(state);
            Log.d(this, "write() writing serialized state: %s", serialized);
            boolean success = getPreferences()
                    .edit()
                    .putString(PREFERENCE_PHONE_ACCOUNTS, serialized)
                    .commit();
            Log.d(this, "serialized state was written with succcess = %b", success);
            return success;
        } catch (JSONException e) {
            Log.e(this, e, "write");
            return false;
        }
    }

    private SharedPreferences getPreferences() {
        return mContext.getSharedPreferences(TELECOMM_PREFERENCES, Context.MODE_PRIVATE);
    }

    private String serializeState(State s) throws JSONException {
        // TODO: If this is used in production, remove the indent (=> do not pretty print)
        return sStateJson.toJson(s).toString(2);
    }

    private State deserializeState(String s) throws JSONException {
        return sStateJson.fromJson(new JSONObject(new JSONTokener(s)));
    }

    private static class State {
        public PhoneAccountHandle defaultOutgoingHandle = null;
        public final List<PhoneAccount> accounts = new ArrayList<>();
    }

    //
    // JSON serialization
    //

    private interface Json<T> {
        JSONObject toJson(T o) throws JSONException;
        T fromJson(JSONObject json) throws JSONException;
    }

    private static final Json<State> sStateJson =
            new Json<State>() {
        private static final String DEFAULT_OUTGOING = "default_outgoing";
        private static final String ACCOUNTS = "accounts";

        @Override
        public JSONObject toJson(State o) throws JSONException {
            JSONObject json = new JSONObject();
            if (o.defaultOutgoingHandle != null) {
                json.put(DEFAULT_OUTGOING, sPhoneAccountJson.toJson(o.defaultOutgoingHandle));
            }
            JSONArray accounts = new JSONArray();
            for (PhoneAccount m : o.accounts) {
                accounts.put(sPhoneAccountMetadataJson.toJson(m));
            }
            json.put(ACCOUNTS, accounts);
            return json;
        }

        @Override
        public State fromJson(JSONObject json) throws JSONException {
            State s = new State();
            if (json.has(DEFAULT_OUTGOING)) {
                s.defaultOutgoingHandle = sPhoneAccountJson.fromJson(
                        (JSONObject) json.get(DEFAULT_OUTGOING));
            }
            if (json.has(ACCOUNTS)) {
                JSONArray accounts = (JSONArray) json.get(ACCOUNTS);
                for (int i = 0; i < accounts.length(); i++) {
                    try {
                        s.accounts.add(sPhoneAccountMetadataJson.fromJson(
                                (JSONObject) accounts.get(i)));
                    } catch (Exception e) {
                        Log.e(this, e, "Extracting phone account");
                    }
                }
            }
            return s;
        }
    };

    private static final Json<PhoneAccount> sPhoneAccountMetadataJson =
            new Json<PhoneAccount>() {
        private static final String ACCOUNT = "account";
        private static final String HANDLE = "handle";
        private static final String SUBSCRIPTION_NUMBER = "subscription_number";
        private static final String CAPABILITIES = "capabilities";
        private static final String ICON_RES_ID = "icon_res_id";
        private static final String LABEL = "label";
        private static final String SHORT_DESCRIPTION = "short_description";
        private static final String VIDEO_CALLING_SUPPORTED = "video_calling_supported";

        @Override
        public JSONObject toJson(PhoneAccount o) throws JSONException {
            return new JSONObject()
                    .put(ACCOUNT, sPhoneAccountJson.toJson(o.getAccountHandle()))
                    .put(HANDLE, o.getHandle().toString())
                    .put(SUBSCRIPTION_NUMBER, o.getSubscriptionNumber())
                    .put(CAPABILITIES, o.getCapabilities())
                    .put(ICON_RES_ID, o.getIconResId())
                    .put(LABEL, o.getLabel())
                    .put(SHORT_DESCRIPTION, o.getShortDescription())
                    .put(VIDEO_CALLING_SUPPORTED, (Boolean) o.isVideoCallingSupported());
        }

        @Override
        public PhoneAccount fromJson(JSONObject json) throws JSONException {
            return new PhoneAccount(
                    sPhoneAccountJson.fromJson((JSONObject) json.get(ACCOUNT)),
                    Uri.parse((String) json.get(HANDLE)),
                    (String) json.get(SUBSCRIPTION_NUMBER),
                    (int) json.get(CAPABILITIES),
                    (int) json.get(ICON_RES_ID),
                    (String) json.get(LABEL),
                    (String) json.get(SHORT_DESCRIPTION),
                    (Boolean) json.get(VIDEO_CALLING_SUPPORTED));
        }
    };

    private static final Json<PhoneAccountHandle> sPhoneAccountJson =
            new Json<PhoneAccountHandle>() {
        private static final String COMPONENT_NAME = "component_name";
        private static final String ID = "id";

        @Override
        public JSONObject toJson(PhoneAccountHandle o) throws JSONException {
            return new JSONObject()
                    .put(COMPONENT_NAME, o.getComponentName().flattenToString())
                    .put(ID, o.getId());
        }

        @Override
        public PhoneAccountHandle fromJson(JSONObject json) throws JSONException {
            return new PhoneAccountHandle(
                    ComponentName.unflattenFromString((String) json.get(COMPONENT_NAME)),
                    (String) json.get(ID));
        }
    };
}

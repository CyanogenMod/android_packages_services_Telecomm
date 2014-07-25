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
    private final State mState;

    PhoneAccountRegistrar(Context context) {
        mContext = context;
        mState = readState();
    }

    public PhoneAccountHandle getDefaultOutgoingPhoneAccount() {
        if (mState.defaultOutgoing != null) {
            // Return the registered outgoing default iff it still exists (we keep a sticky
            // default to survive account deletion and re-addition)
            for (int i = 0; i < mState.accounts.size(); i++) {
                if (mState.accounts.get(i).getAccountHandle().equals(mState.defaultOutgoing)) {
                    return mState.defaultOutgoing;
                }
            }
            // At this point, there was a registered default but it has been deleted; proceed
            // as though there were no default
        }

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

    public void setDefaultOutgoingPhoneAccount(PhoneAccountHandle accountHandle) {
        if (accountHandle == null) {
            // Asking to clear the default outgoing is a valid request
            mState.defaultOutgoing = null;
        } else {
            boolean found = false;
            for (PhoneAccount m : mState.accounts) {
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

            mState.defaultOutgoing = accountHandle;
        }

        write();
    }

    public void setSimCallManager(PhoneAccountHandle callManager) {
        if (callManager != null) {
            PhoneAccount callManagerAccount = getPhoneAccount(callManager);
            if (callManagerAccount == null) {
                Log.d(this, "setSimCallManager: Nonexistent call manager: %s", callManager);
                return;
            } else if (!has(callManagerAccount, PhoneAccount.CAPABILITY_SIM_CALL_MANAGER)) {
                Log.d(this, "setSimCallManager: Not a call manager: %s", callManagerAccount);
                return;
            }
        }
        mState.simCallManager = callManager;
        write();
    }

    public PhoneAccountHandle getSimCallManager() {
        return mState.simCallManager;
    }

    public List<PhoneAccountHandle> getAllPhoneAccountHandles() {
        List<PhoneAccountHandle> accountHandles = new ArrayList<>();
        for (PhoneAccount m : mState.accounts) {
            accountHandles.add(m.getAccountHandle());
        }
        return accountHandles;
    }

    public List<PhoneAccount> getAllPhoneAccounts() {
        return new ArrayList<>(mState.accounts);
    }

    // TODO: Rename systemwide to "getCallProviderPhoneAccounts"?
    public List<PhoneAccountHandle> getEnabledPhoneAccounts() {
        return getCallProviderAccountHandles();
    }

    public PhoneAccount getPhoneAccount(PhoneAccountHandle handle) {
        for (PhoneAccount m : mState.accounts) {
            if (Objects.equals(handle, m.getAccountHandle())) {
                return m;
            }
        }
        return null;
    }

    // TODO: Should we implement an artificial limit for # of accounts associated with a single
    // ComponentName?
    public void registerPhoneAccount(PhoneAccount account) {
        account = hackFixBabelAccount(account);
        mState.accounts.add(account);
        // Search for duplicates and remove any that are found.
        for (int i = 0; i < mState.accounts.size() - 1; i++) {
            if (Objects.equals(
                    account.getAccountHandle(), mState.accounts.get(i).getAccountHandle())) {
                // replace existing entry.
                mState.accounts.remove(i);
                break;
            }
        }

        write();
    }

    // STOPSHIP: Hack to edit the account registered by Babel so it shows up properly
    private PhoneAccount hackFixBabelAccount(PhoneAccount account) {
        String pkg = account.getAccountHandle().getComponentName().getPackageName();
        return "com.google.android.talk".equals(pkg)
                ? new PhoneAccount(
                        account.getAccountHandle(),
                        account.getHandle(),
                        account.getSubscriptionNumber(),
                        PhoneAccount.CAPABILITY_SIM_CALL_MANAGER,
                        account.getIconResId(),
                        account.getLabel(),
                        account.getShortDescription(),
                        account.isVideoCallingSupported())
                : account;
    }

    public void unregisterPhoneAccount(PhoneAccountHandle accountHandle) {
        for (int i = 0; i < mState.accounts.size(); i++) {
            if (Objects.equals(accountHandle, mState.accounts.get(i).getAccountHandle())) {
                mState.accounts.remove(i);
                break;
            }
        }

        write();
    }

    public void clearAccounts(String packageName) {
        for (int i = 0; i < mState.accounts.size(); i++) {
            if (Objects.equals(
                    packageName,
                    mState.accounts.get(i).getAccountHandle()
                            .getComponentName().getPackageName())) {
                mState.accounts.remove(i);
            }
        }

        write();
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////

    // TODO: Add a corresponding has(...) method to class PhoneAccount itself and remove this one
    // Return true iff the given account has all the specified capability flags
    static boolean has(PhoneAccount account, int capability) {
        return (account.getCapabilities() & capability) == capability;
    }

    private List<PhoneAccountHandle> getCallProviderAccountHandles() {
        List<PhoneAccountHandle> accountHandles = new ArrayList<>();
        for (PhoneAccount m : mState.accounts) {
            if (has(m, PhoneAccount.CAPABILITY_CALL_PROVIDER)) {
                accountHandles.add(m.getAccountHandle());
            }
        }
        return accountHandles;
    }

    /**
     * The state of this {@code PhoneAccountRegistrar}.
     */
    private static class State {
        /**
         * The account selected by the user to be employed by default for making outgoing calls.
         * If the user has not made such a selection, then this is null.
         */
        public PhoneAccountHandle defaultOutgoing = null;

        /**
         * A {@code PhoneAccount} having {@link PhoneAccount#CAPABILITY_SIM_CALL_MANAGER} which
         * manages and optimizes a user's PSTN SIM connections.
         */
        public PhoneAccountHandle simCallManager;

        /**
         * The complete list of {@code PhoneAccount}s known to the Telecomm subsystem.
         */
        public final List<PhoneAccount> accounts = new ArrayList<>();
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    //
    // State management
    //

    private void write() {
        writeState(mState);
    }

    private State readState() {
        try {
            String serialized = getPreferences().getString(PREFERENCE_PHONE_ACCOUNTS, null);
            Log.v(this, "read() obtained serialized state: %s", serialized);
            State state = serialized == null
                    ? new State()
                    : deserializeState(serialized);
            Log.v(this, "read() obtained state: %s", state);
            return state;
        } catch (JSONException e) {
            Log.e(this, e, "read");
            return new State();
        }
    }

    private boolean writeState(State state) {
        try {
            Log.v(this, "write() writing state: %s", state);
            String serialized = serializeState(state);
            Log.v(this, "write() writing serialized state: %s", serialized);
            boolean success = getPreferences()
                    .edit()
                    .putString(PREFERENCE_PHONE_ACCOUNTS, serialized)
                    .commit();
            Log.v(this, "serialized state was written with success = %b", success);
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

    ////////////////////////////////////////////////////////////////////////////////////////////////
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
        private static final String SIM_CALL_MANAGER = "sim_call_manager";
        private static final String ACCOUNTS = "accounts";

        @Override
        public JSONObject toJson(State o) throws JSONException {
            JSONObject json = new JSONObject();
            if (o.defaultOutgoing != null) {
                json.put(DEFAULT_OUTGOING, sPhoneAccountHandleJson.toJson(o.defaultOutgoing));
            }
            if (o.simCallManager != null) {
                json.put(SIM_CALL_MANAGER, sPhoneAccountHandleJson.toJson(o.simCallManager));
            }
            JSONArray accounts = new JSONArray();
            for (PhoneAccount m : o.accounts) {
                accounts.put(sPhoneAccountJson.toJson(m));
            }
            json.put(ACCOUNTS, accounts);
            return json;
        }

        @Override
        public State fromJson(JSONObject json) throws JSONException {
            State s = new State();
            if (json.has(DEFAULT_OUTGOING)) {
                try {
                    s.defaultOutgoing = sPhoneAccountHandleJson.fromJson(
                            (JSONObject) json.get(DEFAULT_OUTGOING));
                } catch (Exception e) {
                    Log.e(this, e, "Extracting PhoneAccountHandle");
                }
            }
            if (json.has(SIM_CALL_MANAGER)) {
                try {
                    s.simCallManager = sPhoneAccountHandleJson.fromJson(
                            (JSONObject) json.get(SIM_CALL_MANAGER));
                } catch (Exception e) {
                    Log.e(this, e, "Extracting PhoneAccountHandle");
                }
            }
            if (json.has(ACCOUNTS)) {
                JSONArray accounts = (JSONArray) json.get(ACCOUNTS);
                for (int i = 0; i < accounts.length(); i++) {
                    try {
                        s.accounts.add(sPhoneAccountJson.fromJson(
                                (JSONObject) accounts.get(i)));
                    } catch (Exception e) {
                        Log.e(this, e, "Extracting phone account");
                    }
                }
            }
            return s;
        }
    };

    private static final Json<PhoneAccount> sPhoneAccountJson =
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
                    .put(ACCOUNT, sPhoneAccountHandleJson.toJson(o.getAccountHandle()))
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
                    sPhoneAccountHandleJson.fromJson((JSONObject) json.get(ACCOUNT)),
                    Uri.parse((String) json.get(HANDLE)),
                    (String) json.get(SUBSCRIPTION_NUMBER),
                    (int) json.get(CAPABILITIES),
                    (int) json.get(ICON_RES_ID),
                    (String) json.get(LABEL),
                    (String) json.get(SHORT_DESCRIPTION),
                    (Boolean) json.get(VIDEO_CALLING_SUPPORTED));
        }
    };

    private static final Json<PhoneAccountHandle> sPhoneAccountHandleJson =
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

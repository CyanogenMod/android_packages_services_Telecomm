/*
 * Copyright 2014, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.telecom;

import android.telecom.ParcelableConnection;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telephony.DisconnectCause;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * This class creates connections to place new outgoing calls to attached to an existing incoming
 * call. In either case, this class cycles through a set of connection services until:
 *   - a connection service returns a newly created connection in which case the call is displayed
 *     to the user
 *   - a connection service cancels the process, in which case the call is aborted
 */
final class CreateConnectionProcessor {

    // Describes information required to attempt to make a phone call
    private static class CallAttemptRecord {
        // The PhoneAccount describing the target connection service which we will
        // contact in order to process an attempt
        public final PhoneAccountHandle connectionManagerPhoneAccount;
        // The PhoneAccount which we will tell the target connection service to use
        // for attempting to make the actual phone call
        public final PhoneAccountHandle targetPhoneAccount;

        public CallAttemptRecord(
                PhoneAccountHandle connectionManagerPhoneAccount,
                PhoneAccountHandle targetPhoneAccount) {
            this.connectionManagerPhoneAccount = connectionManagerPhoneAccount;
            this.targetPhoneAccount = targetPhoneAccount;
        }

        @Override
        public String toString() {
            return "CallAttemptRecord("
                    + Objects.toString(connectionManagerPhoneAccount) + ","
                    + Objects.toString(targetPhoneAccount) + ")";
        }

        /**
         * Determines if this instance of {@code CallAttemptRecord} has the same underlying
         * {@code PhoneAccountHandle}s as another instance.
         *
         * @param obj The other instance to compare against.
         * @return {@code True} if the {@code CallAttemptRecord}s are equal.
         */
        @Override
        public boolean equals(Object obj) {
            if (obj instanceof CallAttemptRecord) {
                CallAttemptRecord other = (CallAttemptRecord) obj;
                return Objects.equals(connectionManagerPhoneAccount,
                        other.connectionManagerPhoneAccount) &&
                        Objects.equals(targetPhoneAccount, other.targetPhoneAccount);
            }
            return false;
        }
    }

    private final Call mCall;
    private final ConnectionServiceRepository mRepository;
    private List<CallAttemptRecord> mAttemptRecords;
    private Iterator<CallAttemptRecord> mAttemptRecordIterator;
    private CreateConnectionResponse mResponse;
    private int mLastErrorCode = DisconnectCause.OUTGOING_FAILURE;
    private String mLastErrorMsg;

    CreateConnectionProcessor(
            Call call, ConnectionServiceRepository repository, CreateConnectionResponse response) {
        mCall = call;
        mRepository = repository;
        mResponse = response;
    }

    void process() {
        Log.v(this, "process");
        mAttemptRecords = new ArrayList<>();
        if (mCall.getTargetPhoneAccount() != null) {
            mAttemptRecords.add(new CallAttemptRecord(
                    mCall.getTargetPhoneAccount(), mCall.getTargetPhoneAccount()));
        }
        adjustAttemptsForConnectionManager();
        adjustAttemptsForEmergency();
        mAttemptRecordIterator = mAttemptRecords.iterator();
        attemptNextPhoneAccount();
    }

    void abort() {
        Log.v(this, "abort");

        // Clear the response first to prevent attemptNextConnectionService from attempting any
        // more services.
        CreateConnectionResponse response = mResponse;
        mResponse = null;

        ConnectionServiceWrapper service = mCall.getConnectionService();
        if (service != null) {
            service.abort(mCall);
            mCall.clearConnectionService();
        }
        if (response != null) {
            response.handleCreateConnectionFailure(DisconnectCause.OUTGOING_CANCELED, null);
        }
    }

    private void attemptNextPhoneAccount() {
        Log.v(this, "attemptNextPhoneAccount");
        PhoneAccountRegistrar registrar = TelecomApp.getInstance().getPhoneAccountRegistrar();
        CallAttemptRecord attempt = null;
        if (mAttemptRecordIterator.hasNext()) {
            attempt = mAttemptRecordIterator.next();

            if (!registrar.phoneAccountHasPermission(attempt.connectionManagerPhoneAccount)) {
                Log.w(this,
                        "Connection mgr does not have BIND_CONNECTION_SERVICE for attempt: %s",
                        attempt);
                attemptNextPhoneAccount();
                return;
            }

            // If the target PhoneAccount differs from the ConnectionManager phone acount, ensure it
            // also has BIND_CONNECTION_SERVICE permission.
            if (!attempt.connectionManagerPhoneAccount.equals(attempt.targetPhoneAccount) &&
                    !registrar.phoneAccountHasPermission(attempt.targetPhoneAccount)) {
                Log.w(this,
                        "Target PhoneAccount does not have BIND_CONNECTION_SERVICE for attempt: %s",
                        attempt);
                attemptNextPhoneAccount();
                return;
            }
        }

        if (mResponse != null && attempt != null) {
            Log.i(this, "Trying attempt %s", attempt);
            ConnectionServiceWrapper service =
                    mRepository.getService(
                            attempt.connectionManagerPhoneAccount.getComponentName());
            if (service == null) {
                Log.i(this, "Found no connection service for attempt %s", attempt);
                attemptNextPhoneAccount();
            } else {
                mCall.setConnectionManagerPhoneAccount(attempt.connectionManagerPhoneAccount);
                mCall.setTargetPhoneAccount(attempt.targetPhoneAccount);
                mCall.setConnectionService(service);
                Log.i(this, "Attempting to call from %s", service.getComponentName());
                service.createConnection(mCall, new Response(service));
            }
        } else {
            Log.v(this, "attemptNextPhoneAccount, no more accounts, failing");
            if (mResponse != null) {
                mResponse.handleCreateConnectionFailure(mLastErrorCode, mLastErrorMsg);
                mResponse = null;
                mCall.clearConnectionService();
            }
        }
    }

    private boolean shouldSetConnectionManager() {
        if (mAttemptRecords.size() == 0) {
            return false;
        }

        if (mAttemptRecords.size() > 1) {
            Log.d(this, "shouldSetConnectionManager, error, mAttemptRecords should not have more "
                    + "than 1 record");
            return false;
        }

        PhoneAccountRegistrar registrar = TelecomApp.getInstance().getPhoneAccountRegistrar();
        PhoneAccountHandle connectionManager = registrar.getSimCallManager();
        if (connectionManager == null) {
            return false;
        }

        PhoneAccountHandle targetPhoneAccountHandle = mAttemptRecords.get(0).targetPhoneAccount;
        if (Objects.equals(connectionManager, targetPhoneAccountHandle)) {
            return false;
        }

        // Connection managers are only allowed to manage SIM subscriptions.
        PhoneAccount targetPhoneAccount = registrar.getPhoneAccount(targetPhoneAccountHandle);
        boolean isSimSubscription = (targetPhoneAccount.getCapabilities() &
                PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION) != 0;
        if (!isSimSubscription) {
            return false;
        }

        return true;
    }

    // If there exists a registered connection manager then use it.
    private void adjustAttemptsForConnectionManager() {
        if (shouldSetConnectionManager()) {
            CallAttemptRecord record = new CallAttemptRecord(
                    TelecomApp.getInstance().getPhoneAccountRegistrar().getSimCallManager(),
                    mAttemptRecords.get(0).targetPhoneAccount);
            Log.v(this, "setConnectionManager, changing %s -> %s",
                    mAttemptRecords.get(0).targetPhoneAccount, record);
            mAttemptRecords.set(0, record);
        } else {
            Log.v(this, "setConnectionManager, not changing");
        }
    }

    // If we are possibly attempting to call a local emergency number, ensure that the
    // plain PSTN connection services are listed, and nothing else.
    private void adjustAttemptsForEmergency()  {
        if (TelephonyUtil.shouldProcessAsEmergency(TelecomApp.getInstance(), mCall.getHandle())) {
            Log.i(this, "Emergency number detected");
            mAttemptRecords.clear();
            List<PhoneAccount> allAccounts = TelecomApp.getInstance()
                    .getPhoneAccountRegistrar().getAllPhoneAccounts();
            // First, add SIM phone accounts which can place emergency calls.
            for (PhoneAccount phoneAccount : allAccounts) {
                if (phoneAccount.hasCapabilities(PhoneAccount.CAPABILITY_PLACE_EMERGENCY_CALLS) &&
                        phoneAccount.hasCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION)) {
                    Log.i(this, "Will try PSTN account %s for emergency",
                            phoneAccount.getAccountHandle());
                    mAttemptRecords.add(
                            new CallAttemptRecord(
                                    phoneAccount.getAccountHandle(),
                                    phoneAccount.getAccountHandle()));
                }
            }

            // Next, add the connection manager account as a backup if it can place emergency calls.
            PhoneAccountHandle callManagerHandle = TelecomApp.getInstance()
                    .getPhoneAccountRegistrar().getSimCallManager();
            if (callManagerHandle != null) {
                PhoneAccount callManager = TelecomApp.getInstance()
                        .getPhoneAccountRegistrar().getPhoneAccount(callManagerHandle);
                if (callManager.hasCapabilities(PhoneAccount.CAPABILITY_PLACE_EMERGENCY_CALLS)) {
                    CallAttemptRecord callAttemptRecord = new CallAttemptRecord(callManagerHandle,
                            TelecomApp.getInstance().getPhoneAccountRegistrar().
                                    getDefaultOutgoingPhoneAccount(mCall.getHandle().getScheme())
                    );

                    if (!mAttemptRecords.contains(callAttemptRecord)) {
                        Log.i(this, "Will try Connection Manager account %s for emergency",
                                callManager);
                        mAttemptRecords.add(callAttemptRecord);
                    }
                }
            }
        }
    }

    private class Response implements CreateConnectionResponse {
        private final ConnectionServiceWrapper mService;

        Response(ConnectionServiceWrapper service) {
            mService = service;
        }

        @Override
        public void handleCreateConnectionSuccess(
                CallIdMapper idMapper,
                ParcelableConnection connection) {
            if (mResponse == null) {
                // Nobody is listening for this connection attempt any longer; ask the responsible
                // ConnectionService to tear down any resources associated with the call
                mService.abort(mCall);
            } else {
                // Success -- share the good news and remember that we are no longer interested
                // in hearing about any more attempts
                mResponse.handleCreateConnectionSuccess(idMapper, connection);
                mResponse = null;
            }
        }

        @Override
        public void handleCreateConnectionFailure(int code, String msg) {
            // Failure of some sort; record the reasons for failure and try again if possible
            Log.d(CreateConnectionProcessor.this, "Connection failed: %d (%s)", code, msg);
            mLastErrorCode = code;
            mLastErrorMsg = msg;
            attemptNextPhoneAccount();
        }
    }
}

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

package com.android.telecomm;

import android.telecomm.PhoneAccountHandle;
import android.telephony.DisconnectCause;
import android.telecomm.ConnectionRequest;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * This class creates connections to place new outgoing calls to attached to an existing incoming
 * call. In either case, this class cycles through a set of connection services until:
 *   - a connection service returns a newly created connection in which case the call is displayed
 *     to the user
 *   - a connection service cancels the process, in which case the call is aborted
 */
final class CreateConnectionProcessor {
    private final Call mCall;
    private final ConnectionServiceRepository mRepository;
    private List<PhoneAccountHandle> mPhoneAccountHandles;
    private Iterator<PhoneAccountHandle> mPhoneAccountHandleIterator;
    private CreateConnectionResponse mResponse;
    private int mLastErrorCode = DisconnectCause.ERROR_UNSPECIFIED;
    private String mLastErrorMsg;

    CreateConnectionProcessor(
            Call call, ConnectionServiceRepository repository, CreateConnectionResponse response) {
        mCall = call;
        mRepository = repository;
        mResponse = response;
    }

    void process() {
        Log.v(this, "process");
        mPhoneAccountHandles = new ArrayList<>();
        if (mCall.getPhoneAccount() != null) {
            mPhoneAccountHandles.add(mCall.getPhoneAccount());
        }
        adjustPhoneAccountsForEmergency();
        mPhoneAccountHandleIterator = mPhoneAccountHandles.iterator();
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
            response.handleCreateConnectionCancelled();
        }
    }

    private void attemptNextPhoneAccount() {
        Log.v(this, "attemptNextPhoneAccount");

        if (mResponse != null && mPhoneAccountHandleIterator.hasNext()) {
            PhoneAccountHandle accountHandle = mPhoneAccountHandleIterator.next();
            Log.i(this, "Trying accountHandle %s", accountHandle);
            ConnectionServiceWrapper service =
                    mRepository.getService(accountHandle.getComponentName());
            if (service == null) {
                Log.i(this, "Found no connection service for accountHandle %s", accountHandle);
                attemptNextPhoneAccount();
            } else {
                mCall.setPhoneAccount(accountHandle);
                mCall.setConnectionService(service);
                Log.i(this, "Attempting to call from %s", service.getComponentName());
                service.createConnection(mCall, new Response(service));
            }
        } else {
            Log.v(this, "attemptNextPhoneAccount, no more accounts, failing");
            if (mResponse != null) {
                mResponse.handleCreateConnectionFailed(mLastErrorCode, mLastErrorMsg);
                mResponse = null;
                mCall.clearConnectionService();
            }
        }
    }

    // If we are possibly attempting to call a local emergency number, ensure that the
    // plain PSTN connection services are listed, and nothing else.
    private void adjustPhoneAccountsForEmergency()  {
        if (TelephonyUtil.shouldProcessAsEmergency(TelecommApp.getInstance(), mCall.getHandle())) {
            Log.i(this, "Emergency number detected");
            mPhoneAccountHandles.clear();
            List<PhoneAccountHandle> allAccountHandles = TelecommApp.getInstance()
                    .getPhoneAccountRegistrar().getEnabledPhoneAccounts();
            for (int i = 0; i < allAccountHandles.size(); i++) {
                if (TelephonyUtil.isPstnComponentName(
                        allAccountHandles.get(i).getComponentName())) {
                    Log.i(this, "Will try PSTN account %s for emergency", allAccountHandles.get(i));
                    mPhoneAccountHandles.add(allAccountHandles.get(i));
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
        public void handleCreateConnectionSuccessful(ConnectionRequest request) {
            if (mResponse == null) {
                mService.abort(mCall);
            } else {
                mResponse.handleCreateConnectionSuccessful(request);
                mResponse= null;
            }
        }

        @Override
        public void handleCreateConnectionFailed(int code, String msg) {
            mLastErrorCode = code;
            mLastErrorMsg = msg;
            Log.d(CreateConnectionProcessor.this, "Connection failed: %d (%s)", code, msg);
            attemptNextPhoneAccount();
        }

        @Override
        public void handleCreateConnectionCancelled() {
            if (mResponse != null) {
                mResponse.handleCreateConnectionCancelled();
                mResponse = null;
            }
        }
    }
}

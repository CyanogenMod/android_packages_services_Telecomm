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

import android.content.ComponentName;
import android.net.Uri;
import android.telephony.DisconnectCause;
import android.telephony.PhoneNumberUtils;
import android.telecomm.ConnectionRequest;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

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
    private List<ComponentName> mServiceComponentNames;
    private Iterator<ComponentName> mServiceComponentNameIterator;
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

        mServiceComponentNames = new ArrayList<>();

        // TODO(sail): Remove once there's a way to pick the service.
        ArrayList<ComponentName> priorityComponents = new ArrayList<>();
        priorityComponents.add(new ComponentName("com.android.phone",
                "com.android.services.telephony.sip.SipConnectionService"));
        priorityComponents.add(new ComponentName("com.google.android.talk",
                "com.google.android.apps.babel.telephony.TeleConnectionService"));
        priorityComponents.add(new ComponentName("com.android.telecomm.tests",
                "com.android.telecomm.testapps.TestConnectionService"));

        for (ConnectionServiceWrapper service : mRepository.lookupServices()) {
            ComponentName serviceName = service.getComponentName();
            if (priorityComponents.contains(serviceName)) {
                Log.i(this, "Moving connection service %s to top of list", serviceName);
                mServiceComponentNames .add(0, serviceName);
            } else {
                mServiceComponentNames.add(serviceName);
            }
        }

        adjustComponentNamesForEmergency();
        mServiceComponentNameIterator = mServiceComponentNames.iterator();
        attemptNextConnectionService();
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

    private void attemptNextConnectionService() {
        Log.v(this, "attemptNextConnectionService");

        if (mResponse != null && mServiceComponentNameIterator.hasNext()) {
            ComponentName component = mServiceComponentNameIterator.next();
            ConnectionServiceWrapper service = mRepository.getService(component);
            if (service == null) {
                attemptNextConnectionService();
            } else {
                mCall.setConnectionService(service);
                Log.i(this, "Attempting to call from %s", service.getComponentName());
                service.createConnection(mCall, new Response(service));
            }
        } else {
            Log.v(this, "attemptNextConnectionService, no more services, failing");
            if (mResponse != null) {
                mResponse.handleCreateConnectionFailed(mLastErrorCode, mLastErrorMsg);
                mResponse = null;
                mCall.clearConnectionService();
            }
        }
    }

    // If we are possibly attempting to call a local emergency number, ensure that the
    // plain PSTN connection service, if it exists, is attempted first.
    private void adjustComponentNamesForEmergency()  {
        if (shouldProcessAsEmergency(mCall.getHandle())) {
            for (int i = 0; i < mServiceComponentNames.size(); i++) {
                if (TelephonyUtil.isPstnComponentName(mServiceComponentNames.get(i))) {
                    mServiceComponentNames.add(0, mServiceComponentNames.remove(i));
                    return;
                }
            }
        }
    }

    private boolean shouldProcessAsEmergency(Uri handle) {
        return handle != null && PhoneNumberUtils.isPotentialLocalEmergencyNumber(
                TelecommApp.getInstance(), handle.getSchemeSpecificPart());
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
            attemptNextConnectionService();
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

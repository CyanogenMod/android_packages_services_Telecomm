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

package com.android.server.telecom.tests;

import com.android.internal.telecom.IConnectionService;
import com.android.internal.telecom.IConnectionServiceAdapter;

import android.os.IBinder;
import android.os.RemoteException;
import android.telecom.AudioState;
import android.telecom.ConnectionRequest;
import android.telecom.PhoneAccountHandle;

/**
 * Controls a test {@link IConnectionService} as would be provided by a source of connectivity
 * to the Telecom framework.
 */
public class ConnectionServiceHolder implements TestDoubleHolder<IConnectionService> {

    private final IConnectionService mConnectionService = new IConnectionService.Stub() {
        @Override
        public void addConnectionServiceAdapter(IConnectionServiceAdapter adapter)
                throws RemoteException {
        }

        @Override
        public void removeConnectionServiceAdapter(IConnectionServiceAdapter adapter)
                throws RemoteException {

        }

        @Override
        public void createConnection(PhoneAccountHandle connectionManagerPhoneAccount,
                String callId,
                ConnectionRequest request, boolean isIncoming, boolean isUnknown)
                throws RemoteException {

        }

        @Override
        public void abort(String callId) throws RemoteException {

        }

        @Override
        public void answerVideo(String callId, int videoState) throws RemoteException {

        }

        @Override
        public void answer(String callId) throws RemoteException {

        }

        @Override
        public void reject(String callId) throws RemoteException {

        }

        @Override
        public void disconnect(String callId) throws RemoteException {

        }

        @Override
        public void hold(String callId) throws RemoteException {

        }

        @Override
        public void unhold(String callId) throws RemoteException {

        }

        @Override
        public void onAudioStateChanged(String activeCallId, AudioState audioState)
                throws RemoteException {

        }

        @Override
        public void playDtmfTone(String callId, char digit) throws RemoteException {

        }

        @Override
        public void stopDtmfTone(String callId) throws RemoteException {

        }

        @Override
        public void conference(String conferenceCallId, String callId) throws RemoteException {

        }

        @Override
        public void splitFromConference(String callId) throws RemoteException {

        }

        @Override
        public void mergeConference(String conferenceCallId) throws RemoteException {

        }

        @Override
        public void swapConference(String conferenceCallId) throws RemoteException {

        }

        @Override
        public void onPostDialContinue(String callId, boolean proceed) throws RemoteException {

        }

        @Override
        public IBinder asBinder() {
            return null;
        }
    };

    @Override
    public IConnectionService getTestDouble() {
        return mConnectionService;
    }

}

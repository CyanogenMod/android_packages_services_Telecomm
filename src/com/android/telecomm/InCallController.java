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
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import android.telecomm.CallInfo;
import android.telecomm.IInCallService;

/**
 * Binds to {@link IInCallService} and provides the service to {@link CallsManager} through which it
 * can send updates to the in-call app. This class is created and owned by CallsManager and retains
 * a binding to the {@link IInCallService} (implemented by the in-call app) until CallsManager
 * explicitly disconnects it. CallsManager starts the connection by calling {@link #connect} and
 * retains the connection as long as it has calls which need UI. When all calls are disconnected,
 * CallsManager will invoke {@link #disconnect} to sever the binding until the in-call UI is needed
 * again.
 */
public final class InCallController {
    /**
     * Used to bind to the in-call app and triggers the start of communication between
     * CallsManager and in-call app.
     */
    private class InCallServiceConnection implements ServiceConnection {
        /** {@inheritDoc} */
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            onConnected(service);
        }

        /** {@inheritDoc} */
        @Override public void onServiceDisconnected(ComponentName name) {
            onDisconnected();
        }
    }

    /**
     * Package name of the in-call app. Although in-call code in kept in its own namespace, it is
     * ultimately compiled into the dialer APK, hence the difference in namespaces between this and
     * {@link #IN_CALL_SERVICE_CLASS_NAME}.
     * TODO(santoscordon): Change this into config.xml resource entry.
     */
    private static final String IN_CALL_PACKAGE_NAME = "com.google.android.dialer";

    /**
     * Class name of the component within in-call app which implements {@link IInCallService}.
     */
    private static final String IN_CALL_SERVICE_CLASS_NAME = "com.android.incallui.InCallService";

    /** Maintains a binding connection to the in-call app. */
    private final InCallServiceConnection mConnection = new InCallServiceConnection();

    private final CallsManager mCallsManager;

    /** The in-call app implementation, see {@link IInCallService}. */
    private IInCallService mInCallService;

    /**
     * Persists the specified parameters.
     */
    InCallController(CallsManager callsManager) {
        mCallsManager = callsManager;
    }

    // TODO(santoscordon): May be better to expose the IInCallService methods directly from this
    // class as its own method to make the CallsManager code easier to read.
    IInCallService getService() {
        return mInCallService;
    }

    /**
     * Indicates to the in-call app that a new call has been created and an appropriate
     * user-interface should be built and shown to notify the user.  Information about the call
     * including its current state is passed in through the callInfo object.
     *
     * @param callInfo Details about the new call.
     */
    void addCall(CallInfo callInfo) {
        try {
            if (mInCallService == null) {
                bind();
            } else {
                // TODO(santoscordon): Protect against logging phone number.
                Log.i(this, "Adding call: %s", callInfo);
                mInCallService.addCall(callInfo);
            }
        } catch (RemoteException e) {
            Log.e(this, e, "Exception attempting to addCall.");
        }
    }

    /**
     * Indicates to the in-call app that a call has moved to the active state.
     *
     * @param callId The identifier of the call that became active.
     */
    void markCallAsActive(String callId) {
        try {
            if (mInCallService != null) {
                Log.i(this, "Mark call as ACTIVE: %s", callId);
                mInCallService.setActive(callId);
            }
        } catch (RemoteException e) {
            Log.e(this, e, "Exception attempting to markCallAsActive.");
        }
    }

    /**
     * Indicates to the in-call app that a call has been disconnected and the user should be
     * notified.
     *
     * @param callId The identifier of the call that was disconnected.
     */
    void markCallAsDisconnected(String callId) {
        try {
            if (mInCallService != null) {
                Log.i(this, "Mark call as DISCONNECTED: %s", callId);
                mInCallService.setDisconnected(callId);
            }
        } catch (RemoteException e) {
            Log.e(this, e, "Exception attempting to markCallAsDisconnected.");
        }
    }

    /**
     * Unbinds an existing bound connection to the in-call app.
     */
    void unbind() {
        ThreadUtil.checkOnMainThread();
        if (mInCallService != null) {
            Log.i(this, "Unbinding from InCallService");
            TelecommApp.getInstance().unbindService(mConnection);
            mInCallService = null;
        }
    }

    /**
     * Binds to the in-call app if not already connected by binding directly to the saved
     * component name of the {@link IInCallService} implementation.
     */
    private void bind() {
        ThreadUtil.checkOnMainThread();
        if (mInCallService == null) {
            ComponentName component =
                    new ComponentName(IN_CALL_PACKAGE_NAME, IN_CALL_SERVICE_CLASS_NAME);
            Log.i(this, "Attempting to bind to InCallService: %s", component);

            Intent serviceIntent = new Intent(IInCallService.class.getName());
            serviceIntent.setComponent(component);

            Context context = TelecommApp.getInstance();
            if (!context.bindService(serviceIntent, mConnection, Context.BIND_AUTO_CREATE)) {
                Log.w(this, "Could not connect to the in-call app (%s)", component);

                // TODO(santoscordon): Implement retry or fall-back-to-default logic.
            }
        }
    }

    /**
     * Persists the {@link IInCallService} instance and starts the communication between
     * CallsManager and in-call app by sending the first update to in-call app. This method is
     * called after a successful binding connection is established.
     *
     * @param service The {@link IInCallService} implementation.
     */
    private void onConnected(IBinder service) {
        ThreadUtil.checkOnMainThread();
        mInCallService = IInCallService.Stub.asInterface(service);

        try {
            mInCallService.setInCallAdapter(new InCallAdapter(mCallsManager));
        } catch (RemoteException e) {
            Log.e(this, e, "Failed to set the in-call adapter.");
            mInCallService = null;
        }

        // Upon successful connection, send the state of the world to the in-call app.
        if (mInCallService != null) {
            mCallsManager.updateInCall();
        }

    }

    /**
     * Cleans up the instance of in-call app after the service has been unbound.
     */
    private void onDisconnected() {
        ThreadUtil.checkOnMainThread();
        mInCallService = null;
    }
}

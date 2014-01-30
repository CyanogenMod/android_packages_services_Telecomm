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
import android.telecomm.IInCallService;
import android.util.Log;

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

    private static final String TAG = InCallController.class.getSimpleName();

    /**
     * Package name of the in-call app. Although in-call code in kept in its own namespace, it is
     * ultimately compiled into the dialer apk, hence the difference in namespaces between this and
     * {@link IN_CALL_SERVICE_CLASS_NAME}.
     * TODO(santoscordon): Change this into config.xml resource entry.
     */
    private static final String IN_CALL_PACKAGE_NAME = "com.google.android.dialer";

    /**
     * Class name of the component within in-call app which implements {@link IInCallService}.
     */
    private static final String IN_CALL_SERVICE_CLASS_NAME = "com.android.incall.InCallService";

    /** Maintains a binding connection to the in-call app. */
    private final InCallServiceConnection mConnection = new InCallServiceConnection();

    private final CallsManager mCallsManager;

    /** The in-call app implementation, see {@link IInCallService}. */
    private IInCallService mInCallService;

    /**
     * Persists the specified parameters.
     */
    InCallController() {
        mCallsManager = CallsManager.getInstance();
    }

    // TODO(santoscordon): May be better to expose the IInCallService methods directly from this
    // class as its own method to make the CallsManager code easier to read.
    IInCallService getService() {
        return mInCallService;
    }

    /**
     * Binds to the in-call app if not already connected by binding directly to the saved
     * component name of the {@link IInCallService} implementation.
     *
     * @param context The application context.
     */
    void connect(Context context) {
        ThreadUtil.checkOnMainThread();
        if (mInCallService == null) {
            ComponentName component =
                    new ComponentName(IN_CALL_PACKAGE_NAME, IN_CALL_SERVICE_CLASS_NAME);

            Intent serviceIntent = new Intent(IInCallService.class.getName());
            serviceIntent.setComponent(component);

            if (!context.bindService(serviceIntent, mConnection, Context.BIND_AUTO_CREATE)) {
                Log.e(TAG, "Could not connect to the in-call app (" + component + ")");

                // TODO(santoscordon): Implement retry or fall-back-to-default logic.
            }
        }
    }

    /**
     * Unbinds an existing bound connection to the in-call app.
     *
     * @param context The application context.
     */
    void disconnect(Context context) {
        ThreadUtil.checkOnMainThread();
        if (mInCallService != null) {
            context.unbindService(mConnection);
            mInCallService = null;
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
            Log.e(TAG, "Failed to set the in-call adapter.", e);
            mInCallService = null;
        }

        update();
    }

    /**
     * Cleans up the instance of in-call app after the service has been unbound.
     */
    private void onDisconnected() {
        ThreadUtil.checkOnMainThread();
        mInCallService = null;
    }

    /**
     * Gathers the list of current calls from CallsManager and sends them to the in-call app.
     */
    private void update() {
        // TODO(santoscordon): mInCallService.sendCalls(CallsManager.getCallList());
    }
}

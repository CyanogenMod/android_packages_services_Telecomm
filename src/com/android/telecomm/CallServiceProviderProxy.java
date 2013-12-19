/*
 * Copyright (C) 2013 The Android Open Source Project
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
import android.telecomm.ICallServiceProvider;
import android.util.Log;

import com.google.common.base.Preconditions;

/**
 * A proxy to a bound CallServiceProvider implementation. Given a {@link ComponentName}, this class
 * will bind, maintain and unbind a connection with a CallServiceProvider.
 */
class CallServiceProviderProxy {
    /**
     * Interface used for notifying when the {@link ICallServiceProvider} is bound.
     */
    public interface CallServiceProviderConnectionCallback {
        public void onConnected(ICallServiceProvider provider);
    }

    /** Used to identify log entries by this class */
    static final String TAG = CallServiceFinder.class.getSimpleName();

    /** Context used to bind with ICallServiceProvider. */
    private final Context mContext;

    /**
     * Explicit component name of of the ICallServiceProvider implementation with which to bind.
     */
    private final ComponentName mComponentName;

    /**
     * Persists the specified parameters.
     */
    public CallServiceProviderProxy(ComponentName componentName, Context context) {
        mComponentName = Preconditions.checkNotNull(componentName);
        mContext = Preconditions.checkNotNull(context);
    }

    /**
     * Binds with the {@link ICallServiceProvider} implementation specified by
     * {@link #mComponentName}.
     */
    public void connect(final CallServiceProviderConnectionCallback connectionCallback) {
        // TODO(santoscordon): Are there cases where we are already connected and should return
        // early with a saved instance?

        Intent serviceIntent = getServiceIntent();
        Log.i(TAG, "Binding to ICallService through " + serviceIntent);

        // Connection object for the service binding.
        ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName className, IBinder service) {
                onConnected(ICallServiceProvider.Stub.asInterface(service), this,
                        connectionCallback);
            }

            @Override
            public void onServiceDisconnected(ComponentName className) {
                onDisconnected(this);
            }
        };

        if (!mContext.bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)) {
            // TODO(santoscordon): Handle error
        }

        // At this point, we should get called on onServiceConnected asynchronously
    }

    /**
     * Returns the service {@link Intent} used to bind to {@link ICallServiceProvider} instances.
     */
    private Intent getServiceIntent() {
        Intent serviceIntent = new Intent(ICallServiceProvider.class.getName());
        serviceIntent.setComponent(mComponentName);
        return serviceIntent;
    }

    /**
     * Called when an instance of ICallServiceProvider is bound to this process.
     *
     * @param serviceProvider The {@link ICallServiceProvider} instance that was bound.
     * @param connection The service connection used to bind to serviceProvider.
     */
    private void onConnected(ICallServiceProvider serviceProvider, ServiceConnection connection,
            CallServiceProviderConnectionCallback connectionCallback) {
        // TODO(santoscordon): add some error conditions

        connectionCallback.onConnected(serviceProvider);
    }

    /**
     * Called when ICallServiceProvider is disconnected.  This could be for any reason including
     * the host process dying.
     *
     * @param connection The service connection used to bind initially.
     */
    private void onDisconnected(ServiceConnection connection) {
        // TODO(santoscordon): How to handle disconnection?
    }
}

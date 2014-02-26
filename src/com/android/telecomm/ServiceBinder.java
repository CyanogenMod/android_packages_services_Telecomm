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
import android.os.IInterface;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.Sets;

import java.util.Set;

/**
 * Abstract class to perform the work of binding and unbinding to the specified service interface.
 * Subclasses supply the service intent and component name and this class will invoke protected
 * methods when the class is bound, unbound, or upon failure.
 */
abstract class ServiceBinder<ServiceInterface extends IInterface> {

    /**
     * Callback to notify after a binding succeeds or fails.
     */
    interface BindCallback {
        public void onSuccess();
        public void onFailure();
    }

    private final class ServiceBinderConnection implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder binder) {
            ThreadUtil.checkOnMainThread();

            // Unbind request was queued so unbind immediately.
            if (mIsBindingAborted) {
                clearAbort();
                mContext.unbindService(this);
                handleFailedConnection();
                return;
            }

            mServiceConnection = this;
            mBinder = binder;
            setServiceInterface(binder);
            handleSuccessfulConnection();
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mServiceConnection = null;
            clearAbort();

            handleServiceDisconnected();
        }
    }

    /** The application context. */
    private final Context mContext;

    /** The intent action to use when binding through {@link Context#bindService}. */
    private final String mServiceAction;

    /** The component name of the service to bind to. */
    private final ComponentName mComponentName;

    /** The set of callbacks waiting for notification of the binding's success or failure. */
    private final Set<BindCallback> mCallbacks = Sets.newHashSet();

    /** Used to bind and unbind from the service. */
    private ServiceConnection mServiceConnection;

    /** The binder provided by {@link ServiceConnection#onServiceConnected} */
    private IBinder mBinder;

    /**
     * Indicates that an unbind request was made when the service was not yet bound. If the service
     * successfully connects when this is true, it should be unbound immediately.
     */
    private boolean mIsBindingAborted;

    /**
     * Persists the specified parameters and initializes the new instance.
     *
     * @param serviceAction The intent-action used with {@link Context#bindService}.
     * @param componentName The component name of the service with which to bind.
     */
    protected ServiceBinder(String serviceAction, ComponentName componentName) {
        Preconditions.checkState(!Strings.isNullOrEmpty(serviceAction));
        Preconditions.checkNotNull(componentName);

        mContext = TelecommApp.getInstance();
        mServiceAction = serviceAction;
        mComponentName = componentName;
    }

    /**
     * Performs an asynchronous bind to the service if not already bound.
     *
     * @param callback The callback to notify of the binding's success or failure.
     * @return The result of {#link Context#bindService} or true if already bound.
     */
    final boolean bind(BindCallback callback) {
        ThreadUtil.checkOnMainThread();

        // Reset any abort request if we're asked to bind again.
        clearAbort();

        // If we are already waiting on a binding request, simply append to the list of waiting
        // callbacks.
        if (!mCallbacks.isEmpty()) {
            mCallbacks.add(callback);
            return true;
        }

        mCallbacks.add(callback);
        if (mServiceConnection == null) {
            Intent serviceIntent = new Intent(mServiceAction).setComponent(mComponentName);
            ServiceConnection connection = new ServiceBinderConnection();

            if (!mContext.bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)) {
                handleFailedConnection();
                return false;
            }
        } else {
            Preconditions.checkNotNull(mBinder);
            handleSuccessfulConnection();
        }

        return true;
    }

    /**
     * Unbinds from the service if already bound, no-op otherwise.
     */
    final void unbind() {
        ThreadUtil.checkOnMainThread();

        if (mServiceConnection == null) {
            // We're not yet bound, so queue up an abort request.
            mIsBindingAborted = true;
        } else {
            mContext.unbindService(mServiceConnection);
            mServiceConnection = null;
            mBinder = null;
        }
    }

    final ComponentName getComponentName() {
        return mComponentName;
    }

    /**
     * Notifies all the outstanding callbacks that the service is successfully bound. The list of
     * outstanding callbacks is cleared afterwards.
     */
    private void handleSuccessfulConnection() {
        for (BindCallback callback : mCallbacks) {
            callback.onSuccess();
        }
        mCallbacks.clear();
    }

    /**
     * Notifies all the outstanding callbacks that the service failed to bind. The list of
     * outstanding callbacks is cleared afterwards.
     */
    private void handleFailedConnection() {
        for (BindCallback callback : mCallbacks) {
            callback.onFailure();
        }
        mCallbacks.clear();
    }

    /**
     * Handles a service disconnection.
     */
    private void handleServiceDisconnected() {
        setServiceInterface(null);
    }

    private void clearAbort() {
        mIsBindingAborted = false;
    }

    /**
     * Sets the service interface after the service is bound or unbound.
     *
     * @param binder The actual bound service implementation.
     */
    protected abstract void setServiceInterface(IBinder binder);
}

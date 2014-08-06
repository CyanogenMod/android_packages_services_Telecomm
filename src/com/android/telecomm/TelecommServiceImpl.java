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

import android.app.AppOpsManager;
import android.content.ComponentName;
import android.content.Context;

import android.content.Intent;
import android.content.res.Resources;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.ServiceManager;
import android.phone.PhoneManager;
import android.telecomm.CallState;
import android.telecomm.PhoneAccount;
import android.telecomm.PhoneAccountHandle;
import android.telecomm.TelecommManager;
import android.telephony.TelephonyManager;

import com.android.internal.telecomm.ITelecommService;

import java.util.List;

/**
 * Implementation of the ITelecomm interface.
 */
public class TelecommServiceImpl extends ITelecommService.Stub {
    private static final String TELEPHONY_PACKAGE_NAME = "com.android.phone";

    /** ${inheritDoc} */
    @Override
    public IBinder asBinder() {
        return super.asBinder();
    }

 /**
     * A request object for use with {@link MainThreadHandler}. Requesters should wait() on the
     * request after sending. The main thread will notify the request when it is complete.
     */
    private static final class MainThreadRequest {
        /** The result of the request that is run on the main thread */
        public Object result;
    }

    /**
     * A handler that processes messages on the main thread in the phone process. Since many
     * of the Phone calls are not thread safe this is needed to shuttle the requests from the
     * inbound binder threads to the main thread in the phone process.
     */
    private final class MainThreadHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            if (msg.obj instanceof MainThreadRequest) {
                MainThreadRequest request = (MainThreadRequest) msg.obj;
                Object result = null;
                switch (msg.what) {
                    case MSG_SILENCE_RINGER:
                        mCallsManager.getRinger().silence();
                        break;
                    case MSG_SHOW_CALL_SCREEN:
                        mCallsManager.getInCallController().bringToForeground(msg.arg1 == 1);
                        break;
                    case MSG_END_CALL:
                        result = endCallInternal();
                        break;
                    case MSG_ACCEPT_RINGING_CALL:
                        acceptRingingCallInternal();
                        break;
                    case MSG_CANCEL_MISSED_CALLS_NOTIFICATION:
                        mMissedCallNotifier.clearMissedCalls();
                        break;
                    case MSG_IS_TTY_SUPPORTED:
                        result = mCallsManager.isTtySupported();
                        break;
                    case MSG_GET_CURRENT_TTY_MODE:
                        result = mCallsManager.getCurrentTtyMode();
                        break;
                }

                if (result != null) {
                    request.result = result;
                    synchronized(request) {
                        request.notifyAll();
                    }
                }
            }
        }
    }

    /** Private constructor; @see init() */
    private static final String TAG = TelecommServiceImpl.class.getSimpleName();

    private static final String SERVICE_NAME = "telecomm";

    private static final int MSG_SILENCE_RINGER = 1;
    private static final int MSG_SHOW_CALL_SCREEN = 2;
    private static final int MSG_END_CALL = 3;
    private static final int MSG_ACCEPT_RINGING_CALL = 4;
    private static final int MSG_CANCEL_MISSED_CALLS_NOTIFICATION = 5;
    private static final int MSG_IS_TTY_SUPPORTED = 6;
    private static final int MSG_GET_CURRENT_TTY_MODE = 7;

    /** The singleton instance. */
    private static TelecommServiceImpl sInstance;

    private final MainThreadHandler mMainThreadHandler = new MainThreadHandler();
    private final CallsManager mCallsManager = CallsManager.getInstance();
    private final MissedCallNotifier mMissedCallNotifier;
    private final PhoneAccountRegistrar mPhoneAccountRegistrar;
    private final AppOpsManager mAppOpsManager;

    private TelecommServiceImpl(
            MissedCallNotifier missedCallNotifier, PhoneAccountRegistrar phoneAccountRegistrar) {
        mMissedCallNotifier = missedCallNotifier;
        mPhoneAccountRegistrar = phoneAccountRegistrar;
        mAppOpsManager =
                (AppOpsManager) TelecommApp.getInstance().getSystemService(Context.APP_OPS_SERVICE);

        publish();
    }

    /**
     * Initialize the singleton TelecommServiceImpl instance.
     * This is only done once, at startup, from TelecommApp.onCreate().
     */
    static TelecommServiceImpl init(
            MissedCallNotifier missedCallNotifier, PhoneAccountRegistrar phoneAccountRegistrar) {
        synchronized (TelecommServiceImpl.class) {
            if (sInstance == null) {
                sInstance = new TelecommServiceImpl(missedCallNotifier, phoneAccountRegistrar);
            } else {
                Log.wtf(TAG, "init() called multiple times!  sInstance %s", sInstance);
            }
            return sInstance;
        }
    }

    //
    // Implementation of the ITelecommService interface.
    //

    @Override
    public PhoneAccountHandle getDefaultOutgoingPhoneAccount() {
        try {
            return mPhoneAccountRegistrar.getDefaultOutgoingPhoneAccount();
        } catch (Exception e) {
            Log.e(this, e, "getDefaultOutgoingPhoneAccount");
            throw e;
        }
    }

    @Override
    public List<PhoneAccountHandle> getEnabledPhoneAccounts() {
        try {
            return mPhoneAccountRegistrar.getEnabledPhoneAccounts();
        } catch (Exception e) {
            Log.e(this, e, "getEnabledPhoneAccounts");
            throw e;
        }
    }

    @Override
    public PhoneAccount getPhoneAccount(PhoneAccountHandle accountHandle) {
        try {
            return mPhoneAccountRegistrar.getPhoneAccount(accountHandle);
        } catch (Exception e) {
            Log.e(this, e, "getPhoneAccount %s", accountHandle);
            throw e;
        }
    }

    @Override
    public void registerPhoneAccount(PhoneAccount account) {
        try {
            enforceModifyPermissionOrCallingPackage(
                    account.getAccountHandle().getComponentName().getPackageName());
            if (PhoneAccountRegistrar.has(account, PhoneAccount.CAPABILITY_CALL_PROVIDER) ||
                PhoneAccountRegistrar.has(account, PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION)) {
                enforceModifyPermissionOrCallingPackage(TELEPHONY_PACKAGE_NAME);
            }
            mPhoneAccountRegistrar.registerPhoneAccount(account);
        } catch (Exception e) {
            Log.e(this, e, "registerPhoneAccount %s", account);
            throw e;
        }
    }

    @Override
    public void unregisterPhoneAccount(PhoneAccountHandle accountHandle) {
        try {
            enforceModifyPermissionOrCallingPackage(
                    accountHandle.getComponentName().getPackageName());
            mPhoneAccountRegistrar.unregisterPhoneAccount(accountHandle);
        } catch (Exception e) {
            Log.e(this, e, "unregisterPhoneAccount %s", accountHandle);
            throw e;
        }
    }

    @Override
    public void clearAccounts(String packageName) {
        try {
            enforceModifyPermissionOrCallingPackage(packageName);
            mPhoneAccountRegistrar.clearAccounts(packageName);
        } catch (Exception e) {
            Log.e(this, e, "clearAccounts %s", packageName);
            throw e;
        }
    }

    /**
     * @see TelecommManager#silenceRinger
     */
    @Override
    public void silenceRinger() {
        Log.d(this, "silenceRinger");
        enforceModifyPermission();
        sendRequestAsync(MSG_SILENCE_RINGER, 0);
    }

    /**
     * @see TelecommManager#getDefaultPhoneApp
     */
    @Override
    public ComponentName getDefaultPhoneApp() {
        Resources resources = TelecommApp.getInstance().getResources();
        return new ComponentName(
                resources.getString(R.string.ui_default_package),
                resources.getString(R.string.dialer_default_class));
    }

    /**
     * @see TelecommManager#isInAPhoneCall
     */
    @Override
    public boolean isInAPhoneCall() {
        enforceReadPermission();
        // Do not use sendRequest() with this method since it could cause a deadlock with
        // audio service, which we call into from the main thread: AudioManager.setMode().
        return mCallsManager.hasAnyCalls();
    }

    /**
     * @see TelecommManager#isRinging
     */
    @Override
    public boolean isRinging() {
        enforceReadPermission();
        return mCallsManager.hasRingingCall();
    }

    /**
     * @see TelecommManager#endCall
     */
    @Override
    public boolean endCall() {
        enforceModifyPermission();
        return (boolean) sendRequest(MSG_END_CALL);
    }

    /**
     * @see TelecommManager#acceptRingingCall
     */
    @Override
    public void acceptRingingCall() {
        enforceModifyPermission();
        sendRequestAsync(MSG_ACCEPT_RINGING_CALL, 0);
    }

    /**
     * @see PhoneManager#showCallScreen
     */
    @Override
    public void showCallScreen(boolean showDialpad) {
        enforceReadPermissionOrDefaultDialer();
        sendRequestAsync(MSG_SHOW_CALL_SCREEN, showDialpad ? 1 : 0);
    }

    /**
     * @see PhoneManager#cancelMissedCallsNotification
     */
    @Override
    public void cancelMissedCallsNotification() {
        enforceModifyPermissionOrDefaultDialer();
        sendRequestAsync(MSG_CANCEL_MISSED_CALLS_NOTIFICATION, 0);
    }

    /**
     * @see PhoneManager#handlePinMmi
     */
    @Override
    public boolean handlePinMmi(String dialString) {
        enforceModifyPermissionOrDefaultDialer();

        // Switch identity so that TelephonyManager checks Telecomm's permissions instead.
        long token = Binder.clearCallingIdentity();
        boolean retval = getTelephonyManager().handlePinMmi(dialString);
        Binder.restoreCallingIdentity(token);

        return retval;
    }

    /**
     * @see TelecommManager#isTtySupported
     */
    @Override
    public boolean isTtySupported() {
        enforceReadPermission();
        return (boolean) sendRequest(MSG_IS_TTY_SUPPORTED);
    }

    /**
     * @see TelecommManager#getCurrentTtyMode
     */
    @Override
    public int getCurrentTtyMode() {
        enforceReadPermission();
        return (int) sendRequest(MSG_GET_CURRENT_TTY_MODE);
    }

    /**
     * @see TelecommManager#addNewIncomingCall
     */
    @Override
    public void addNewIncomingCall(PhoneAccountHandle phoneAccountHandle, Bundle extras) {
        if (phoneAccountHandle != null && phoneAccountHandle.getComponentName() != null) {
            mAppOpsManager.checkPackage(
                    Binder.getCallingUid(), phoneAccountHandle.getComponentName().getPackageName());

            Intent intent = new Intent(TelecommManager.ACTION_INCOMING_CALL);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.putExtra(TelecommManager.EXTRA_PHONE_ACCOUNT_HANDLE, phoneAccountHandle);
            if (extras != null) {
                intent.putExtra(TelecommManager.EXTRA_INCOMING_CALL_EXTRAS, extras);
            }

            long token = Binder.clearCallingIdentity();
            TelecommApp.getInstance().startActivity(intent);
            Binder.restoreCallingIdentity(token);
        }
    }

    //
    // Supporting methods for the ITelecommService interface implementation.
    //

    private void acceptRingingCallInternal() {
        Call call = mCallsManager.getFirstCallWithState(CallState.RINGING);
        if (call != null) {
            call.answer(call.getVideoState());
        }
    }

    private boolean endCallInternal() {
        // Always operate on the foreground call if one exists, otherwise get the first call in
        // priority order by call-state.
        Call call = mCallsManager.getForegroundCall();
        if (call == null) {
            call = mCallsManager.getFirstCallWithState(
                    CallState.ACTIVE,
                    CallState.DIALING,
                    CallState.RINGING,
                    CallState.ON_HOLD);
        }

        if (call != null) {
            if (call.getState() == CallState.RINGING) {
                call.reject(false /* rejectWithMessage */, null);
            } else {
                call.disconnect();
            }
            return true;
        }

        return false;
    }

    /**
     * Make sure the caller has the MODIFY_PHONE_STATE permission.
     *
     * @throws SecurityException if the caller does not have the required permission
     */
    private void enforceModifyPermission() {
        TelecommApp.getInstance().enforceCallingOrSelfPermission(
                android.Manifest.permission.MODIFY_PHONE_STATE, null);
    }

    private void enforceModifyPermissionOrDefaultDialer() {
        if (!isDefaultDialerCalling()) {
            enforceModifyPermission();
        }
    }

    private void enforceModifyPermissionOrCallingPackage(String packageName) {
        // TODO: Use a new telecomm permission for this instead of reusing modify.
        try {
            enforceModifyPermission();
        } catch (SecurityException e) {
            enforceCallingPackage(packageName);
        }
    }

    private void enforceReadPermission() {
        TelecommApp.getInstance().enforceCallingOrSelfPermission(
                android.Manifest.permission.READ_PHONE_STATE, null);
    }

    private void enforceReadPermissionOrDefaultDialer() {
        if (!isDefaultDialerCalling()) {
            enforceReadPermission();
        }
    }

    private void enforceCallingPackage(String packageName) {
        mAppOpsManager.checkPackage(Binder.getCallingUid(), packageName);
    }

    private void showCallScreenInternal(boolean showDialpad) {
        CallsManager.getInstance().getInCallController().bringToForeground(showDialpad);
    }

    private boolean isDefaultDialerCalling() {
        ComponentName defaultDialerComponent = getDefaultPhoneApp();
        if (defaultDialerComponent != null) {
            try {
                mAppOpsManager.checkPackage(
                        Binder.getCallingUid(), defaultDialerComponent.getPackageName());
                return true;
            } catch (SecurityException e) {
                Log.e(TAG, e, "Could not get default dialer.");
            }
        }
        return false;
    }

    private TelephonyManager getTelephonyManager() {
        return (TelephonyManager)
                TelecommApp.getInstance().getSystemService(Context.TELEPHONY_SERVICE);
    }

    private void publish() {
        Log.d(this, "publish: %s", this);
        ServiceManager.addService(SERVICE_NAME, this);
    }

    private MainThreadRequest sendRequestAsync(int command, int arg1) {
        MainThreadRequest request = new MainThreadRequest();
        mMainThreadHandler.obtainMessage(command, arg1, 0, request).sendToTarget();
        return request;
    }

    /**
     * Posts the specified command to be executed on the main thread, waits for the request to
     * complete, and returns the result.
     */
    private Object sendRequest(int command) {
        if (Looper.myLooper() == mMainThreadHandler.getLooper()) {
            MainThreadRequest request = new MainThreadRequest();
            mMainThreadHandler.handleMessage(mMainThreadHandler.obtainMessage(command, request));
            return request.result;
        } else {
            MainThreadRequest request = sendRequestAsync(command, 0);

            // Wait for the request to complete
            synchronized (request) {
                while (request.result == null) {
                    try {
                        request.wait();
                    } catch (InterruptedException e) {
                        // Do nothing, go back and wait until the request is complete
                    }
                }
            }
            return request.result;
        }
    }
}

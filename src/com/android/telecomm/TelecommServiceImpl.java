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
import android.content.res.Resources;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ServiceManager;
import android.phone.PhoneManager;
import android.telecomm.CallState;
import android.telecomm.PhoneAccount;
import android.telecomm.TelecommManager;
import android.telephony.TelephonyManager;

import com.android.internal.telecomm.ITelecommService;
import com.google.android.collect.Lists;

import java.util.List;

/**
 * Implementation of the ITelecomm interface.
 */
public class TelecommServiceImpl extends ITelecommService.Stub {
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
                    case MSG_IS_IN_A_PHONE_CALL:
                        result = mCallsManager.hasAnyCalls();
                        break;
                    case MSG_IS_RINGING:
                        result = mCallsManager.hasRingingCall();
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
    private static final int MSG_IS_IN_A_PHONE_CALL = 3;
    private static final int MSG_IS_RINGING = 4;
    private static final int MSG_END_CALL = 5;
    private static final int MSG_ACCEPT_RINGING_CALL = 6;
    private static final int MSG_CANCEL_MISSED_CALLS_NOTIFICATION = 7;

    /** The singleton instance. */
    private static TelecommServiceImpl sInstance;

    private final MainThreadHandler mMainThreadHandler = new MainThreadHandler();
    private final CallsManager mCallsManager = CallsManager.getInstance();
    private final MissedCallNotifier mMissedCallNotifier;
    private final AppOpsManager mAppOpsManager;

    private TelecommServiceImpl(MissedCallNotifier missedCallNotifier) {
        mMissedCallNotifier = missedCallNotifier;
        mAppOpsManager =
                (AppOpsManager) TelecommApp.getInstance().getSystemService(Context.APP_OPS_SERVICE);

        publish();
    }

    /**
     * Initialize the singleton TelecommServiceImpl instance.
     * This is only done once, at startup, from TelecommApp.onCreate().
     */
    static TelecommServiceImpl init(MissedCallNotifier missedCallNotifier) {
        synchronized (TelecommServiceImpl.class) {
            if (sInstance == null) {
                sInstance = new TelecommServiceImpl(missedCallNotifier);
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
    public List<PhoneAccount> getAccounts() {
        // TODO (STOPSHIP): Static list of Accounts for testing and UX work only.
        ComponentName componentName = new ComponentName(
                "com.android.telecomm",
                TelecommServiceImpl.class.getName());  // This field is a no-op
        Context app = TelecommApp.getInstance();

        return Lists.newArrayList(
                new PhoneAccount(
                        componentName,
                        "account0",
                        Uri.parse("tel:999-555-1212"),
                        app.getString(R.string.test_account_0_label),
                        app.getString(R.string.test_account_0_short_description),
                        true,
                        true),
                new PhoneAccount(
                        componentName,
                        "account1",
                        Uri.parse("tel:333-111-2222"),
                        app.getString(R.string.test_account_1_label),
                        app.getString(R.string.test_account_1_short_description),
                        true,
                        false),
                new PhoneAccount(
                        componentName,
                        "account2",
                        Uri.parse("mailto:two@example.com"),
                        app.getString(R.string.test_account_2_label),
                        app.getString(R.string.test_account_2_short_description),
                        true,
                        false),
                new PhoneAccount(
                        componentName,
                        "account3",
                        Uri.parse("mailto:three@example.com"),
                        app.getString(R.string.test_account_3_label),
                        app.getString(R.string.test_account_3_short_description),
                        true,
                        false)
        );
    }

    @Override
    public void setEnabled(PhoneAccount account, boolean enabled) {
        // Enforce MODIFY_PHONE_STATE ?
        // TODO
    }

    @Override
    public void setSystemDefault(PhoneAccount account) {
        // Enforce MODIFY_PHONE_STATE ?
        // TODO
    }

    /**
     * @see TelecommManager#silenceringer
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
        return (boolean) sendRequest(MSG_IS_IN_A_PHONE_CALL);
    }

    /**
     * @see TelecommManager#isRinging
     */
    @Override
    public boolean isRinging() {
        enforceReadPermission();
        return (boolean) sendRequest(MSG_IS_RINGING);
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

    //
    // Supporting methods for the ITelecommService interface implementation.
    //

    private void acceptRingingCallInternal() {
        Call call = mCallsManager.getFirstCallWithState(CallState.RINGING);
        if (call != null) {
            call.answer();
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

    private void enforceReadPermission() {
        TelecommApp.getInstance().enforceCallingOrSelfPermission(
                android.Manifest.permission.READ_PHONE_STATE, null);
    }

    private void enforceReadPermissionOrDefaultDialer() {
        if (!isDefaultDialerCalling()) {
            enforceReadPermission();
        }
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

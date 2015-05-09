package com.android.server.telecom;

import android.app.Notification;
import android.content.Context;

public final class CallInfoProvider extends CallsManagerListenerBase {
    public CallInfoProvider(Context context) {
    }

    public synchronized boolean shouldBlock(String number) {
        return false;
    }

    public boolean requiresNetwork() {
        return false;
    }

    public boolean providesCallInfo() {
        return false;
    }

    public boolean updateInfoForCall(CallInfo call) {
        return false;
    }

    public void updateMissedCallNotification(Notification notification) {
    }
}

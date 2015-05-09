package com.android.server.telecom;

import android.content.Context;

public final class CallInfoProvider extends CallsManagerListenerBase {
    public CallInfoProvider(Context context) {
    }

    /**
     * Synchronous call used to determine whether given number
     * should be blocked
     */
    public synchronized boolean shouldBlock(String number) {
        return false;
    }

    public CallInfo processCall(Call call) {
        return null;
    }
}

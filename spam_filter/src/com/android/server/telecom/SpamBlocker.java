package com.android.server.telecom;

import android.content.Context;

/**
 * Listens for call updates and records whether calls should be blocked based on
 * caller info results.  Can also hang up calls while they are ringing, in case
 * they could not be blocked in time.
 */
public class SpamBlocker extends CallsManagerListenerBase {

    public SpamBlocker(Context context) {
    }

    public synchronized boolean shouldBlock(String number) {
        return false;
    }

}

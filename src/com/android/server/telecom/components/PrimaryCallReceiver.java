package com.android.server.telecom.components;

import com.android.server.telecom.TelecomSystem;
import com.android.server.telecom.UserCallIntentProcessor;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Single point of entry for all outgoing and incoming calls. {@link UserCallIntentProcessor} serves
 * as a trampoline that captures call intents for individual users and forwards it to
 * the {@link PrimaryCallReceiver} which interacts with the rest of Telecom, both of which run only as
 * the primary user.
 */
public class PrimaryCallReceiver extends BroadcastReceiver implements TelecomSystem.Component {

    @Override
    public void onReceive(Context context, Intent intent) {
        getTelecomSystem().getCallIntentProcessor().processIntent(intent);
    }

    @Override
    public TelecomSystem getTelecomSystem() {
        return TelecomSystem.getInstance();
    }
}

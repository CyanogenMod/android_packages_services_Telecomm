/*
 * Copyright 2014, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
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
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.telecomm.ICallServiceSelector;
import android.util.Log;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import java.util.List;
import java.util.Set;

/**
 * Helper class to retrieve {@link ICallServiceSelector} implementations on the device and
 * asynchronously bind to them.  Each lookup cycle is time-boxed, hence selectors too slow
 * to bind are effectively omitted from the set that is passed back to {@link Switchboard}.
 */
final class CallServiceSelectorRepository {

    private static final String TAG = CallServiceSelectorRepository.class.getSimpleName();

    /**
     * The longest period in milliseconds each lookup cycle is allowed to span over, see
     * {@link #mLookupTerminator}.
     * TODO(gilad): Likely requires tuning.
     */
    private static final int LOOKUP_TIMEOUT_MS = 100;

    /**
     * Used to retrieve all known ICallServiceSelector implementations from the framework.
     */
    private static final String CALL_SERVICE_SELECTOR_CLASS_NAME =
            ICallServiceSelector.class.getName();

    /**
     * Used to interrupt lookup cycles that didn't terminate naturally within the allowed
     * period, see LOOKUP_TIMEOUT.
     */
    private final Runnable mLookupTerminator = new Runnable() {
        @Override
        public void run() {
            terminateLookup();
        }
    };

    /** Used to run code (e.g. messages, Runnables) on the main (UI) thread. */
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private final Switchboard mSwitchboard;

    /** The application context. */
    private final Context mContext;

    /**
     * Determines whether or not a lookup cycle is already running.
     */
    private boolean mIsLookupInProgress = false;

    /**
     * The current lookup-cycle ID, unique per invocation of {@link #initiateLookup}.
     */
    private int mLookupId = 0;

    /**
     * The set of bound call-service selectors.  Only populated via initiateLookup scenarios.
     * Selectors should only be removed upon unbinding.
     */
    private final Set<ICallServiceSelector> mSelectorRegistry = Sets.newHashSet();

    /**
     * Stores the names of the selectors to bind to in one lookup cycle.  The set size represents
     * the number of call-service selectors this repositories expects to hear back from upon
     * initiating lookups, see initiateLookup. Whenever all selectors respond before the timeout
     * occurs, the complete set of available selectors is passed to the switchboard for further
     * processing of outgoing calls etc.  When the timeout occurs before all responds are received,
     * the partial (potentially empty) set gets passed to the switchboard instead. Note that cached
     * selectors do not require finding and hence are excluded from this set.  Also note that
     * selectors are removed from this set as they register.
     */
    private final Set<ComponentName> mUnregisteredSelectors = Sets.newHashSet();

    /**
     * Persists the specified parameters and initializes the new instance.
     *
     * @param switchboard The switchboard for this finer to work against.
     */
    CallServiceSelectorRepository(Switchboard switchboard) {
        mSwitchboard = switchboard;
        mContext = TelecommApp.getInstance();
    }

    /**
     * Initiates a lookup cycle for call-service selectors. Must be called from the UI thread.
     *
     * @param lookupId The switchboard-supplied lookup ID.
     */
    void initiateLookup(int lookupId) {
        ThreadUtil.checkOnMainThread();
        if (mIsLookupInProgress) {
            // At most one active lookup is allowed at any given time, bail out.
            return;
        }

        List<ComponentName> selectorNames = getSelectorNames();
        if (selectorNames.isEmpty()) {
            Log.i(TAG, "No ICallServiceSelector implementations found.");
            updateSwitchboard();
            return;
        }

        mLookupId = lookupId;
        mIsLookupInProgress = true;
        mUnregisteredSelectors.clear();

        for (ComponentName name : selectorNames) {
            if (!mSelectorRegistry.contains(name)) {
                // The selector is either not yet registered or has been unregistered
                // due to unbinding etc.
                mUnregisteredSelectors.add(name);
                bindSelector(name, lookupId);
            }
        }

        int selectorCount = selectorNames.size();
        int unregisteredSelectorCount = mUnregisteredSelectors.size();

        Log.i(TAG, "Found " + selectorCount + " implementations of ICallServiceSelector, "
                + unregisteredSelectorCount + " of which are not currently registered.");

        if (unregisteredSelectorCount == 0) {
            // All known (selector) implementations are already registered, pass control
            // back to the switchboard.
            updateSwitchboard();
        } else {
            // Schedule a lookup terminator to run after LOOKUP_TIMEOUT_MS milliseconds.
            mHandler.removeCallbacks(mLookupTerminator);
            mHandler.postDelayed(mLookupTerminator, LOOKUP_TIMEOUT_MS);
        }
    }

    /**
     * @return The list containing the (component) names of all known ICallServiceSelector
     *     implementations or the empty list upon no available selectors.
     */
    private List<ComponentName> getSelectorNames() {
        // The list of selector names to return to the caller, may be populated below.
        List<ComponentName> selectorNames = Lists.newArrayList();

        PackageManager packageManager = mContext.getPackageManager();
        Intent intent = new Intent(CALL_SERVICE_SELECTOR_CLASS_NAME);
        for (ResolveInfo entry : packageManager.queryIntentServices(intent, 0)) {
            ServiceInfo serviceInfo = entry.serviceInfo;
            if (serviceInfo != null) {
                // The entry resolves to a proper service, add it to the list of selector names.
                selectorNames.add(
                        new ComponentName(serviceInfo.packageName, serviceInfo.name));
            }
        }

        return selectorNames;
    }

    /**
     * Attempts to bind the specified selector and have it register upon successful binding.
     * Also performs the necessary wiring to unregister the selector upon un-binding.
     *
     * @param selectorName The component name of the relevant selector.
     * @param lookupId The lookup-cycle ID.
     */
    private void bindSelector(
            final ComponentName selectorName, final int lookupId) {

        Preconditions.checkNotNull(selectorName);

        Intent serviceIntent =
                new Intent(CALL_SERVICE_SELECTOR_CLASS_NAME).setComponent(selectorName);
        Log.i(TAG, "Binding to ICallServiceSelector through " + serviceIntent);

        // Connection object for the service binding.
        ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName className, IBinder service) {
                ICallServiceSelector selector = ICallServiceSelector.Stub.asInterface(service);
                registerSelector(lookupId, selectorName, selector);
            }

            @Override
            public void onServiceDisconnected(ComponentName className) {
                unregisterSelector(selectorName);
            }
        };

        if (!mContext.bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)) {
            // TODO(gilad): Handle error.
        }
    }

    /**
     * Registers the specified selector.
     *
     * @param lookupId The lookup-cycle ID.  Currently unused, consider removing.
     * @param selectorName The component name of the relevant selector.
     * @param selector The selector object to register.
     */
    private void registerSelector(
            int lookupId, ComponentName selectorName, ICallServiceSelector selector) {

        ThreadUtil.checkOnMainThread();

        if (mUnregisteredSelectors.remove(selectorName)) {
            mSelectorRegistry.add(selector);
            if (mUnregisteredSelectors.size() < 1) {
                terminateLookup();  // No other selectors to wait for.
            }
        }
    }

    /**
     * Unregisters the specified selector.
     *
     * @param selectorName The component name of the relevant selector.
     */
    private void unregisterSelector(ComponentName selectorName) {
        mSelectorRegistry.remove(selectorName);
    }

    /**
     * Timeouts the current lookup cycle, see LookupTerminator.
     */
    private void terminateLookup() {
        mHandler.removeCallbacks(mLookupTerminator);
        updateSwitchboard();
    }

    /**
     * Updates the switchboard passing the relevant call services selectors.
     */
    private void updateSwitchboard() {
        ThreadUtil.checkOnMainThread();

        mSwitchboard.setSelectors(mSelectorRegistry);
        mIsLookupInProgress = false;
    }
}

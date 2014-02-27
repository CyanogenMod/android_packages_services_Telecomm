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

import android.util.Log;

import com.google.common.collect.Sets;

import java.util.Iterator;
import java.util.Set;

/**
 * Keeps track of all outstanding binder connections and provides a facility to clean up unnecessary
 * binders (after initializing outgoing or incoming calls). This in turn allows the classes involved
 * in incoming and outgoing initialization to not have to unbind at all possible failure conditions,
 * which are numerous.
 *
 * To provide (theoretical) context, the two candidate approaches seem to be (1) provide a notion of
 * guaranteed outcome to every attempted action only deallocating resources upon no pending actions,
 * and (2) while other outcomes may be provided, only guarantee the potential outcome of success and
 * then rely on timeouts to treat all expired actions as failures. Since Telecomm pretty much has to
 * deal with timeouts (e.g. due to relying on third-party code), it seems to make little or no sense
 * to guarantee an outcome to every attempted action particularly via using timeouts. Instead, since
 * relying on timeouts is required in both cases, using a centralized timeout solution appears to be
 * beneficial. This gives rise to the following implementation. Any time the switchboard enters what
 * can be thought of as a critical section, it is likely to cause certain resources to be created or
 * bounded. Additional resources may be created and/or bounded throughout that section just as well.
 * To account for that, the switchboard is expected to acquire a use permit and then release it once
 * the critical section is exited.  Since two or more critical sections may overlap (by design), it
 * is imperative that no resources are deallocated until the last critical section is exited.  This
 * ensures that resources that have been obtained but not yet used aren't identified as unused which
 * would otherwise lead to their removal.  This also allows switchboard to maintain a single timeout
 * loop, freeing for example the incoming/outgoing call managers from needing to implement the same.
 * Once the switchboard expires certain actions (e.g. pending outgoing calls) it should also release
 * the corresponding permit, just as it would upon explicit success/failure outcomes.  Subsequently,
 * once all pending permits are released, a resource-deallocation cycle can safely follow.  In terms
 * of implementation, there is no real need to associate specific resources with the action/s during
 * which these resources turned up to be necessary such that counting permits seems sufficient.
 */
final class BinderDeallocator {

    private static final String TAG = BinderDeallocator.class.getSimpleName();

    /**
     * The number of actions currently permitted to use previously-allocated resources and/or create
     * new ones.
     */
    private int mPermitCount = 0;

    /**
     * The set of all known binders, either in use or potentially about to be used.
     */
    @SuppressWarnings("rawtypes")
    private Set<ServiceBinder> mBinders = Sets.newHashSet();

    /**
     * Accounts for the action entering a critical section (i.e. potentially needing access to
     * resources).
     */
    void acquireUsePermit() {
        ThreadUtil.checkOnMainThread();

        mPermitCount++;
    }

    /**
     * Updates the set of binders.
     *
     * @param binders The binders to potentially add to the all-inclusive set of known binders,
     *     see {@link #mBinders}.
     */
    @SuppressWarnings("rawtypes")
    void updateBinders(Set<? extends ServiceBinder> binders) {
        ThreadUtil.checkOnMainThread();

        mBinders.addAll(binders);
    }

    /**
     * Accounts for the action exiting a critical section (i.e. no longer needing access to
     * resources).
     */
    void releaseUsePermit() {
        ThreadUtil.checkOnMainThread();

        if (mPermitCount < 1) {
            Log.wtf(TAG, "releaseUsePermit should only be invoked upon mPermitCount > 0");
        } else if (--mPermitCount == 0) {
            deallocateUnusedResources();
        }
    }

    /**
     * Starts a resource-deallocation cycle.
     */
    @SuppressWarnings("rawtypes")
    private void deallocateUnusedResources() {
        Iterator<ServiceBinder> iterator = mBinders.iterator();
        while (iterator.hasNext()) {
            ServiceBinder binder = iterator.next();
            if (binder.getAssociatedCallCount() < 1) {
                binder.unbind();
                mBinders.remove(binder);
            }
        }
    }
}

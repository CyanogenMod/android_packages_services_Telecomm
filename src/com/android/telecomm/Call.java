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

import java.util.Date;

final class Call {

    /** The handle with which to establish this call. */
    private String mHandle;

    /** Additional contact information beyond handle above, optional. */
    private ContactInfo mContactInfo;

    /**
     * The time this call was created, typically also the time this call was added to the set
     * of pending outgoing calls (mPendingOutgoingCalls) that's maintained by the switchboard.
     * Beyond logging and such, may also be used for bookkeeping and specifically for marking
     * certain call attempts as failed attempts.
     */
    private final Date mCreationTime;

    /**
     * Persists the specified parameters and initializes the new instance.
     *
     * @param handle The handle to dial.
     * @param contactInfo Information about the entity being called.
     */
    public Call(String handle, ContactInfo contactInfo) {
        mHandle = handle;
        mContactInfo = contactInfo;

        mCreationTime = new Date();
    }

    /**
     * @return The "age" of this call object in milliseconds, which typically also represents the
     *     period since this call was added to the set pending outgoing calls, see mCreationTime.
     */
    public long getAgeInMilliseconds() {
        return new Date().getTime() - mCreationTime.getTime();
    }
}

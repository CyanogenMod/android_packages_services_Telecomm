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

import android.net.Uri;

/**
 * Implementations can be used to suppress certain classes of outgoing calls based on arbitrary
 * restrictions across call services (e.g. black-listing some phone numbers regardless if these
 * are attempted over PSTN or WiFi). That being the case, FDN which is specific to GSM may need
 * to be implemented separately since classes implementing this interface are generally invoked
 * before any given call service is selected.
 * See http://en.wikipedia.org/wiki/Fixed_Dialing_Number and/or IncomingCallValidator regarding
 * incoming calls.
 */
public interface OutgoingCallValidator {

    boolean isValid(Uri handle, ContactInfo contactInfo);
}

/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.server.telecom.tests;

/**
 * An object that provides a control interface for configuring a test double.
 *
 * TODO: Come up with a better name for this.
 */
public interface TestDoubleHolder <T> {

    /**
     * Obtain the actual test double provided by this holder.
     *
     * @return the test double.
     */
    T getTestDouble();
}

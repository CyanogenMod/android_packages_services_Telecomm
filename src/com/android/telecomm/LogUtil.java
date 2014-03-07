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

import android.util.Log;

import java.util.IllegalFormatException;
import java.util.Locale;

/**
 * Manages logging for the entire module.
 */
public class LogUtil {

    // Generic tag for all In Call logging
    private static final String TAG = "Telecomm";

    public static final boolean FORCE_LOGGING = true; /* STOP SHIP if true */
    public static final boolean DEBUG = isLoggable(Log.DEBUG);
    public static final boolean VERBOSE = isLoggable(Log.VERBOSE);

    private LogUtil() {}

    public static boolean isLoggable(int level) {
        return FORCE_LOGGING || Log.isLoggable(TAG, level);
    }

    public static void d(String prefix, String format, Object... args) {
        if (DEBUG) {
            Log.d(TAG, buildMessage(prefix, format, args));
        }
    }

    public static void d(Object objectPrefix, String format, Object... args) {
        if (DEBUG) {
            Log.d(TAG, buildMessage(getPrefixFromObject(objectPrefix), format, args));
        }
    }

    public static void v(String prefix, String format, Object... args) {
        if (VERBOSE) {
            Log.v(TAG, buildMessage(prefix, format, args));
        }
    }

    public static void v(Object objectPrefix, String format, Object... args) {
        if (VERBOSE) {
            Log.v(TAG, buildMessage(getPrefixFromObject(objectPrefix), format, args));
        }
    }

    public static void w(String prefix, String format, Object... args) {
        if (isLoggable(Log.WARN)) {
            Log.w(TAG, buildMessage(prefix, format, args));
        }
    }

    public static void w(Object objectPrefix, String format, Object... args) {
        if (isLoggable(Log.WARN)) {
            Log.w(TAG, buildMessage(getPrefixFromObject(objectPrefix), format, args));
        }
    }

    public static void e(String prefix, Throwable tr, String format, Object... args) {
        if (isLoggable(Log.ERROR)) {
            Log.e(TAG, buildMessage(prefix, format, args), tr);
        }
    }

    public static void e(Object objectPrefix, Throwable tr, String format, Object... args) {
        if (isLoggable(Log.ERROR)) {
            Log.e(TAG, buildMessage(getPrefixFromObject(objectPrefix), format, args), tr);
        }
    }

    public static void wtf(String prefix, Throwable tr, String format, Object... args) {
        Log.wtf(TAG, buildMessage(prefix, format, args), tr);
    }

    public static void wtf(Object objectPrefix, Throwable tr, String format, Object... args) {
        Log.wtf(TAG, buildMessage(getPrefixFromObject(objectPrefix), format, args), tr);
    }

    public static void wtf(String prefix, String format, Object... args) {
        Log.wtf(TAG, buildMessage(prefix, format, args));
    }

    public static void wtf(Object objectPrefix, String format, Object... args) {
        Log.wtf(TAG, buildMessage(getPrefixFromObject(objectPrefix), format, args));
    }

    private static String getPrefixFromObject(Object obj) {
        return obj == null ? "<null>" : obj.getClass().getSimpleName();
    }

    private static String buildMessage(String prefix, String format, Object... args) {
        String msg;
        try {
            msg = (args == null || args.length == 0) ? format
                    : String.format(Locale.US, format, args);
        } catch (IllegalFormatException ife) {
            wtf("LogUtil", "IllegalFormatException: formatString='%s' numArgs=%d", format,
                    args.length);
            msg = format + " (An error occurred while formatting the message.)";
        }
        return String.format(Locale.US, "%s: %s", prefix, msg);
    }
}

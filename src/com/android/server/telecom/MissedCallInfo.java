/*
 * Copyright (C) 2015 The CyanogenMod Project
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
 * limitations under the License
 */

package com.android.server.telecom;

import com.android.internal.telephony.CallerInfo;

import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;

public class MissedCallInfo {
    private String mNumber;
    private String mName;
    private Drawable mPhoto;
    private String mSummary;
    private Bitmap mPhotoIcon;
    private long mCreationTimeMillis;
    private Uri mHandle;
    private CallerInfo mCallerInfo;
    private boolean mPhotoVisible;
    private boolean mFetchRequested;
    private boolean mProviderHasInformation;
    private Object mTag;

    MissedCallInfo(Call call) {
        setNumber(call.getNumber());
        setName(call.getName());
        setPhoto(call.getPhoto());
        setPhotoIcon(call.getPhotoIcon());
        mCreationTimeMillis = call.getCreationTimeMillis();
        mHandle = call.getHandle();
        mCallerInfo = call.getCallerInfo();
    }

    public CallerInfo getCallerInfo() {
        return mCallerInfo;
    }

    public String getName() {
        return mName;
    }

    public void setName(String name) {
        mName = name;
    }

    public Drawable getPhoto() {
        return mPhoto;
    }

    public void setPhoto(Drawable photo) {
        mPhoto = photo;
    }

    public String getSummaryText() {
        return mSummary;
    }

    public void setSummaryText(String summary) {
        mSummary = summary;
    }

    private void setNumber(String number) {
        mNumber = number;
    }

    public String getNumber() {
        return mNumber;
    }

    public Uri getHandle() {
        return mHandle;
    }

    void setPhotoIcon(Bitmap photoIcon) {
        mPhotoIcon = photoIcon;
    }

    public Bitmap getPhotoIcon() {
        return mPhotoIcon;
    }

    public long getCreationTimeMillis() {
        return mCreationTimeMillis;
    }

    public boolean isFetchRequested() {
        return mFetchRequested;
    }

    public void setFetchRequested(boolean fetchRequested) {
        mFetchRequested = fetchRequested;
    }

    public boolean willPhotoBeVisible() {
        return mPhotoVisible;
    }

    public void setPhotoVisible(boolean willPhotoBeVisible) {
        mPhotoVisible = willPhotoBeVisible;
    }

    public boolean providerHasInformation() {
        return mProviderHasInformation;
    }

    public void setProviderHasInformation(boolean providerHasInformation) {
        mProviderHasInformation = providerHasInformation;
    }

    public void setTag(Object tag) {
        mTag = tag;
    }

    public Object getTag() {
        return mTag;
    }
}

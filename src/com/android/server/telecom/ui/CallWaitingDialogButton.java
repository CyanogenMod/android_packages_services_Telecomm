/*
 * Copyright (C) 2016 The CyanogenMod Project
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
package com.android.server.telecom.ui;

import android.annotation.Nullable;
import android.content.Context;
import android.util.AttributeSet;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.android.server.telecom.R;

/**
 * <pre>
 *     Row button for call waiting dialog
 * </pre>
 *
 * @see {@link LinearLayout}
 */
public class CallWaitingDialogButton extends LinearLayout {

    // Constants
    public static final int BUTTON_HOLD = 0;
    public static final int BUTTON_END = 1;

    // Members
    private int mIconResId = 0;
    private int mTextResId = 0;
    private int mColorFilter = 0;

    // Views
    private ImageView mIconView;
    private TextView mTextView;

    public CallWaitingDialogButton(Context context) {
        super(context);
    }

    public CallWaitingDialogButton(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public CallWaitingDialogButton(Context context, @Nullable AttributeSet attrs,
            int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public CallWaitingDialogButton(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    public void onFinishInflate(){
        mIconView = (ImageView) findViewById(R.id.iv_icon);
        mTextView = (TextView) findViewById(R.id.tv_text);
        setIcon(mIconResId);
        setText(mTextResId);
    }

    public void setIcon(int resId) {
        mIconResId = resId;
        if (mIconView != null && mIconResId != 0) {
            mIconView.setColorFilter(mColorFilter);
            mIconView.setImageResource(mIconResId);
        }
    }

    public void setText(int resId) {
        mTextResId = resId;
        if (mTextView != null && mTextResId != 0) {
            mTextView.setText(mTextResId);
        }
    }

    public void setColorFilter(int colorFilter) {
        mColorFilter = colorFilter;
        if (mIconView != null) {
            mIconView.setColorFilter(mColorFilter);
        }
    }

}

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

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import com.android.server.telecom.R;

public class CallWaitingButtonAdapter extends ArrayAdapter<Integer> {

    private int mLayoutId;

    public CallWaitingButtonAdapter(Context context, int layoutRes, Integer[] data) {
        super(context, layoutRes, data);
        mLayoutId = layoutRes;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (convertView == null) {
            convertView = LayoutInflater.from(getContext()).inflate(mLayoutId, parent, false);
        }
        Integer btnId = getItem(position);
        CallWaitingDialogButton btn = (CallWaitingDialogButton) convertView;
        int colorFilter;
        switch (btnId) {
            case CallWaitingDialogButton.BUTTON_HOLD:
                btn.setIcon(R.drawable.ic_pause_white_24dp);
                btn.setText(R.string.call_waiting_dialog_hold_call);
                colorFilter = getContext().getResources().getColor(R.color.call_waiting_hold_tint);
                break;
            case CallWaitingDialogButton.BUTTON_END:
            default:
                btn.setIcon(R.drawable.ic_call_end_white_24dp);
                btn.setText(R.string.call_waiting_dialog_end_call);
                colorFilter = getContext().getResources().getColor(R.color.call_waiting_end_tint);
                break;
        }
        btn.setColorFilter(colorFilter);
        return convertView;
    }

}

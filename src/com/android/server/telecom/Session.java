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
 * limitations under the License
 */

package com.android.server.telecom;

import android.annotation.NonNull;

import java.util.ArrayList;

/**
 * The session that stores information about a thread's point of entry into the Telecom code that
 * persists until the thread exits Telecom.
 */
public class Session {

    public static final String START_SESSION = "START_SESSION";
    public static final String CREATE_SUBSESSION = "CREATE_SUBSESSION";
    public static final String CONTINUE_SUBSESSION = "CONTINUE_SUBSESSION";
    public static final String END_SUBSESSION = "END_SUBSESSION";
    public static final String END_SESSION = "END_SESSION";

    public static final int UNDEFINED = -1;

    private String mSessionId;
    private String mShortMethodName;
    private long mExecutionStartTimeMs;
    private long mExecutionEndTimeMs = UNDEFINED;
    private Session mParentSession;
    private ArrayList<Session> mChildSessions;
    private boolean mIsCompleted = false;
    private int mChildCounter = 0;
    private long mThreadId = 0;

    public Session(String sessionId, String shortMethodName, long startTimeMs, long threadID) {
        setSessionId(sessionId);
        setShortMethodName(shortMethodName);
        mExecutionStartTimeMs = startTimeMs;
        mParentSession = null;
        mChildSessions = new ArrayList<>(5);
        mThreadId = threadID;
    }

    public void setSessionId(@NonNull String sessionId) {
       if(sessionId == null) {
           mSessionId = "?";
       }
       mSessionId = sessionId;
    }

    public String getShortMethodName() {
        return mShortMethodName;
    }

    public void setShortMethodName(String shortMethodName) {
        if(shortMethodName == null) {
            shortMethodName = "";
        }
        mShortMethodName = shortMethodName;
    }

    public void setParentSession(Session parentSession) {
        mParentSession = parentSession;
    }

    public void addChild(Session childSession) {
        if(childSession != null) {
            mChildSessions.add(childSession);
        }
    }

    public void removeChild(Session child) {
        if(child != null) {
            mChildSessions.remove(child);
        }
    }

    public long getExecutionStartTimeMilliseconds() {
        return mExecutionStartTimeMs;
    }

    public void setExecutionStartTimeMs(long startTimeMs) {
        mExecutionStartTimeMs = startTimeMs;
    }

    public Session getParentSession() {
        return mParentSession;
    }

    public ArrayList<Session> getChildSessions() {
        return mChildSessions;
    }

    public boolean isSessionCompleted() {
        return mIsCompleted;
    }

    // Mark this session complete. This will be deleted by Log when all subsessions are complete
    // as well.
    public void markSessionCompleted(long executionEndTimeMs) {
        mExecutionEndTimeMs = executionEndTimeMs;
        mIsCompleted = true;
    }

    public long getLocalExecutionTime() {
        if(mExecutionEndTimeMs == UNDEFINED) {
            return UNDEFINED;
        }
        return mExecutionEndTimeMs - mExecutionStartTimeMs;
    }

    public synchronized String getNextChildId() {
        return String.valueOf(mChildCounter++);
    }

    public long getThreadId () {
        return mThreadId;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof Session)) {
            return false;
        }
        if (obj == this) {
            return true;
        }
        Session otherSession = (Session) obj;
        return (mSessionId.equals(otherSession.mSessionId)) &&
                (mShortMethodName.equals(otherSession.mShortMethodName)) &&
                mExecutionStartTimeMs == otherSession.mExecutionStartTimeMs &&
                mParentSession == otherSession.mParentSession &&
                mChildSessions.equals(otherSession.mChildSessions) &&
                mIsCompleted == otherSession.mIsCompleted &&
                mExecutionEndTimeMs == otherSession.mExecutionEndTimeMs &&
                mChildCounter == otherSession.mChildCounter &&
                mThreadId == otherSession.mThreadId;
    }

    // Builds full session id recursively
    private String getFullSessionId() {
        if(mParentSession == null) {
            return mSessionId;
        } else {
            return mParentSession.getFullSessionId() + "_" + mSessionId;
        }
    }

    // Print out the full Session tree from any subsession node
    public String printFullSessionTree() {
        // Get to the top of the tree
        Session topNode = this;
        while(topNode.getParentSession() != null) {
            topNode = topNode.getParentSession();
        }
        return topNode.printSessionTree();
    }

    // Recursively move down session tree using DFS, but print out each node when it is reached.
    public String printSessionTree() {
        StringBuilder sb = new StringBuilder();
        printSessionTree(0, sb);
        return sb.toString();
    }

    private void printSessionTree(int tabI, StringBuilder sb) {
        sb.append(toString());
        for (Session child : mChildSessions) {
            sb.append("\n");
            for(int i = 0; i <= tabI; i++) {
                sb.append("\t");
            }
            child.printSessionTree(tabI + 1, sb);
        }
    }

    @Override
    public String toString() {
        return mShortMethodName + "@" + getFullSessionId();
    }
}

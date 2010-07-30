/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.email.activity;

import com.android.email.Email;
import com.android.email.R;
import com.android.email.Utility;
import com.android.email.mail.MeetingInfo;
import com.android.email.mail.PackedString;
import com.android.email.provider.EmailContent.Message;
import com.android.email.service.EmailServiceConstants;

import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import java.security.InvalidParameterException;

/**
 * A {@link MessageViewFragmentBase} subclass for regular email messages.  (regular as in "not eml
 * files").
 *
 * See {@link MessageViewBase} for the class relation diagram.
 *
 * TODO Rename it to MessageViewFragment.  "2" was added so that GIT correctly detects the rename
 * from MessageViewFragment to MessageViewFragmentBase.
 */
public class MessageViewFragment2 extends MessageViewFragmentBase {

    private ImageView mFavoriteIcon;
    private View mInviteSection;

    // calendar meeting invite answers
    private TextView mMeetingYes;
    private TextView mMeetingMaybe;
    private TextView mMeetingNo;
    private int mPreviousMeetingResponse = -1;

    private Drawable mFavoriteIconOn;
    private Drawable mFavoriteIconOff;

    private long mMessageIdToOpen = -1;

    /**
     * This class has more call backs than {@link MessageViewFragmentBase}.
     *
     * - EML files can't be "mark unread".
     * - EML files can't have the invite buttons or the view in calender link.
     *   Note EML files can have ICS (calendar invitation) files, but we don't treat them as
     *   invites.  (Only exchange provider sets the FLAG_INCOMING_MEETING_INVITE
     *   flag.)
     *   It'd be weird to respond to an invitation in an EML that might not be addressed to you...
     */
    public interface Callback extends MessageViewFragmentBase.Callback {
        /** Called when the "view in calendar" link is clicked. */
        public void onCalendarLinkClicked(long epochEventStartTime);

        /**
         * Called when a calender response button is clicked.
         *
         * @param response one of {@link EmailServiceConstants#MEETING_REQUEST_ACCEPTED},
         * {@link EmailServiceConstants#MEETING_REQUEST_DECLINED}, or
         * {@link EmailServiceConstants#MEETING_REQUEST_TENTATIVE}.
         */
        public void onRespondedToInvite(int response);

        /** Called when the current message is set unread. */
        public void onMessageSetUnread();
    }

    public static final class EmptyCallback extends MessageViewFragmentBase.EmptyCallback
            implements Callback {
        public static final Callback INSTANCE = new EmptyCallback();

        @Override
        public void onCalendarLinkClicked(long epochEventStartTime) {
        }

        @Override
        public void onMessageSetUnread() {
        }

        @Override
        public void onRespondedToInvite(int response) {
        }
    }

    private Callback mCallback = EmptyCallback.INSTANCE;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final Resources res = getActivity().getResources();
        mFavoriteIconOn = res.getDrawable(R.drawable.btn_star_big_buttonless_on);
        mFavoriteIconOff = res.getDrawable(R.drawable.btn_star_big_buttonless_off);
    }

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        final View view = super.onCreateView(inflater, container, savedInstanceState);

        mFavoriteIcon = (ImageView) view.findViewById(R.id.favorite);
        mInviteSection = view.findViewById(R.id.invite_section);
        mFavoriteIcon.setOnClickListener(this);
        mMeetingYes = (TextView) view.findViewById(R.id.accept);
        mMeetingMaybe = (TextView) view.findViewById(R.id.maybe);
        mMeetingNo = (TextView) view.findViewById(R.id.decline);

        mMeetingYes.setOnClickListener(this);
        mMeetingMaybe.setOnClickListener(this);
        mMeetingNo.setOnClickListener(this);
        view.findViewById(R.id.invite_link).setOnClickListener(this);

        return view;
    }

    public void setCallback(Callback callback) {
        mCallback = (callback == null) ? EmptyCallback.INSTANCE : callback;
        super.setCallback(mCallback);
    }

    /** Called by activities to set an id of a message to open. */
    public void openMessage(long messageId) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "MessageViewFragment openMessage");
        }
        if (messageId == -1) {
            throw new InvalidParameterException();
        }
        mMessageIdToOpen = messageId;
        openMessageIfStarted();
    }

    @Override
    protected boolean isMessageSpecified() {
        return mMessageIdToOpen != -1;
    }

    @Override
    protected Message openMessageSync() {
        return Message.restoreMessageWithId(getActivity(), mMessageIdToOpen);
    }

    /**
     * Toggle favorite status and write back to provider
     */
    private void onClickFavorite() {
        Message message = getMessage();

        // Update UI
        boolean newFavorite = ! message.mFlagFavorite;
        mFavoriteIcon.setImageDrawable(newFavorite ? mFavoriteIconOn : mFavoriteIconOff);

        // Update provider
        message.mFlagFavorite = newFavorite;
        getController().setMessageFavorite(message.mId, newFavorite);
    }

    /**
     * Set message read/unread.
     */
    public void onMarkMessageAsRead(boolean isRead) {
        Message message = getMessage();
        if (message.mFlagRead != isRead) {
            message.mFlagRead = isRead;
            getController().setMessageRead(message.mId, isRead);
            if (!isRead) { // Became unread.  We need to close the message.
                mCallback.onMessageSetUnread();
            }
        }
    }

    /**
     * Send a service message indicating that a meeting invite button has been clicked.
     */
    private void onRespondToInvite(int response, int toastResId) {
        Message message = getMessage();
        // do not send twice in a row the same response
        if (mPreviousMeetingResponse != response) {
            getController().sendMeetingResponse(message.mId, response);
            mPreviousMeetingResponse = response;
        }
        Utility.showToast(getActivity(), toastResId);
        mCallback.onRespondedToInvite(response);
    }

    private void onInviteLinkClicked() {
        Message message = getMessage();
        String startTime = new PackedString(message.mMeetingInfo).get(MeetingInfo.MEETING_DTSTART);
        if (startTime != null) {
            long epochTimeMillis = Utility.parseEmailDateTimeToMillis(startTime);
            mCallback.onCalendarLinkClicked(epochTimeMillis);
        } else {
            Email.log("meetingInfo without DTSTART " + message.mMeetingInfo);
        }
    }

    @Override
    public void onClick(View view) {
        if (!isMessageOpen()) {
            return; // Ignore.
        }
        switch (view.getId()) {
            case R.id.favorite:
                onClickFavorite();
                return;
            case R.id.accept:
                onRespondToInvite(EmailServiceConstants.MEETING_REQUEST_ACCEPTED,
                         R.string.message_view_invite_toast_yes);
                return;
            case R.id.maybe:
                onRespondToInvite(EmailServiceConstants.MEETING_REQUEST_TENTATIVE,
                         R.string.message_view_invite_toast_maybe);
                return;
            case R.id.decline:
                onRespondToInvite(EmailServiceConstants.MEETING_REQUEST_DECLINED,
                         R.string.message_view_invite_toast_no);
                return;
            case R.id.invite_link:
                onInviteLinkClicked();
                return;
        }
        super.onClick(view);
    }

    /**
     * {@inheritDoc}
     *
     * Mark the current as unread.
     */
    @Override
    protected void onPostLoadBody() {
        onMarkMessageAsRead(true);
    }

    /**
     * {@inheritDoc}
     *
     * - Update the favorite star icon.
     * - Show the invite section if necessary.
     */
    @Override
    protected void reloadUiFromMessage(Message message, boolean okToFetch) {
        super.reloadUiFromMessage(message, okToFetch);

        mFavoriteIcon.setImageDrawable(message.mFlagFavorite ? mFavoriteIconOn : mFavoriteIconOff);
        // Show the message invite section if we're an incoming meeting invitation only
        mInviteSection.setVisibility((message.mFlags & Message.FLAG_INCOMING_MEETING_INVITE) != 0 ?
                View.VISIBLE : View.GONE);
    }
}
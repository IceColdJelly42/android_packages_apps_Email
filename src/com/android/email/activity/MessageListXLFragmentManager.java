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

import android.app.Fragment;
import android.app.FragmentTransaction;
import android.os.Bundle;
import android.util.Log;

import java.security.InvalidParameterException;
import java.util.ArrayList;

/*
  TODO: When opening a mailbox I see this:
D Email   : com.android.email.activity.MailboxListFragment openMailboxes
D Email   : com.android.email.activity.MailboxListFragment onCreate *1 <- Why second instance???
D Email   : com.android.email.activity.MailboxListFragment onActivityCreated
D Email   : com.android.email.activity.MailboxListFragment onStart
D Email   : com.android.email.activity.MailboxListFragment onResume
 */

/**
 * A class manages what are showing on {@link MessageListXL} (i.e. account id, mailbox id, and
 * message id), and show/hide fragments accordingly.
 *
 * TODO: Test it.  It's testable if we implement MockFragmentTransaction, which may be too early
 * to do so at this point.  (API may not be stable enough yet.)
 *
 * TODO: See if the "restored fragments" hack can be removed if the fragments restore their
 * state by themselves.  (That'll require phone activity changes as well.)
 */
class MessageListXLFragmentManager {
    private static final String BUNDLE_KEY_ACCOUNT_ID = "MessageListXl.state.account_id";
    private static final String BUNDLE_KEY_MAILBOX_ID = "MessageListXl.state.mailbox_id";
    private static final String BUNDLE_KEY_MESSAGE_ID = "MessageListXl.state.message_id";

    /**
     * List of fragments that are restored by the framework when the activity is being re-created.
     * (e.g. for orientation change)
     */
    private final ArrayList<Fragment> mRestoredFragments = new ArrayList<Fragment>();

    private boolean mIsActivityStarted;

    /** Current account id. (-1 = not selected) */
    private long mAccountId = -1;

    /** Current mailbox id. (-1 = not selected) */
    private long mMailboxId = -1;

    /** Current message id. (-1 = not selected) */
    private long mMessageId = -1;

    private MailboxListFragment mMailboxListFragment;
    private MessageListFragment mMessageListFragment;
    private MessageViewFragment2 mMessageViewFragment;

    private MailboxListFragment.Callback mMailboxListFragmentCallback;
    private MessageListFragment.Callback mMessageListFragmentCallback;
    private MessageViewFragment2.Callback mMessageViewFragmentCallback;

    /**
     * The interface that {@link MessageListXL} implements.  We don't call its methods directly,
     * in the hope that it'll make writing tests easier.
     */
    public interface TargetActivity {
        public FragmentTransaction openFragmentTransaction();
        /**
         * Called when MessageViewFragment is being shown.
         * {@link MessageListXL} uses it to show the navigation buttons.
         */
        public void onMessageViewFragmentShown(long accountId, long mailboxId, long messageId);
        /**
         * Called when MessageViewFragment is being hidden.
         * {@link MessageListXL} uses it to hide the navigation buttons.
         */
        public void onMessageViewFragmentHidden();
    }

    private final TargetActivity mTargetActivity;

    public MessageListXLFragmentManager(TargetActivity targetActivity) {
        mTargetActivity = targetActivity;
    }

    /** Set callback for fragment. */
    public void setMailboxListFragmentCallback(
            MailboxListFragment.Callback mailboxListFragmentCallback) {
        mMailboxListFragmentCallback = mailboxListFragmentCallback;
    }

    /** Set callback for fragment. */
    public void setMessageListFragmentCallback(
            MessageListFragment.Callback messageListFragmentCallback) {
        mMessageListFragmentCallback = messageListFragmentCallback;
    }

    /** Set callback for fragment. */
    public void setMessageViewFragmentCallback(
            MessageViewFragment2.Callback messageViewFragmentCallback) {
        mMessageViewFragmentCallback = messageViewFragmentCallback;
    }

    public long getAccountId() {
        return mAccountId;
    }

    public long getMailboxId() {
        return mMailboxId;
    }

    public long getMessageId() {
        return mMessageId;
    }

    public boolean isAccountSelected() {
        return getAccountId() != -1;
    }

    public boolean isMailboxSelected() {
        return getMailboxId() != -1;
    }

    public boolean isMessageSelected() {
        return getMessageId() != -1;
    }

    /**
     * Called from {@link MessageListXL#onStart()}.
     *
     * When the activity is being started, we initialize the "restored" fragments.
     *
     * @see #initRestoredFragments
     */
    public void onStart() {
        if (mIsActivityStarted) {
            return;
        }
        mIsActivityStarted = true;
        initRestoredFragments();
    }

    /**
     * Called from {@link MessageListXL#onStop()}.
     */
    public void onStop() {
        if (!mIsActivityStarted) {
            return;
        }
        mIsActivityStarted = false;
    }

    public void onSaveInstanceState(Bundle outState) {
        outState.putLong(BUNDLE_KEY_ACCOUNT_ID, mAccountId);
        outState.putLong(BUNDLE_KEY_MAILBOX_ID, mMailboxId);
        outState.putLong(BUNDLE_KEY_MESSAGE_ID, mMessageId);
    }

    public void loadState(Bundle savedInstanceState) {
        mAccountId = savedInstanceState.getLong(BUNDLE_KEY_ACCOUNT_ID, -1);
        mMailboxId = savedInstanceState.getLong(BUNDLE_KEY_MAILBOX_ID, -1);
        mMessageId = savedInstanceState.getLong(BUNDLE_KEY_MESSAGE_ID, -1);
        if (Email.DEBUG) {
            Log.d(Email.LOG_TAG, "MessageListXLFragmentManager: Restoring "
                    + mAccountId + "," + mMailboxId + "," + mMessageId);
        }
    }

    /**
     * Called by {@link MessageListXL#onAttachFragment}.
     *
     * If the activity is not started yet, just store it in {@link #mRestoredFragments} to
     * initialize it later.
     */
    public void onAttachFragment(Fragment fragment) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "MessageListXLFragmentManager.onAttachFragment fragment=" +
                    fragment.getClass());
        }
        if (!mIsActivityStarted) {
            mRestoredFragments.add(fragment);
            return;
        }
        if (fragment instanceof MailboxListFragment) {
            updateMailboxListFragment((MailboxListFragment) fragment);
        } else if (fragment instanceof MessageListFragment) {
            updateMessageListFragment((MessageListFragment) fragment);
        } else if (fragment instanceof MessageViewFragment2) {
            updateMessageViewFragment((MessageViewFragment2) fragment);
        }
    }

    /**
     * Called by {@link #setActivityStarted} to initialize the "restored" fragments.
     */
    private void initRestoredFragments() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "MessageListXLFragmentManager.initRestoredFragments");
        }
        for (Fragment f : mRestoredFragments) {
            onAttachFragment(f);
        }
        mRestoredFragments.clear();
    }

    /**
     * Call it to select an account.
     */
    public void selectAccount(long accountId) {
        // TODO Handle "combined mailboxes".
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "selectAccount mAccountId=" + accountId);
        }
        if (accountId == -1) {
            throw new InvalidParameterException();
        }
        if (mAccountId == accountId) {
            return;
        }

        // Update members.
        mAccountId = accountId;
        mMailboxId = -1;
        mMessageId = -1;

        // Replace fragments if necessary.
        final FragmentTransaction ft = mTargetActivity.openFragmentTransaction();
        if (mMailboxListFragment == null) {
            // The left pane not set yet.

            // We can put it directly in the layout file, but then it'll have slightly different
            // lifecycle as the other fragments.  Let's create it here this way for now.
            MailboxListFragment f = new MailboxListFragment();
            ft.replace(R.id.left_pane, f);
        }
        if (mMessageListFragment != null) {
            ft.remove(mMessageListFragment);
            mMessageListFragment = null;
        }
        if (mMessageViewFragment != null) {
            ft.remove(mMessageViewFragment);
            mMessageViewFragment = null;
            mTargetActivity.onMessageViewFragmentHidden(); // Don't forget to tell the activity.
        }
        ft.commit();

        // If it's already shown, update it.
        if (mMailboxListFragment != null) {
            updateMailboxListFragment(mMailboxListFragment);
        } else {
            Log.w(Email.LOG_TAG, "MailboxListFragment not set yet.");
        }

        // TODO Open the inbox on the right pane.
    }

    private void updateMailboxListFragment(MailboxListFragment fragment) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "updateMailboxListFragment mAccountId=" + mAccountId);
        }
        if (mAccountId == -1) { // Shouldn't happen
            throw new RuntimeException();
        }
        mMailboxListFragment = fragment;
        fragment.setCallback(mMailboxListFragmentCallback);
        fragment.openMailboxes(mAccountId);
    }

    /**
     * Call it to select a mailbox.
     *
     * We assume the mailbox selected here belongs to the account selected with
     * {@link #selectAccount}.
     */
    public void selectMailbox(long mailboxId) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "selectMailbox mMailboxId=" + mailboxId);
        }
        if (mailboxId == -1) {
            throw new InvalidParameterException();
        }
        if ((mMailboxId == mailboxId) && !isMessageSelected()) {
            return;
        }

        // Update members.
        mMailboxId = mailboxId;
        mMessageId = -1;

        // Update fragments.
        if (mMessageListFragment == null) {
            MessageListFragment f = new MessageListFragment();
            mTargetActivity.openFragmentTransaction().replace(R.id.right_pane, f).commit();

            if (mMessageViewFragment != null) {
                // Message view will disappear.
                mMessageViewFragment = null;
                mTargetActivity.onMessageViewFragmentHidden(); // Don't forget to tell the activity.
            }
        } else {
            updateMessageListFragment(mMessageListFragment);
        }
    }

    private void updateMessageListFragment(MessageListFragment fragment) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "updateMessageListFragment mMailboxId=" + mMailboxId);
        }
        if (mAccountId == -1 || mMailboxId == -1) { // Shouldn't happen
            throw new RuntimeException();
        }
        mMessageListFragment = fragment;

        fragment.setCallback(mMessageListFragmentCallback);
        fragment.openMailbox(mAccountId, mMailboxId);
    }

    /**
     * Call it to select a mailbox.
     *
     * We assume the message passed here belongs to the account/mailbox selected with
     * {@link #selectAccount} and {@link #selectMailbox}.
     */
    public void selectMessage(long messageId) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "selectMessage messageId=" + messageId);
        }
        if (messageId == -1) {
            throw new InvalidParameterException();
        }
        if (mMessageId == messageId) {
            return;
        }

        // Update member.
        mMessageId = messageId;

        // Update fragments.
        if (mMessageViewFragment == null) {
            MessageViewFragment2 f = new MessageViewFragment2();

            // TODO We want to support message view -> [back] -> message list, but the back behavior
            // with addToBackStack() is not too clear.  We do it manually for now.
            // See MessageListXL.onBackPressed().
            mTargetActivity.openFragmentTransaction().replace(R.id.right_pane, f)
//                    .addToBackStack(null)
                    .commit();
            mMessageListFragment = null;
        } else {
            updateMessageViewFragment(mMessageViewFragment);
        }
    }

    private void updateMessageViewFragment(MessageViewFragment2 fragment) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "updateMessageViewFragment messageId=" + mMessageId);
        }
        if (mAccountId == -1 || mMailboxId == -1 || mMessageId == -1) { // Shouldn't happen
            throw new RuntimeException();
        }
        mMessageViewFragment = fragment;
        fragment.setCallback(mMessageViewFragmentCallback);
        fragment.openMessage(mMessageId);
        mTargetActivity.onMessageViewFragmentShown(getAccountId(), getMailboxId(), getMessageId());
    }
}
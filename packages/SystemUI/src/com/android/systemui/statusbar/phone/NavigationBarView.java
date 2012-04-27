/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Message;
import android.os.ServiceManager;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.Slog;
import android.view.animation.AccelerateInterpolator;
import android.view.Display;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Surface;
import android.view.WindowManager;
import android.widget.LinearLayout;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.StringBuilder;

import com.android.internal.statusbar.IStatusBarService;
import com.android.systemui.statusbar.policy.KeyButtonView;

import com.android.systemui.R;

public class NavigationBarView extends LinearLayout {
    final static boolean DEBUG = false;
    final static String TAG = "PhoneStatusBar/NavigationBarView";

    final static boolean DEBUG_DEADZONE = false;

    final static boolean NAVBAR_ALWAYS_AT_RIGHT = true;

    final static boolean ANIMATE_HIDE_TRANSITION = false; // turned off because it introduces unsightly delay when videos goes to full screen

    private boolean mShowMenuButton;
    private boolean mShowSearchButton;
    private boolean isPortrait;
    private int mLightsOut;

    protected IStatusBarService mBarService;
    final Display mDisplay;
    View mCurrentView = null;
    View[] mRotatedViews = new View[4];

    int mBarSize, mSlotOne, mSlotTwo, mSlotThree, mSlotFour, mSlotFive;
    boolean mVertical;

    boolean mHidden, mLowProfile, mShowMenu;
    int mDisabledFlags = 0;

    // workaround for LayoutTransitions leaving the nav buttons in a weird state (bug 5549288)
    final static boolean WORKAROUND_INVALID_LAYOUT = true;
    final static int MSG_CHECK_INVALID_LAYOUT = 8686;

    private class H extends Handler {
        public void handleMessage(Message m) {
            switch (m.what) {
                case MSG_CHECK_INVALID_LAYOUT:
                    final String how = "" + m.obj;
                    final int w = getWidth();
                    final int h = getHeight();
                    final int vw = mCurrentView.getWidth();
                    final int vh = mCurrentView.getHeight();

                    if (h != vh || w != vw) {
                        Slog.w(TAG, String.format(
                            "*** Invalid layout in navigation bar (%s this=%dx%d cur=%dx%d)",
                            how, w, h, vw, vh));
                        if (WORKAROUND_INVALID_LAYOUT) {
                            requestLayout();
                        }
                    }
                    break;
            }
        }
    }

    private H mHandler = new H();

    public View getRecentsButton() {
        return mCurrentView.findViewWithTag("recent");
    }

    public View getMenuStock() {
        return mCurrentView.findViewById(R.id.menu_stock);
    }

    public View getOutsideSpacerSmall() {
        return mCurrentView.findViewById(R.id.outside_spacer_small);
    }

    public View getOutsideSpacer() {
        return mCurrentView.findViewById(R.id.outside_spacer);
    }

    public View getInsideSpacerOne() {
        return mCurrentView.findViewById(R.id.inside_spacer_one);
    }

    public View getInsideSpacerTwo() {
        return mCurrentView.findViewById(R.id.inside_spacer_two);
    }

    public View getMenuSpacer() {
        return mCurrentView.findViewById(R.id.menu_spacer);
    }

    public NavigationBarView(Context context, AttributeSet attrs) {
        super(context, attrs);

        mHidden = false;

        mDisplay = ((WindowManager)context.getSystemService(
                Context.WINDOW_SERVICE)).getDefaultDisplay();
        mBarService = IStatusBarService.Stub.asInterface(
                ServiceManager.getService(Context.STATUS_BAR_SERVICE));

        final Resources res = mContext.getResources();
        mBarSize = res.getDimensionPixelSize(R.dimen.navigation_bar_size);
        mVertical = false;
        mShowMenu = false;
    }

    View.OnTouchListener mLightsOutListener = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent ev) {
            if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                // even though setting the systemUI visibility below will turn these views
                // on, we need them to come up faster so that they can catch this motion
                // event
                setLowProfile(false, false, false);

                try {
                    mBarService.setSystemUiVisibility(0);
                } catch (android.os.RemoteException ex) {
                }
            }
            return false;
        }
    };

    public void setDisabledFlags(int disabledFlags) {
        setDisabledFlags(disabledFlags, false);
    }

    public void setDisabledFlags(int disabledFlags, boolean force) {
        if (!force && mDisabledFlags == disabledFlags) return;

        mDisabledFlags = disabledFlags;

        final boolean disableButtons = ((disabledFlags & View.STATUS_BAR_DISABLE_HOME) != 0);

        KeyButtonView oneView = (KeyButtonView) mCurrentView.findViewById(R.id.slot_one);
        KeyButtonView twoView = (KeyButtonView) mCurrentView.findViewById(R.id.slot_two);
        KeyButtonView threeView = (KeyButtonView) mCurrentView.findViewById(R.id.slot_three);
        KeyButtonView fourView = (KeyButtonView) mCurrentView.findViewById(R.id.slot_four);
        KeyButtonView fiveView = (KeyButtonView) mCurrentView.findViewById(R.id.slot_five);

        twoView.setVisibility(disableButtons ? View.INVISIBLE : View.VISIBLE);
        threeView.setVisibility(disableButtons ? View.INVISIBLE : View.VISIBLE);
        fourView.setVisibility(disableButtons ? View.INVISIBLE : View.VISIBLE);

        if (mSlotOne != 0) {
            oneView.setVisibility(disableButtons ? View.INVISIBLE : View.VISIBLE);
        } else {
            oneView.setVisibility(View.GONE);
        }

        if (mSlotFive != 0) {
            fiveView.setVisibility(disableButtons ? View.INVISIBLE : View.VISIBLE);
        } else {
            fiveView.setVisibility(View.GONE);
        }
    }

    public void setMenuVisibility(final boolean show) {
        setMenuVisibility(show, false);
    }

    public void setMenuVisibility(final boolean show, final boolean force) {
        if (!force && mShowMenu == show) return;

        mShowMenu = show;

        if ((mSlotOne != 0) && (mSlotFive != 0)) {
            getMenuStock().setVisibility(View.GONE);
        } else if ((mSlotOne == 1) || (mSlotTwo == 0) || 
                   (mSlotThree == 0) || (mSlotFour == 0) || 
                   (mSlotFive == 1)) {
            getMenuStock().setVisibility(View.GONE);
        } else {
            getMenuStock().setVisibility(mShowMenu ? View.VISIBLE 
                    : View.INVISIBLE);
        }
    }

    public void setLowProfile(final boolean lightsOut) {
        setLowProfile(lightsOut, true, false);
    }

    public void setLowProfile(final boolean lightsOut, final boolean animate, final boolean force) {
        if (!force && lightsOut == mLowProfile) return;

        mLowProfile = lightsOut;

        if ((mSlotOne != 0) && (mSlotFive != 0)) {
            mLightsOut = R.id.lights_out_5;
        } else if ((mSlotOne == 0) && (mSlotFive == 0)) {
            mLightsOut = R.id.lights_out;
        } else {
            mLightsOut = R.id.lights_out_4;
        }

        if (DEBUG) Slog.d(TAG, "setting lights " + (lightsOut?"out":"on"));

        final View navButtons = mCurrentView.findViewById(R.id.nav_buttons);
        final View lowLights = mCurrentView.findViewById(mLightsOut);

        // ok, everyone, stop it right there
        navButtons.animate().cancel();
        lowLights.animate().cancel();

        if (!animate) {
            navButtons.setAlpha(lightsOut ? 0f : 1f);

            lowLights.setAlpha(lightsOut ? 1f : 0f);
            lowLights.setVisibility(lightsOut ? View.VISIBLE : View.GONE);
        } else {
            navButtons.animate()
                .alpha(lightsOut ? 0f : 1f)
                .setDuration(lightsOut ? 600 : 200)
                .start();

            lowLights.setOnTouchListener(mLightsOutListener);
            if (lowLights.getVisibility() == View.GONE) {
                lowLights.setAlpha(0f);
                lowLights.setVisibility(View.VISIBLE);
            }
            lowLights.animate()
                .alpha(lightsOut ? 1f : 0f)
                .setStartDelay(lightsOut ? 500 : 0)
                .setDuration(lightsOut ? 1000 : 300)
                .setInterpolator(new AccelerateInterpolator(2.0f))
                .setListener(lightsOut ? null : new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator _a) {
                        lowLights.setVisibility(View.GONE);
                    }
                })
                .start();
        }
    }

    public void setHidden(final boolean hide) {
        if (hide == mHidden) return;

        mHidden = hide;
        Slog.d(TAG,
            (hide ? "HIDING" : "SHOWING") + " navigation bar");

        // bring up the lights no matter what
        setLowProfile(false);
    }

    public void onFinishInflate() {
        mRotatedViews[Surface.ROTATION_0] = 
        mRotatedViews[Surface.ROTATION_180] = findViewById(R.id.rot0);

        mRotatedViews[Surface.ROTATION_90] = findViewById(R.id.rot90);
        
        mRotatedViews[Surface.ROTATION_270] = NAVBAR_ALWAYS_AT_RIGHT
                                                ? findViewById(R.id.rot90)
                                                : findViewById(R.id.rot270);

        for (View v : mRotatedViews) {
            // this helps avoid drawing artifacts with glowing navigation keys 
            ViewGroup group = (ViewGroup) v.findViewById(R.id.nav_buttons);
            group.setMotionEventSplittingEnabled(false);
        }
        mCurrentView = mRotatedViews[Surface.ROTATION_0];
    }

    public void reorient() {
        final int rot = mDisplay.getRotation();
        for (int i=0; i<4; i++) {
            mRotatedViews[i].setVisibility(View.GONE);
        }
        mCurrentView = mRotatedViews[rot];
        mCurrentView.setVisibility(View.VISIBLE);
        mVertical = (rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270);

        setNavButtonViews();

        // force the low profile & disabled states into compliance
        setLowProfile(mLowProfile, false, true /* force */);
        setDisabledFlags(mDisabledFlags, true /* force */);
        setMenuVisibility(mShowMenu, true /* force */);

        if (DEBUG_DEADZONE) {
            mCurrentView.findViewById(R.id.deadzone).setBackgroundColor(0x808080FF);
        }

        if (DEBUG) {
            Slog.d(TAG, "reorient(): rot=" + mDisplay.getRotation());
        }
    }

    public void setNavButtonViews() {
        ContentResolver resolver = mContext.getContentResolver();
        mSlotOne = (Settings.System.getInt(resolver,
                Settings.System.NAV_BUTTONS_SLOT_ONE, 0));
        mSlotTwo = (Settings.System.getInt(resolver,
                Settings.System.NAV_BUTTONS_SLOT_TWO, 1));
        mSlotThree = (Settings.System.getInt(resolver,
                Settings.System.NAV_BUTTONS_SLOT_THREE, 2));
        mSlotFour = (Settings.System.getInt(resolver,
                Settings.System.NAV_BUTTONS_SLOT_FOUR, 3));
        mSlotFive = (Settings.System.getInt(resolver,
                Settings.System.NAV_BUTTONS_SLOT_FIVE, 0));

        // Let's start clean
        ViewGroup navButtonView = ((ViewGroup) mCurrentView.findViewById(R.id.nav_buttons));
        for (int nb = 0; nb < navButtonView.getChildCount(); nb++) {
            if (!(navButtonView.getChildAt(nb) instanceof KeyButtonView)) {
                navButtonView.getChildAt(nb).setVisibility(View.GONE);
            }
        }

        KeyButtonView oneView = (KeyButtonView) mCurrentView.findViewById(R.id.slot_one);
        KeyButtonView twoView = (KeyButtonView) mCurrentView.findViewById(R.id.slot_two);
        KeyButtonView threeView = (KeyButtonView) mCurrentView.findViewById(R.id.slot_three);
        KeyButtonView fourView = (KeyButtonView) mCurrentView.findViewById(R.id.slot_four);
        KeyButtonView fiveView = (KeyButtonView) mCurrentView.findViewById(R.id.slot_five);

        if ((mSlotOne != 0) && (mSlotFive != 0)) {
            getOutsideSpacer().setVisibility(View.GONE);
            getMenuStock().setVisibility(View.GONE);
        } else {
            getOutsideSpacer().setVisibility(View.INVISIBLE);
            getMenuStock().setVisibility(View.INVISIBLE);
        }

        if ((mSlotOne == 0) && (mSlotFive == 0)) {
            getOutsideSpacerSmall().setVisibility(View.INVISIBLE);
            getInsideSpacerOne().setVisibility(View.INVISIBLE);
            getInsideSpacerTwo().setVisibility(View.INVISIBLE);
            getMenuSpacer().setVisibility(View.INVISIBLE);
        } else {
            getOutsideSpacerSmall().setVisibility(View.GONE);
            getInsideSpacerOne().setVisibility(View.GONE);
            getInsideSpacerTwo().setVisibility(View.GONE);
            getMenuSpacer().setVisibility(View.GONE);
        }

        int mMenuImg = (R.drawable.ic_sysbar_menu);
        int mMenuImgLand = (R.drawable.ic_sysbar_menu_land);
        int mBackImg = (R.drawable.ic_sysbar_back);
        int mBackImgLand = (R.drawable.ic_sysbar_back_land);
        int mHomeImg = (R.drawable.ic_sysbar_home);
        int mHomeImgLand = (R.drawable.ic_sysbar_home_land);
        int mRecentImg = (R.drawable.ic_sysbar_recent);
        int mRecentImgLand = (R.drawable.ic_sysbar_recent_land);
        int mSearchImg = (R.drawable.ic_sysbar_search);
        int mSearchImgLand = (R.drawable.ic_sysbar_search_land);

        switch (mSlotOne) {
            case 1:
                oneView.setTag("menu");
                oneView.setContentDescription(mContext.getResources().getString(R.string.accessibility_menu));
                oneView.setMCode(KeyEvent.KEYCODE_MENU);
                oneView.setImageResource(isPortrait ? mMenuImg : mMenuImgLand);
                break;
            case 2:
                oneView.setTag("back");
                oneView.setContentDescription(mContext.getResources().getString(R.string.accessibility_back));
                oneView.setMCode(KeyEvent.KEYCODE_BACK);
                oneView.setImageResource(isPortrait ? mBackImg : mBackImgLand);
                break;
            case 3:
                oneView.setTag("home");
                oneView.setContentDescription(mContext.getResources().getString(R.string.accessibility_home));
                oneView.setMCode(KeyEvent.KEYCODE_HOME);
                oneView.setImageResource(isPortrait ? mHomeImg : mHomeImgLand);
                break;
            case 4:
                oneView.setTag("recent");
                oneView.setContentDescription(mContext.getResources().getString(R.string.accessibility_recent));
                oneView.setMCode(0);
                oneView.setImageResource(isPortrait ? mRecentImg : mRecentImgLand);
                break;
            case 5:
                oneView.setTag("search");
                oneView.setContentDescription(mContext.getResources().getString(R.string.accessibility_search));
                oneView.setMCode(KeyEvent.KEYCODE_SEARCH);
                oneView.setImageResource(isPortrait ? mSearchImg : mSearchImgLand);
                break;
            case 6:
                oneView.setTag("media_previous");
                oneView.setContentDescription(mContext.getResources().getString(R.string.accessibility_media_previous));
                oneView.setMCode(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
                oneView.setImageResource(isPortrait ? R.drawable.ic_sysbar_media_previous : R.drawable.ic_sysbar_media_previous_land);
                break;
            case 7:
                oneView.setTag("media_next");
                oneView.setContentDescription(mContext.getResources().getString(R.string.accessibility_media_next));
                oneView.setMCode(KeyEvent.KEYCODE_MEDIA_NEXT);
                oneView.setImageResource(isPortrait ? R.drawable.ic_sysbar_media_next : R.drawable.ic_sysbar_media_next_land);
                break;
            default:
            case 0:
                oneView.setVisibility(View.GONE);
                break;
        }

        switch (mSlotTwo) {
            case 0:
                twoView.setTag("menu");
                twoView.setContentDescription(mContext.getResources().getString(R.string.accessibility_menu));
                twoView.setMCode(KeyEvent.KEYCODE_MENU);
                twoView.setImageResource(isPortrait ? mMenuImg : mMenuImgLand);
                break;
            case 2:
                twoView.setTag("home");
                twoView.setContentDescription(mContext.getResources().getString(R.string.accessibility_home));
                twoView.setMCode(KeyEvent.KEYCODE_HOME);
                twoView.setImageResource(isPortrait ? mHomeImg : mHomeImgLand);
                break;
            case 3:
                twoView.setTag("recent");
                twoView.setContentDescription(mContext.getResources().getString(R.string.accessibility_recent));
                twoView.setMCode(0);
                twoView.setImageResource(isPortrait ? mRecentImg : mRecentImgLand);
                break;
            case 4:
                twoView.setTag("search");
                twoView.setContentDescription(mContext.getResources().getString(R.string.accessibility_search));
                twoView.setMCode(KeyEvent.KEYCODE_SEARCH);
                twoView.setImageResource(isPortrait ? mSearchImg : mSearchImgLand);
                break;
            case 5:
                twoView.setTag("media_previous");
                twoView.setContentDescription(mContext.getResources().getString(R.string.accessibility_media_previous));
                twoView.setMCode(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
                twoView.setImageResource(isPortrait ? R.drawable.ic_sysbar_media_previous : R.drawable.ic_sysbar_media_previous_land);
                break;
            case 6:
                twoView.setTag("media_next");
                twoView.setContentDescription(mContext.getResources().getString(R.string.accessibility_media_next));
                twoView.setMCode(KeyEvent.KEYCODE_MEDIA_NEXT);
                twoView.setImageResource(isPortrait ? R.drawable.ic_sysbar_media_next : R.drawable.ic_sysbar_media_next_land);
                break;
            default:
            case 1:
                twoView.setTag("back");
                twoView.setContentDescription(mContext.getResources().getString(R.string.accessibility_back));
                twoView.setMCode(KeyEvent.KEYCODE_BACK);
                twoView.setImageResource(isPortrait ? mBackImg : mBackImgLand);
                break;
        }

        switch (mSlotThree) {
            case 0:
                threeView.setTag("menu");
                threeView.setContentDescription(mContext.getResources().getString(R.string.accessibility_menu));
                threeView.setMCode(KeyEvent.KEYCODE_MENU);
                threeView.setImageResource(isPortrait ? mMenuImg : mMenuImgLand);
                break;
            case 1:
                threeView.setTag("back");
                threeView.setContentDescription(mContext.getResources().getString(R.string.accessibility_back));
                threeView.setMCode(KeyEvent.KEYCODE_BACK);
                threeView.setImageResource(isPortrait ? mBackImg : mBackImgLand);
                break;
            case 3:
                threeView.setTag("recent");
                threeView.setContentDescription(mContext.getResources().getString(R.string.accessibility_recent));
                threeView.setMCode(0);
                threeView.setImageResource(isPortrait ? mRecentImg : mRecentImgLand);
                break;
            case 4:
                threeView.setTag("search");
                threeView.setContentDescription(mContext.getResources().getString(R.string.accessibility_search));
                threeView.setMCode(KeyEvent.KEYCODE_SEARCH);
                threeView.setImageResource(isPortrait ? mSearchImg : mSearchImgLand);
                break;
            case 5:
                threeView.setTag("media_previous");
                threeView.setContentDescription(mContext.getResources().getString(R.string.accessibility_media_previous));
                threeView.setMCode(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
                threeView.setImageResource(isPortrait ? R.drawable.ic_sysbar_media_previous : R.drawable.ic_sysbar_media_previous_land);
                break;
            case 6:
                threeView.setTag("media_next");
                threeView.setContentDescription(mContext.getResources().getString(R.string.accessibility_media_next));
                threeView.setMCode(KeyEvent.KEYCODE_MEDIA_NEXT);
                threeView.setImageResource(isPortrait ? R.drawable.ic_sysbar_media_next : R.drawable.ic_sysbar_media_next_land);
                break;
            default:
            case 2:
                threeView.setTag("home");
                threeView.setContentDescription(mContext.getResources().getString(R.string.accessibility_home));
                threeView.setMCode(KeyEvent.KEYCODE_HOME);
                threeView.setImageResource(isPortrait ? mHomeImg : mHomeImgLand);
                break;
        }
        
        switch (mSlotFour) {
            case 0:
                fourView.setTag("menu");
                fourView.setContentDescription(mContext.getResources().getString(R.string.accessibility_menu));
                fourView.setMCode(KeyEvent.KEYCODE_MENU);
                fourView.setImageResource(isPortrait ? mMenuImg : mMenuImgLand);
                break;
            case 1:
                fourView.setTag("back");
                fourView.setContentDescription(mContext.getResources().getString(R.string.accessibility_back));
                fourView.setMCode(KeyEvent.KEYCODE_BACK);
                fourView.setImageResource(isPortrait ? mBackImg : mBackImgLand);
                break;
            case 2:
                fourView.setTag("home");
                fourView.setContentDescription(mContext.getResources().getString(R.string.accessibility_home));
                fourView.setMCode(KeyEvent.KEYCODE_HOME);
                fourView.setImageResource(isPortrait ? mHomeImg : mHomeImgLand);
                break;
            case 4:
                fourView.setTag("search");
                fourView.setContentDescription(mContext.getResources().getString(R.string.accessibility_search));
                fourView.setMCode(KeyEvent.KEYCODE_SEARCH);
                fourView.setImageResource(isPortrait ? mSearchImg : mSearchImgLand);
                break;
            case 5:
                fourView.setTag("media_previous");
                fourView.setContentDescription(mContext.getResources().getString(R.string.accessibility_media_previous));
                fourView.setMCode(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
                fourView.setImageResource(isPortrait ? R.drawable.ic_sysbar_media_previous : R.drawable.ic_sysbar_media_previous_land);
                break;
            case 6:
                fourView.setTag("media_next");
                fourView.setContentDescription(mContext.getResources().getString(R.string.accessibility_media_next));
                fourView.setMCode(KeyEvent.KEYCODE_MEDIA_NEXT);
                fourView.setImageResource(isPortrait ? R.drawable.ic_sysbar_media_next : R.drawable.ic_sysbar_media_next_land);
                break;
            default:
            case 3:
                fourView.setTag("recent");
                fourView.setContentDescription(mContext.getResources().getString(R.string.accessibility_recent));
                fourView.setMCode(0);
                fourView.setImageResource(isPortrait ? mRecentImg : mRecentImgLand);
                break;
        }

        switch (mSlotFive) {
            case 1:
                fiveView.setTag("menu");
                fiveView.setContentDescription(mContext.getResources().getString(R.string.accessibility_menu));
                fiveView.setMCode(KeyEvent.KEYCODE_MENU);
                fiveView.setImageResource(isPortrait ? mMenuImg : mMenuImgLand);
                break;
            case 2:
                fiveView.setTag("back");
                fiveView.setContentDescription(mContext.getResources().getString(R.string.accessibility_back));
                fiveView.setMCode(KeyEvent.KEYCODE_BACK);
                fiveView.setImageResource(isPortrait ? mBackImg : mBackImgLand);
                break;
            case 3:
                fiveView.setTag("home");
                fiveView.setContentDescription(mContext.getResources().getString(R.string.accessibility_home));
                fiveView.setMCode(KeyEvent.KEYCODE_HOME);
                fiveView.setImageResource(isPortrait ? mHomeImg : mHomeImgLand);
                break;
            case 4:
                fiveView.setTag("recent");
                fiveView.setContentDescription(mContext.getResources().getString(R.string.accessibility_recent));
                fiveView.setMCode(0);
                fiveView.setImageResource(isPortrait ? mRecentImg : mRecentImgLand);
                break;
            case 5:
                fiveView.setTag("search");
                fiveView.setContentDescription(mContext.getResources().getString(R.string.accessibility_search));
                fiveView.setMCode(KeyEvent.KEYCODE_SEARCH);
                fiveView.setImageResource(isPortrait ? mSearchImg : mSearchImgLand);
                break;
            case 6:
                fiveView.setTag("media_previous");
                fiveView.setContentDescription(mContext.getResources().getString(R.string.accessibility_media_previous));
                fiveView.setMCode(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
                fiveView.setImageResource(isPortrait ? R.drawable.ic_sysbar_media_previous : R.drawable.ic_sysbar_media_previous_land);
                break;
            case 7:
                fiveView.setTag("media_next");
                fiveView.setContentDescription(mContext.getResources().getString(R.string.accessibility_media_next));
                fiveView.setMCode(KeyEvent.KEYCODE_MEDIA_NEXT);
                fiveView.setImageResource(isPortrait ? R.drawable.ic_sysbar_media_next : R.drawable.ic_sysbar_media_next_land);
                break;
            default:
            case 0:
                fiveView.setVisibility(View.GONE);
                break;
        }
    }

    private String getResourceName(int resId) {
        if (resId != 0) {
            final android.content.res.Resources res = mContext.getResources();
            try {
                return res.getResourceName(resId);
            } catch (android.content.res.Resources.NotFoundException ex) {
                return "(unknown)";
            }
        } else {
            return "(null)";
        }
    }

    private void postCheckForInvalidLayout(final String how) {
        mHandler.obtainMessage(MSG_CHECK_INVALID_LAYOUT, 0, 0, how).sendToTarget();
    }

    private static String visibilityToString(int vis) {
        switch (vis) {
            case View.INVISIBLE:
                return "INVISIBLE";
            case View.GONE:
                return "GONE";
            default:
                return "VISIBLE";
        }
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("NavigationBarView {");
        final Rect r = new Rect();

        pw.println(String.format("      this: " + PhoneStatusBar.viewInfo(this)
                        + " " + visibilityToString(getVisibility())));

        getWindowVisibleDisplayFrame(r);
        final boolean offscreen = r.right > mDisplay.getRawWidth()
            || r.bottom > mDisplay.getRawHeight();
        pw.println("      window: " 
                + r.toShortString()
                + " " + visibilityToString(getWindowVisibility())
                + (offscreen ? " OFFSCREEN!" : ""));

        pw.println(String.format("      mCurrentView: id=%s (%dx%d) %s",
                        getResourceName(mCurrentView.getId()),
                        mCurrentView.getWidth(), mCurrentView.getHeight(),
                        visibilityToString(mCurrentView.getVisibility())));

        pw.println(String.format("      disabled=0x%08x vertical=%s hidden=%s low=%s menu=%s",
                        mDisabledFlags,
                        mVertical ? "true" : "false",
                        mHidden ? "true" : "false",
                        mLowProfile ? "true" : "false",
                        mShowMenu ? "true" : "false"));
    }
}

/*
 * Copyright (C) 2008 The Android Open Source Project
 * Copyright (C) 2025 Raimondas Rimkus
 * Copyright (C) 2021 wittmane
 * Copyright (C) 2021 Maarten Trompper
 * Copyright (C) 2019 Micha LaQua
 * Copyright (C) 2019 Emmanuel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package rkr.simplekeyboard.inputmethod.latin;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.inputmethodservice.InputMethodService;
import android.media.AudioManager;
import android.os.Build;
import android.os.Debug;
import android.os.IBinder;
import android.os.Message;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.util.PrintWriterPrinter;
import android.util.Printer;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.view.Window;
import android.view.WindowInsetsController;
import android.view.inputmethod.EditorInfo;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import rkr.simplekeyboard.inputmethod.compat.EditorInfoCompatUtils;
import rkr.simplekeyboard.inputmethod.compat.PreferenceManagerCompat;
import rkr.simplekeyboard.inputmethod.event.Event;
import rkr.simplekeyboard.inputmethod.event.InputTransaction;
import rkr.simplekeyboard.inputmethod.keyboard.Keyboard;
import rkr.simplekeyboard.inputmethod.keyboard.KeyboardActionListener;
import rkr.simplekeyboard.inputmethod.keyboard.KeyboardId;
import rkr.simplekeyboard.inputmethod.keyboard.KeyboardSwitcher;
import rkr.simplekeyboard.inputmethod.keyboard.MainKeyboardView;
import rkr.simplekeyboard.inputmethod.latin.common.Constants;
import rkr.simplekeyboard.inputmethod.latin.define.DebugFlags;
import rkr.simplekeyboard.inputmethod.latin.inputlogic.InputLogic;
import rkr.simplekeyboard.inputmethod.latin.settings.Settings;
import rkr.simplekeyboard.inputmethod.latin.settings.SettingsActivity;
import rkr.simplekeyboard.inputmethod.latin.settings.SettingsValues;
import rkr.simplekeyboard.inputmethod.latin.utils.ApplicationUtils;
import rkr.simplekeyboard.inputmethod.latin.utils.LeakGuardHandlerWrapper;
import rkr.simplekeyboard.inputmethod.latin.utils.ResourceUtils;
import rkr.simplekeyboard.inputmethod.latin.utils.ViewLayoutUtils;

/**
 * Input method implementation for Qwerty'ish keyboard.
 */
public class LatinIME extends InputMethodService implements KeyboardActionListener,
        RichInputMethodManager.SubtypeChangedListener {
    static final String TAG = LatinIME.class.getSimpleName();
    private static final boolean TRACE = false;

    private static final int EXTENDED_TOUCHABLE_REGION_HEIGHT = 100;
    private static final int PERIOD_FOR_AUDIO_AND_HAPTIC_FEEDBACK_IN_KEY_REPEAT = 2;
    private static final int PENDING_IMS_CALLBACK_DURATION_MILLIS = 800;
    static final long DELAY_DEALLOCATE_MEMORY_MILLIS = TimeUnit.SECONDS.toMillis(10);
    final Settings mSettings;
    private Locale mLocale;
    final InputLogic mInputLogic = new InputLogic(this /* LatinIME */);

    // TODO: Move these {@link View}s to {@link KeyboardSwitcher}.
    private View mInputView;

    private RichInputMethodManager mRichImm;
    final KeyboardSwitcher mKeyboardSwitcher;

    private AlertDialog mOptionsDialog;

    public final UIHandler mHandler = new UIHandler(this);

    public static final class UIHandler extends LeakGuardHandlerWrapper<LatinIME> {
        private static final int MSG_UPDATE_SHIFT_STATE = 0;
        private static final int MSG_PENDING_IMS_CALLBACK = 1;
        private static final int MSG_DEALLOCATE_MEMORY = 9;

        public UIHandler(final LatinIME ownerInstance) {
            super(ownerInstance);
        }

        @Override
        public void handleMessage(final Message msg) {
            final LatinIME latinIme = getOwnerInstance();
            if (latinIme == null) {
                return;
            }
            final KeyboardSwitcher switcher = latinIme.mKeyboardSwitcher;
            switch (msg.what) {
            case MSG_UPDATE_SHIFT_STATE:
                switcher.requestUpdatingShiftState(latinIme.getCurrentAutoCapsState(),
                        latinIme.getCurrentRecapitalizeState());
                break;
            case MSG_DEALLOCATE_MEMORY:
                latinIme.deallocateMemory();
                break;
            }
        }

        public void postUpdateShiftState() {
            removeMessages(MSG_UPDATE_SHIFT_STATE);
            sendMessage(obtainMessage(MSG_UPDATE_SHIFT_STATE));
        }

        public void postDeallocateMemory() {
            sendMessageDelayed(obtainMessage(MSG_DEALLOCATE_MEMORY),
                    DELAY_DEALLOCATE_MEMORY_MILLIS);
        }

        public void cancelDeallocateMemory() {
            removeMessages(MSG_DEALLOCATE_MEMORY);
        }

        public boolean hasPendingDeallocateMemory() {
            return hasMessages(MSG_DEALLOCATE_MEMORY);
        }
        // Working variables for the following methods.
        private boolean mIsOrientationChanging;
        private boolean mPendingSuccessiveImsCallback;
        private boolean mHasPendingStartInput;
        private boolean mHasPendingFinishInputView;
        private boolean mHasPendingFinishInput;
        private EditorInfo mAppliedEditorInfo;

        private void resetPendingImsCallback() {
            mHasPendingFinishInputView = false;
            mHasPendingFinishInput = false;
            mHasPendingStartInput = false;
        }

        private void executePendingImsCallback(final LatinIME latinIme, final EditorInfo editorInfo,
                boolean restarting) {
            if (mHasPendingFinishInputView) {
                latinIme.onFinishInputViewInternal(mHasPendingFinishInput);
            }
            if (mHasPendingFinishInput) {
                latinIme.onFinishInputInternal();
            }
            if (mHasPendingStartInput) {
                latinIme.onStartInputInternal(editorInfo, restarting);
            }
            resetPendingImsCallback();
        }

        public void onStartInput(final EditorInfo editorInfo, final boolean restarting) {
            if (hasMessages(MSG_PENDING_IMS_CALLBACK)) {
                // Typically this is the second onStartInput after orientation changed.
                mHasPendingStartInput = true;
            } else {
                if (mIsOrientationChanging && restarting) {
                    // This is the first onStartInput after orientation changed.
                    mIsOrientationChanging = false;
                    mPendingSuccessiveImsCallback = true;
                }
                final LatinIME latinIme = getOwnerInstance();
                if (latinIme != null) {
                    executePendingImsCallback(latinIme, editorInfo, restarting);
                    latinIme.onStartInputInternal(editorInfo, restarting);
                }
            }
        }
        public void onStartInputView(final EditorInfo editorInfo, final boolean restarting) {
            if (hasMessages(MSG_PENDING_IMS_CALLBACK)
                    && KeyboardId.equivalentEditorInfoForKeyboard(editorInfo, mAppliedEditorInfo)) {
                // Typically this is the second onStartInputView after orientation changed.
                resetPendingImsCallback();
            } else {
                if (mPendingSuccessiveImsCallback) {
                    // This is the first onStartInputView after orientation changed.
                    mPendingSuccessiveImsCallback = false;
                    resetPendingImsCallback();
                    sendMessageDelayed(obtainMessage(MSG_PENDING_IMS_CALLBACK),
                            PENDING_IMS_CALLBACK_DURATION_MILLIS);
                }
                final LatinIME latinIme = getOwnerInstance();
                if (latinIme != null) {
                    executePendingImsCallback(latinIme, editorInfo, restarting);
                    latinIme.onStartInputViewInternal(editorInfo, restarting);
                    mAppliedEditorInfo = editorInfo;
                }
                cancelDeallocateMemory();
            }
        }

        public void onFinishInputView(final boolean finishingInput) {
            if (hasMessages(MSG_PENDING_IMS_CALLBACK)) {
                // Typically this is the first onFinishInputView after orientation changed.
                mHasPendingFinishInputView = true;
            } else {
                final LatinIME latinIme = getOwnerInstance();
                if (latinIme != null) {
                    latinIme.onFinishInputViewInternal(finishingInput);
                    mAppliedEditorInfo = null;
                }
                if (!hasPendingDeallocateMemory()) {
                    postDeallocateMemory();
                }
            }
        }
        public void onFinishInput() {
            if (hasMessages(MSG_PENDING_IMS_CALLBACK)) {
                // Typically this is the first onFinishInput after orientation changed.
                mHasPendingFinishInput = true;
            } else {
                final LatinIME latinIme = getOwnerInstance();
                if (latinIme != null) {
                    executePendingImsCallback(latinIme, null, false);
                    latinIme.onFinishInputInternal();
                }
            }
        }
    }

    public LatinIME() {
        super();
        mSettings = Settings.getInstance();
        mKeyboardSwitcher = KeyboardSwitcher.getInstance();
    }

    @Override
    public void onCreate() {
        Settings.init(this);
        DebugFlags.init(PreferenceManagerCompat.getDeviceSharedPreferences(this));
        RichInputMethodManager.init(this);
        mRichImm = RichInputMethodManager.getInstance();
        mRichImm.setSubtypeChangeHandler(this);
        KeyboardSwitcher.init(this);
        AudioAndHapticFeedbackManager.init(this);
        super.onCreate();

        // TODO: Resolve mutual dependencies of {@link #loadSettings()} and
        // {@link #resetDictionaryFacilitatorIfNecessary()}.
        loadSettings();

        // Register to receive ringer mode change.
        final IntentFilter filter = new IntentFilter();
        filter.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION);
        registerReceiver(mRingerModeChangeReceiver, filter);
    }
    private void loadSettings() {
        mLocale = mRichImm.getCurrentSubtype().getLocaleObject();
        final EditorInfo editorInfo = getCurrentInputEditorInfo();
        final InputAttributes inputAttributes = new InputAttributes(editorInfo, isFullscreenMode());
        mSettings.loadSettings(inputAttributes);
        final SettingsValues currentSettingsValues = mSettings.getCurrent();
        AudioAndHapticFeedbackManager.getInstance().onSettingsChanged(currentSettingsValues);
    }

    @Override
    public void onDestroy() {
        mSettings.onDestroy();
        unregisterReceiver(mRingerModeChangeReceiver);
        super.onDestroy();
    }

    private boolean isImeSuppressedByHardwareKeyboard() {
        final KeyboardSwitcher switcher = KeyboardSwitcher.getInstance();
        return !onEvaluateInputViewShown() && switcher.isImeSuppressedByHardwareKeyboard(
                mSettings.getCurrent(), switcher.getKeyboardSwitchState());
    }

    @Override
    public boolean onEvaluateInputViewShown() {
        final boolean useOnScreen = super.onEvaluateInputViewShown();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) {
            return useOnScreen;
        } else {
            return useOnScreen || mSettings.getCurrent().mUseOnScreen;
        }
    }

    @Override
    public void onConfigurationChanged(final Configuration conf) {
        SettingsValues settingsValues = mSettings.getCurrent();
        if (settingsValues.mHasHardwareKeyboard != Settings.readHasHardwareKeyboard(conf)) {
            loadSettings();
        }

        mKeyboardSwitcher.onConfigurationChanged();

        super.onConfigurationChanged(conf);
    }
    @Override
    public View onCreateInputView() {
        return mKeyboardSwitcher.onCreateInputView();
    }

    @Override
    public void setInputView(final View view) {
        super.setInputView(view);
        mInputView = view;
        updateSoftInputWindowLayoutParameters();
        view.requestApplyInsets();
    }

    @Override
    public void setCandidatesView(final View view) {
        // To ensure that CandidatesView will never be set.
    }

    @Override
    public void onStartInput(final EditorInfo editorInfo, final boolean restarting) {
        mHandler.onStartInput(editorInfo, restarting);
    }

    @Override
    public void onStartInputView(final EditorInfo editorInfo, final boolean restarting) {
        mHandler.onStartInputView(editorInfo, restarting);
    }

    @Override
    public void onFinishInputView(final boolean finishingInput) {
        mInputLogic.clearCaches();
        mRichImm.resetSubtypeCycleOrder();
        mHandler.onFinishInputView(finishingInput);
    }

    @Override
    public void onFinishInput() {
        mHandler.onFinishInput();
    }

    @Override
    public void onCurrentSubtypeChanged() {
        mInputLogic.onSubtypeChanged();
        loadKeyboard();
    }
    void onStartInputInternal(final EditorInfo editorInfo, final boolean restarting) {
        super.onStartInput(editorInfo, restarting);

        final Locale primaryHintLocale = EditorInfoCompatUtils.getPrimaryHintLocale(editorInfo);
        if (primaryHintLocale == null) {
            return;
        }
        mRichImm.setCurrentSubtype(primaryHintLocale);
    }

    void onStartInputViewInternal(final EditorInfo editorInfo, final boolean restarting) {
        super.onStartInputView(editorInfo, restarting);

        final KeyboardSwitcher switcher = mKeyboardSwitcher;
        switcher.updateKeyboardTheme();
        final MainKeyboardView mainKeyboardView = switcher.getMainKeyboardView();
        SettingsValues currentSettingsValues = mSettings.getCurrent();

        if (editorInfo == null) {
            Log.e(TAG, "Null EditorInfo in onStartInputView()");
            return;
        }
        
        Log.i(TAG, "Starting input. Cursor position = "
                + editorInfo.initialSelStart + "," + editorInfo.initialSelEnd +
                " Restarting = " + restarting);

        if (mainKeyboardView == null) {
            return;
        }

        final boolean inputTypeChanged = !currentSettingsValues.isSameInputType(editorInfo);
        final boolean isDifferentTextField = !restarting || inputTypeChanged;

        updateFullscreenMode();

        if (!isImeSuppressedByHardwareKeyboard()) {
            mInputLogic.startInput();
            mInputLogic.mConnection.reloadTextCache(editorInfo, restarting);
        }

        if (isDifferentTextField ||
                !currentSettingsValues.hasSameOrientation(getResources().getConfiguration())) {
            loadSettings();
        }
        if (isDifferentTextField) {
            mainKeyboardView.closing();
            currentSettingsValues = mSettings.getCurrent();

            switcher.loadKeyboard(editorInfo, currentSettingsValues, getCurrentAutoCapsState(),
                    getCurrentRecapitalizeState());
        } else {
            switcher.resetKeyboardStateToAlphabet(getCurrentAutoCapsState(),
                    getCurrentRecapitalizeState());
        }

        if (TRACE) Debug.startMethodTracing("/data/trace/latinime");
    }
    @Override
    public void onWindowShown() {
        super.onWindowShown();
        if (isInputViewShown())
            setNavigationBarColor();
    }

    @Override
    public void onWindowHidden() {
        super.onWindowHidden();
        final MainKeyboardView mainKeyboardView = mKeyboardSwitcher.getMainKeyboardView();
        if (mainKeyboardView != null) {
            mainKeyboardView.closing();
        }
    }

    void onFinishInputInternal() {
        super.onFinishInput();
        final MainKeyboardView mainKeyboardView = mKeyboardSwitcher.getMainKeyboardView();
        if (mainKeyboardView != null) {
            mainKeyboardView.closing();
        }
    }

    private void deallocateMemory() {
        mKeyboardSwitcher.deallocateMemory();
    }

    private void loadKeyboard() {
        if (mKeyboardSwitcher.getMainKeyboardView() != null) {
            mKeyboardSwitcher.loadKeyboard(getCurrentInputEditorInfo(), mSettings.getCurrent(),
                    getCurrentAutoCapsState(), getCurrentRecapitalizeState());
        }
    }

    private void setNavigationBarColor() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            final Window window = getWindow().getWindow();
            if (window == null) return;
            int flag = android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            if (mSettings.getCurrent().mNavigationBarColor) {
                window.getInsetsController().setSystemBarsAppearance(flag, flag);
            } else {
                window.getInsetsController().setSystemBarsAppearance(0, flag);
            }
        }
    }

    private String convertToBanglish(String s) {
        if (s == null || s.isEmpty()) return s;
        s = s.replace("ক্ষ", "kkh").replace("জ্ঞ", "ggo").replace("ঞ্চ", "nch")
             .replace("ঞ্জ", "nj").replace("ঙ্গ", "ng").replace("ঙ্ক", "nk")
             .replace("প্ট", "pt").replace("ষ্ট", "sht").replace("ষ্ঠ", "shth")
             .replace("ন্দ", "nd").replace("ন্ধ", "ndh").replace("ন্ত", "nt")
             .replace("ম্প", "mp").replace("ম্ব", "mb").replace("ম্ভ", "mbh")
             .replace("ষ্ক", "shk").replace("স্প", "sp").replace("স্ত", "st")
             .replace("স্থ", "sth").replace("ত্ন", "tn").replace("ত্থ", "tth");
        
        String[] b = {"অ", "আ", "ই", "ঈ", "উ", "ঊ", "ঋ", "এ", "ঐ", "ও", "ঔ",
                      "ক", "খ", "গ", "ঘ", "ঙ", "চ", "ছ", "জ", "ঝ", "ঞ",
                      "ট", "ঠ", "ড", "ঢ", "ণ", "ত", "থ", "দ", "ধ", "ন",
                      "প", "ফ", "ব", "ভ", "ম", "য", "র", "ল", "শ", "ষ", "স", "হ",
                      "ড়", "ঢ়", "য়", "ৎ", "ং", "ঃ", "ঁ",
                      "া", "ি", "ী", "ু", "ূ", "ৃ", "ে", "ৈ", "ো", "ৌ"};
        String[] e = {"o", "a", "i", "i", "u", "u", "ri", "e", "oi", "o", "ou",
                      "k", "kh", "g", "gh", "ng", "ch", "chh", "j", "jh", "ng",
                      "t", "th", "d", "dh", "n", "t", "th", "d", "dh", "n",
                      "p", "ph", "b", "bh", "m", "z", "r", "l", "sh", "sh", "s", "h",
                      "r", "rh", "y", "t", "ng", "h", "n",
                      "a", "i", "i", "u", "u", "ri", "e", "oi", "o", "ou"};
        for (int i = 0; i < b.length; i++) s = s.replace(b[i], e[i]);
        return s;
    }

    @Override
    public void onTextInput(final String rawText) {
        String text = convertToBanglish(rawText);
        super.onTextInput(text);
    }

    private final BroadcastReceiver mRingerModeChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            AudioAndHapticFeedbackManager.getInstance().onRingerModeChanged();
        }
    };
}

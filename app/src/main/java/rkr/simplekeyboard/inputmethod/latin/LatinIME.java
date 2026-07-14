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
import android.os.Message;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowInsetsController;
import android.view.inputmethod.EditorInfo;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

import rkr.simplekeyboard.inputmethod.compat.EditorInfoCompatUtils;
import rkr.simplekeyboard.inputmethod.compat.PreferenceManagerCompat;
import rkr.simplekeyboard.inputmethod.event.Event;
import rkr.simplekeyboard.inputmethod.event.InputTransaction;
import rkr.simplekeyboard.inputmethod.keyboard.KeyboardActionListener;
import rkr.simplekeyboard.inputmethod.keyboard.KeyboardId;
import rkr.simplekeyboard.inputmethod.keyboard.KeyboardSwitcher;
import rkr.simplekeyboard.inputmethod.keyboard.MainKeyboardView;
import rkr.simplekeyboard.inputmethod.latin.common.Constants;
import rkr.simplekeyboard.inputmethod.latin.define.DebugFlags;
import rkr.simplekeyboard.inputmethod.latin.inputlogic.InputLogic;
import rkr.simplekeyboard.inputmethod.latin.settings.Settings;
import rkr.simplekeyboard.inputmethod.latin.settings.SettingsValues;
import rkr.simplekeyboard.inputmethod.latin.utils.LeakGuardHandlerWrapper;
import rkr.simplekeyboard.inputmethod.latin.utils.ResourceUtils;

public class LatinIME extends InputMethodService implements KeyboardActionListener,
        RichInputMethodManager.SubtypeChangedListener {
    static final String TAG = LatinIME.class.getSimpleName();
    final Settings mSettings;
    final InputLogic mInputLogic = new InputLogic(this);
    final KeyboardSwitcher mKeyboardSwitcher;
    public final UIHandler mHandler = new UIHandler(this);

    public LatinIME() {
        super();
        mSettings = Settings.getInstance();
        mKeyboardSwitcher = KeyboardSwitcher.getInstance();
    }

    @Override
    public void onCreate() {
        Settings.init(this);
        RichInputMethodManager.init(this);
        KeyboardSwitcher.init(this);
        AudioAndHapticFeedbackManager.init(this);
        super.onCreate();
    }

    @Override
    public void onStartInputView(final EditorInfo editorInfo, final boolean restarting) {
        super.onStartInputView(editorInfo, restarting);
        mKeyboardSwitcher.loadKeyboard(editorInfo, mSettings.getCurrent(), getCurrentAutoCapsState(), getCurrentRecapitalizeState());
    }

    @Override
    public void onFinishInputView(final boolean finishingInput) {
        super.onFinishInputView(finishingInput);
        final MainKeyboardView mainKeyboardView = mKeyboardSwitcher.getMainKeyboardView();
        if (mainKeyboardView != null) mainKeyboardView.closing();
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
        String[] b = {"অ", "আ", "ই", "ঈ", "উ", "ঊ", "ঋ", "এ", "ঐ", "ও", "ঔ", "ক", "খ", "গ", "ঘ", "ঙ", "চ", "ছ", "জ", "ঝ", "ঞ", "ট", "ঠ", "ড", "ঢ", "ণ", "ত", "থ", "দ", "ধ", "ন", "প", "ফ", "ব", "ভ", "ম", "য", "র", "ল", "শ", "ষ", "স", "হ", "ড়", "ঢ়", "য়", "ৎ", "ং", "ঃ", "ঁ", "া", "ি", "ী", "ু", "ূ", "ৃ", "ে", "ৈ", "ো", "ৌ"};
        String[] e = {"o", "a", "i", "i", "u", "u", "ri", "e", "oi", "o", "ou", "k", "kh", "g", "gh", "ng", "ch", "chh", "j", "jh", "ng", "t", "th", "d", "dh", "n", "t", "th", "d", "dh", "n", "p", "ph", "b", "bh", "m", "z", "r", "l", "sh", "sh", "s", "h", "r", "rh", "y", "t", "ng", "h", "n", "a", "i", "i", "u", "u", "ri", "e", "oi", "o", "ou"};
        for (int i = 0; i < b.length; i++) s = s.replace(b[i], e[i]);
        return s;
    }

    @Override
    public void onTextInput(final String rawText) {
        String text = convertToBanglish(rawText);
        final Event event = Event.createSoftwareTextEvent(text, Constants.CODE_OUTPUT_TEXT);
        final InputTransaction completeInputTransaction = mInputLogic.onTextInput(mSettings.getCurrent(), event);
        mKeyboardSwitcher.onEvent(event, getCurrentAutoCapsState(), getCurrentRecapitalizeState());
    }
        }
        

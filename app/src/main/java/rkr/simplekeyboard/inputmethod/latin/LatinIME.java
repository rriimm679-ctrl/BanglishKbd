package rkr.simplekeyboard.inputmethod.latin;

import android.os.Build;
import android.view.Window;
import android.view.WindowInsetsController;
import androidx.preference.PreferenceManagerCompat;
import rkr.simplekeyboard.inputmethod.latin.settings.Settings;
import rkr.simplekeyboard.inputmethod.latin.utils.ResourceUtils;

// (আপনার আগের ফাইলের শুরুতে যদি প্যাকেজ বা ইমপোর্ট থাকে সেগুলো এখানে রাখবেন)

public class LatinIME extends InputMethodService {

    // ... আপনার আগের ফাইলের সব কোড এখানে থাকবে ...

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
        final KeyboardActionListener p = mKeyboardActionListener;
        if (p != null) {
            p.onTextInput(text);
        }
    }
}

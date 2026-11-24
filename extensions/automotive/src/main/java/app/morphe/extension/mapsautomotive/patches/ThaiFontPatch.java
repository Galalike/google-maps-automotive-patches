package app.morphe.extension.mapsautomotive.patches;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontFamily;
import android.graphics.fonts.FontStyle;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import app.morphe.extension.shared.Utils;

/**
 * Replaces Maps Automotive's Thai system fallback (which is the older looped Thai font on Android 10
 * DiLink 3 head units) with bundled loopless Noto Sans Thai weights while preserving Roboto / system
 * sans-serif fallback for Latin, numerals, symbols, and emojis.
 */
@SuppressWarnings("unused")
public final class ThaiFontPatch {
    static final String TAG = "MorpheThaiFont";
    private static final String FONT_DIR = "morphe_fonts/";
    private static final long LAYOUT_DEBOUNCE_MS = 250L;
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final Set<View> watchedRoots =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static final Set<Typeface> preservedTypefaces =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private static volatile boolean initialized;
    private static volatile Typeface origDefault;
    private static volatile Typeface origDefaultBold;
    private static volatile Typeface origSansSerif;
    private static volatile Typeface origSerif;
    private static volatile Typeface origMonospace;

    private static volatile Typeface tfRegular;
    private static volatile Typeface tfMedium;
    private static volatile Typeface tfSemiBold;
    private static volatile Typeface tfBold;
    private static volatile Typeface tfItalic;
    private static volatile Typeface tfBoldItalic;

    private ThaiFontPatch() {
    }

    public static void init(Context context) {
        if (initialized || context == null || Build.VERSION.SDK_INT < 29) {
            return;
        }
        Context appContext = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        if (!SafeMode.on(appContext, SafeMode.THAI_FONT)) {
            return;
        }
        synchronized (ThaiFontPatch.class) {
            if (initialized) {
                return;
            }
            try {
                origDefault = Typeface.DEFAULT;
                origDefaultBold = Typeface.DEFAULT_BOLD;
                origSansSerif = Typeface.SANS_SERIF;
                origSerif = Typeface.SERIF;
                origMonospace = Typeface.MONOSPACE;

                AssetManager assets = appContext.getAssets();
                Font regular = new Font.Builder(assets, FONT_DIR + "noto_sans_thai_regular.ttf")
                        .setWeight(400).setSlant(FontStyle.FONT_SLANT_UPRIGHT).build();
                Font medium = new Font.Builder(assets, FONT_DIR + "noto_sans_thai_medium.ttf")
                        .setWeight(500).setSlant(FontStyle.FONT_SLANT_UPRIGHT).build();
                Font semibold = new Font.Builder(assets, FONT_DIR + "noto_sans_thai_semibold.ttf")
                        .setWeight(600).setSlant(FontStyle.FONT_SLANT_UPRIGHT).build();
                Font bold = new Font.Builder(assets, FONT_DIR + "noto_sans_thai_bold.ttf")
                        .setWeight(700).setSlant(FontStyle.FONT_SLANT_UPRIGHT).build();

                FontFamily family = new FontFamily.Builder(regular)
                        .addFont(medium)
                        .addFont(semibold)
                        .addFont(bold)
                        .build();

                tfRegular = new Typeface.CustomFallbackBuilder(family)
                        .setStyle(new FontStyle(400, FontStyle.FONT_SLANT_UPRIGHT))
                        .setSystemFallback("sans-serif")
                        .build();
                tfMedium = new Typeface.CustomFallbackBuilder(family)
                        .setStyle(new FontStyle(500, FontStyle.FONT_SLANT_UPRIGHT))
                        .setSystemFallback("sans-serif")
                        .build();
                tfSemiBold = new Typeface.CustomFallbackBuilder(family)
                        .setStyle(new FontStyle(600, FontStyle.FONT_SLANT_UPRIGHT))
                        .setSystemFallback("sans-serif")
                        .build();
                tfBold = new Typeface.CustomFallbackBuilder(family)
                        .setStyle(new FontStyle(700, FontStyle.FONT_SLANT_UPRIGHT))
                        .setSystemFallback("sans-serif")
                        .build();
                tfItalic = new Typeface.CustomFallbackBuilder(family)
                        .setStyle(new FontStyle(400, FontStyle.FONT_SLANT_ITALIC))
                        .setSystemFallback("sans-serif")
                        .build();
                tfBoldItalic = new Typeface.CustomFallbackBuilder(family)
                        .setStyle(new FontStyle(700, FontStyle.FONT_SLANT_ITALIC))
                        .setSystemFallback("sans-serif")
                        .build();

                initialized = true;
                patchSystemFontState();
                registerActivityWatcher(appContext);
                ReportLog.i(TAG, "Noto Sans Thai initialized");
            } catch (Throwable error) {
                ReportLog.w(TAG, "failed to initialize Noto Sans Thai", error);
            }
        }
    }

    private static boolean ensureInitialized() {
        if (initialized) {
            return true;
        }
        if (Build.VERSION.SDK_INT >= 29 && Utils.isContextSet()) {
            init(Utils.getContext());
        }
        return initialized;
    }

    @SuppressWarnings("unchecked")
    private static void patchSystemFontState() {
        try {
            Field mapField = Typeface.class.getDeclaredField("sSystemFontMap");
            mapField.setAccessible(true);
            Object mapObj = mapField.get(null);
            if (mapObj instanceof Map) {
                Map<String, Typeface> map = (Map<String, Typeface>) mapObj;
                String[] families = {
                        "sans-serif", "sans-serif-thin", "sans-serif-light",
                        "sans-serif-condensed", "sans-serif-condensed-light",
                        "roboto", "roboto-regular",
                        "google-sans", "google-sans-text", "google-sans-display",
                        "google-sans-flex", "product-sans"
                };
                String[] mediumFamilies = {
                        "sans-serif-medium", "sans-serif-condensed-medium",
                        "roboto-medium", "google-sans-medium", "google-sans-text-medium"
                };
                String[] boldFamilies = {
                        "sans-serif-black", "roboto-bold", "google-sans-bold", "google-sans-text-bold"
                };
                try {
                    for (String name : families) map.put(name, tfRegular);
                    for (String name : mediumFamilies) map.put(name, tfMedium);
                    for (String name : boldFamilies) map.put(name, tfBold);
                    ReportLog.i(TAG, "updated sSystemFontMap (" + map.size() + " entries)");
                } catch (UnsupportedOperationException ignored) {
                    ReportLog.i(TAG, "sSystemFontMap unmodifiable; using bytecode + view hooks");
                }
            }
        } catch (Throwable error) {
            ReportLog.i(TAG, "sSystemFontMap skipped: " + error.getClass().getSimpleName());
        }

        try {
            Field defaultsField = Typeface.class.getDeclaredField("sDefaults");
            defaultsField.setAccessible(true);
            Object arr = defaultsField.get(null);
            if (arr instanceof Typeface[]) {
                Typeface[] defaults = (Typeface[]) arr;
                if (defaults.length >= 4) {
                    defaults[0] = tfRegular;
                    defaults[1] = tfBold;
                    defaults[2] = tfItalic;
                    defaults[3] = tfBoldItalic;
                    ReportLog.i(TAG, "updated Typeface.sDefaults");
                }
            }
        } catch (Throwable error) {
            ReportLog.i(TAG, "sDefaults skipped: " + error.getClass().getSimpleName());
        }

        try {
            Method setDefault = Typeface.class.getDeclaredMethod("setDefault", Typeface.class);
            setDefault.setAccessible(true);
            setDefault.invoke(null, tfRegular);
            ReportLog.i(TAG, "updated Typeface.setDefault");
        } catch (Throwable error) {
            ReportLog.i(TAG, "Typeface.setDefault skipped: " + error.getClass().getSimpleName());
        }
    }

    private static void registerActivityWatcher(Context appContext) {
        if (!(appContext instanceof Application)) {
            return;
        }
        ((Application) appContext).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                watchActivity(activity);
            }

            @Override
            public void onActivityStarted(Activity activity) {
                watchActivity(activity);
            }

            @Override
            public void onActivityResumed(Activity activity) {
                watchActivity(activity);
            }

            @Override public void onActivityPaused(Activity activity) {}
            @Override public void onActivityStopped(Activity activity) {}
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
            @Override public void onActivityDestroyed(Activity activity) {}
        });
    }

    private static void watchActivity(Activity activity) {
        if (activity == null) {
            return;
        }
        Window window = activity.getWindow();
        if (window != null) {
            watchRoot(window.getDecorView());
        }
    }

    public static void watchRoot(View root) {
        if (!initialized || root == null) {
            return;
        }
        applyToViewTree(root);
        if (watchedRoots.add(root)) {
            final WeakReference<View> rootRef = new WeakReference<>(root);
            final Runnable debouncedApply = () -> {
                View r = rootRef.get();
                if (r != null && r.isAttachedToWindow()) {
                    applyToViewTree(r);
                }
            };
            root.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                mainHandler.removeCallbacks(debouncedApply);
                mainHandler.postDelayed(debouncedApply, LAYOUT_DEBOUNCE_MS);
            });
        }
    }

    private static void applyToViewTree(View view) {
        if (!initialized || view == null) {
            return;
        }
        if (view instanceof TextView) {
            TextView tv = (TextView) view;
            Typeface cur = tv.getTypeface();
            if (!isOurTypeface(cur) && !isExplicitOther(cur)) {
                Typeface mapped = matchWeightAndSlant(cur);
                if (mapped != null) {
                    tv.setTypeface(mapped);
                }
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            int count = group.getChildCount();
            for (int i = 0; i < count; i++) {
                applyToViewTree(group.getChildAt(i));
            }
        }
    }

    public static Typeface getDefault() {
        return ensureInitialized() ? tfRegular : Typeface.DEFAULT;
    }

    public static Typeface getBold() {
        return ensureInitialized() ? tfBold : Typeface.DEFAULT_BOLD;
    }

    public static Typeface getRegular() {
        return ensureInitialized() ? tfRegular : Typeface.SANS_SERIF;
    }

    public static Typeface defaultFromStyle(int style) {
        return ensureInitialized() ? forStyle(style) : Typeface.defaultFromStyle(style);
    }

    public static Typeface create(String familyName, int style) {
        if (ensureInitialized() && isReplaceableFamily(familyName)) {
            return forStyle(style);
        }
        Typeface preserved = Typeface.create(familyName, style);
        if (preserved != null) {
            preservedTypefaces.add(preserved);
        }
        return preserved;
    }

    public static Typeface create(Typeface family, int style) {
        if (!ensureInitialized()) {
            return Typeface.create(family, style);
        }
        if (family == null || isOurTypeface(family) || isReplaceableInstance(family)) {
            return forStyle(style);
        }
        Typeface preserved = Typeface.create(family, style);
        if (preserved != null) {
            preservedTypefaces.add(preserved);
        }
        return preserved;
    }

    public static Typeface create(Typeface family, int weight, boolean italic) {
        if (!ensureInitialized()) {
            return Typeface.create(family, weight, italic);
        }
        if (family == null || isOurTypeface(family) || isReplaceableInstance(family)) {
            return forWeight(weight, italic);
        }
        Typeface preserved = Typeface.create(family, weight, italic);
        if (preserved != null) {
            preservedTypefaces.add(preserved);
        }
        return preserved;
    }

    public static Typeface createFromAsset(AssetManager mgr, String path) {
        if (ensureInitialized() && path != null) {
            String lower = path.toLowerCase();
            if (lower.contains("productsans") || lower.contains("googlesans")
                    || lower.contains("roboto") || lower.contains("sans")) {
                return lower.contains("bold") ? tfBold
                        : (lower.contains("medium") ? tfMedium : tfRegular);
            }
        }
        Typeface preserved = Typeface.createFromAsset(mgr, path);
        if (preserved != null) {
            preservedTypefaces.add(preserved);
        }
        return preserved;
    }

    public static void setTextViewTypeface(TextView tv, Typeface tf) {
        if (tv == null) {
            return;
        }
        tv.setTypeface(mapTypeface(tf));
    }

    public static void setTextViewTypeface(TextView tv, Typeface tf, int style) {
        if (tv == null) {
            return;
        }
        if (!ensureInitialized()) {
            tv.setTypeface(tf, style);
            return;
        }
        if (tf == null || isOurTypeface(tf) || isReplaceableInstance(tf)) {
            tv.setTypeface(forStyle(style));
        } else {
            tv.setTypeface(tf, style);
        }
    }

    public static Typeface setPaintTypeface(Paint paint, Typeface tf) {
        if (paint == null) {
            return null;
        }
        return paint.setTypeface(mapTypeface(tf));
    }

    private static Typeface mapTypeface(Typeface tf) {
        if (!ensureInitialized()) {
            return tf;
        }
        if (tf == null) {
            return tfRegular;
        }
        if (isOurTypeface(tf) || isExplicitOther(tf)) {
            return tf;
        }
        return matchWeightAndSlant(tf);
    }

    private static boolean isOurTypeface(Typeface tf) {
        return tf != null && (tf == tfRegular || tf == tfMedium || tf == tfSemiBold
                || tf == tfBold || tf == tfItalic || tf == tfBoldItalic);
    }

    private static boolean isExplicitOther(Typeface tf) {
        if (tf == null) {
            return false;
        }
        if (preservedTypefaces.contains(tf)) {
            return true;
        }
        return (origMonospace != null && tf == origMonospace)
                || (origSerif != null && tf == origSerif);
    }

    private static boolean isReplaceableInstance(Typeface tf) {
        if (tf == null) {
            return true;
        }
        if (tf == origDefault || tf == origDefaultBold || tf == origSansSerif) {
            return true;
        }
        return !isExplicitOther(tf);
    }

    private static boolean isReplaceableFamily(String name) {
        if (name == null) {
            return true;
        }
        String lower = name.toLowerCase();
        if (lower.contains("mono") || "serif".equals(lower)
                || lower.contains("cursive") || lower.contains("casual")) {
            return false;
        }
        return lower.contains("sans") || lower.contains("roboto") || lower.contains("google")
                || lower.contains("product") || lower.isEmpty();
    }

    private static Typeface matchWeightAndSlant(Typeface cur) {
        if (cur == null) {
            return tfRegular;
        }
        return forWeight(cur.getWeight(), cur.isItalic());
    }

    private static Typeface forStyle(int style) {
        boolean bold = (style & Typeface.BOLD) != 0;
        boolean italic = (style & Typeface.ITALIC) != 0;
        if (bold && italic) {
            return tfBoldItalic;
        }
        if (bold) {
            return tfBold;
        }
        if (italic) {
            return tfItalic;
        }
        return tfRegular;
    }

    private static Typeface forWeight(int weight, boolean italic) {
        if (italic) {
            return weight >= 600 ? tfBoldItalic : tfItalic;
        }
        if (weight >= 650) {
            return tfBold;
        }
        if (weight >= 550) {
            return tfSemiBold;
        }
        if (weight >= 450) {
            return tfMedium;
        }
        return tfRegular;
    }
}

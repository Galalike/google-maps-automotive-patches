package app.morphe.extension.mapsautomotive.patches;

import android.content.Context;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Customizes Google Maps Automotive quick categories and navigation shortcuts:
 * 1. Injects Gas Stations (Laeqo.P / presetQueryType 20) into EV search categories
 *    so Gas Stations appear in both the search categories list and the quick bar.
 * 2. Builds a 5-icon quick bar under the Search box:
 *    [ EV Charging | Gas Stations | Restaurants | Home | Work ]
 * 3. Handles Home & Work quick-icon taps by resolving the user's saved Google
 *    Account alias (Lpvn -> Lpxj) and launching navigation directly via Lilh.z().
 */
public final class CustomQuickActions {
    private static final String TAG = "MorpheQuickActions";

    private static final String QUERY_HOME = "__MORPHE_HOME__";
    private static final String QUERY_WORK = "__MORPHE_WORK__";

    // Odelay icon IDs (Lakld) handled natively by Lmki.aA(int, Ltzg)
    private static final int ICON_HOME = 6;   // Lakld.HOME
    private static final int ICON_WORK = 7;   // Lakld.WORK
    private static final int ICON_GAS  = 0xf204; // Lakld.LOCAL_SEARCH_GAS_STATIONS

    // Laeqo presetQueryType values (confirmed in 26.10.120003.E)
    private static final int PRESET_EV_CHARGING = 52; // Laeqo.O
    private static final int PRESET_GAS_STATION = 20; // Laeqo.P

    // Resource IDs in 26.10.120003.E
    private static final int RES_HOME = 0x7f140e55; // string/HOME_LOCATION
    private static final int RES_WORK = 0x7f1423f6; // string/WORK_LOCATION
    private static final int RES_GAS  = 0x7f14072f; // string/CAR_SEARCH_CATEGORY_GAS

    private static volatile WeakReference<Object> sSearchCardRef;
    private static volatile WeakReference<Object> sLadpcRef;
    private static volatile Context sAppContext;

    private CustomQuickActions() {}

    /** Called early in Lirg.<init> before Ladpc.A() runs. */
    public static void registerContext(Context ctx) {
        if (ctx != null) {
            sAppContext = ctx.getApplicationContext();
        }
    }

    /** Called at the end of Lirg.<init>. */
    public static void registerSearchCard(Object searchCard) {
        if (searchCard == null) return;
        sSearchCardRef = new WeakReference<>(searchCard);
        try {
            Field ctxField = searchCard.getClass().getDeclaredField("a");
            ctxField.setAccessible(true);
            Object ctx = ctxField.get(searchCard);
            if (ctx instanceof Context) {
                sAppContext = ((Context) ctx).getApplicationContext();
            }
        } catch (Throwable t) {
            Log.w(TAG, "registerSearchCard ctx failed", t);
        }
    }

    /** Called at the start of Ladpc.z() so we have access to Ladpc.e (Lsmw). */
    public static void captureLadpc(Object ladpc) {
        if (ladpc != null) {
            sLadpcRef = new WeakReference<>(ladpc);
        }
    }

    /**
     * Called right before Ladpc.z() returns Labpy<Llhu>.
     * Ensures Gas Stations is included right after EV Charging in the full category list.
     */
    @SuppressWarnings("unchecked")
    public static Object enhanceAllCategories(Object originalListObj) {
        if (!(originalListObj instanceof List)) return originalListObj;
        try {
            List<Object> orig = (List<Object>) originalListObj;
            // Check if Gas Stations is already present
            for (Object item : orig) {
                if (getPresetQueryType(item) == PRESET_GAS_STATION) {
                    return originalListObj;
                }
            }

            Object gasItem = createGasStationCategory(orig);
            if (gasItem == null) {
                return originalListObj;
            }

            List<Object> out = new ArrayList<>(orig.size() + 1);
            int insertIdx = orig.isEmpty() ? 0 : 1; // right after EV Charging
            for (int i = 0; i < orig.size(); i++) {
                if (i == insertIdx) out.add(gasItem);
                out.add(orig.get(i));
            }
            if (insertIdx >= orig.size()) out.add(gasItem);

            return toImmutableList(out);
        } catch (Throwable t) {
            Log.e(TAG, "enhanceAllCategories failed", t);
            return originalListObj;
        }
    }

    /**
     * Called right before Ladpc.A() returns Labpy<Llhu>.
     * Receives the full enhanced list (from Ladpc.z()) and returns 5 quick icons:
     * [ EV Charging | Gas Stations | Restaurants | Home | Work ]
     */
    @SuppressWarnings("unchecked")
    public static Object buildQuickBarCategories(Object fullListObj) {
        if (!(fullListObj instanceof List)) return fullListObj;
        try {
            List<Object> full = (List<Object>) fullListObj;
            if (full.isEmpty()) return fullListObj;

            Object evItem = null;
            Object gasItem = null;
            Object foodItem = null;
            Object fallbackIcon = getIcon(full.get(0));

            int restaurantPreset = getRestaurantPresetId();
            for (Object item : full) {
                int preset = getPresetQueryType(item);
                if (evItem == null && preset == PRESET_EV_CHARGING) {
                    evItem = item;
                } else if (gasItem == null && preset == PRESET_GAS_STATION) {
                    gasItem = item;
                } else if (foodItem == null && (preset == restaurantPreset || (restaurantPreset < 0 && item != evItem && item != gasItem))) {
                    foodItem = item;
                }
            }

            // Fallback: pick first 3 items if any preset lookup missed
            if (evItem == null && full.size() > 0) evItem = full.get(0);
            if (gasItem == null) gasItem = createGasStationCategory(full);
            if (foodItem == null) {
                for (Object item : full) {
                    if (item != evItem && item != gasItem) {
                        foodItem = item;
                        break;
                    }
                }
            }

            List<Object> quickBar = new ArrayList<>(5);
            if (evItem != null) quickBar.add(evItem);
            if (gasItem != null) quickBar.add(gasItem);
            if (foodItem != null) quickBar.add(foodItem);

            Object homeItem = createCustomCategory(QUERY_HOME, getString(RES_HOME, "Home"), ICON_HOME, fallbackIcon);
            Object workItem = createCustomCategory(QUERY_WORK, getString(RES_WORK, "Work"), ICON_WORK, fallbackIcon);
            if (homeItem != null) quickBar.add(homeItem);
            if (workItem != null) quickBar.add(workItem);

            if (quickBar.isEmpty()) return fullListObj;
            return toImmutableList(quickBar);
        } catch (Throwable t) {
            Log.e(TAG, "buildQuickBarCategories failed", t);
            return fullListObj;
        }
    }

    /**
     * Called at the start of Liqv.onClick(View).
     * Returns true if we handled a Home/Work shortcut tap.
     */
    public static boolean handleQuickIconClick(String query, View view) {
        if (!QUERY_HOME.equals(query) && !QUERY_WORK.equals(query)) {
            return false;
        }
        boolean isHome = QUERY_HOME.equals(query);
        Context ctx = (view != null) ? view.getContext() : sAppContext;
        if (ctx != null && sAppContext == null) {
            sAppContext = ctx.getApplicationContext();
        }
        try {
            Object searchCard = (sSearchCardRef != null) ? sSearchCardRef.get() : null;
            if (searchCard == null) {
                Log.w(TAG, "handleQuickIconClick: searchCard is null");
                showNotSetToast(ctx, isHome);
                return true;
            }

            // Lirg.B (Lpgd) -> .a (Lfkd) -> .a (Lfkt) -> .kY (Laloe singleton Provider<Lpvn>) -> .b() (Lpvn)
            Object pgd = getField(searchCard, "B");
            Object fkd = getField(pgd, "a");
            Object fkt = getField(fkd, "a");
            Object pvnProvider = getField(fkt, "kY");
            Method getPvn = pvnProvider.getClass().getMethod("b");
            getPvn.setAccessible(true);
            Object pvn = getPvn.invoke(pvnProvider);

            // Lpvn.f is Labpy<Lpxj>
            Collection<?> aliases = (Collection<?>) getField(pvn, "f");

            // Lajfk.b = HOME, Lajfk.c = WORK
            Class<?> ajfkCls = Class.forName("ajfk");
            Object targetType = ajfkCls.getField(isHome ? "b" : "c").get(null);

            // Lpxj.a(Collection, Lajfk) -> Lpxj
            Class<?> pxjCls = Class.forName("pxj");
            Method findAlias = pxjCls.getDeclaredMethod("a", Collection.class, ajfkCls);
            findAlias.setAccessible(true);
            Object alias = findAlias.invoke(null, aliases, targetType);

            if (alias == null) {
                Log.w(TAG, "No saved alias for " + (isHome ? "Home" : "Work")
                        + " (total aliases=" + (aliases != null ? aliases.size() : -1) + ")");
                showNotSetToast(ctx, isHome);
                return true;
            }

            // String label = alias.f(ctx)
            Method getLabel = pxjCls.getDeclaredMethod("f", Context.class);
            getLabel.setAccessible(true);
            String label = (String) getLabel.invoke(alias, ctx);

            // Likq dest = Likq.a(alias, label)
            Class<?> ikqCls = Class.forName("ikq");
            Method makeDest = ikqCls.getDeclaredMethod("a", pxjCls, String.class);
            makeDest.setAccessible(true);
            Object dest = makeDest.invoke(null, alias, label);

            // Lirg.z (Lilh) -> .z(Likq, 4, null, false, Ljwm.d)
            Object lilh = getField(searchCard, "z");
            Class<?> ndeCls = Class.forName("nde");
            Class<?> jwmCls = Class.forName("jwm");
            Object jwmD = jwmCls.getField("d").get(null);
            Method startRoute = lilh.getClass().getDeclaredMethod(
                    "z", ikqCls, int.class, ndeCls, boolean.class, jwmCls);
            startRoute.setAccessible(true);
            startRoute.invoke(lilh, dest, 4, null, false, jwmD);
            Log.i(TAG, "Started route to " + (isHome ? "Home" : "Work") + ": " + label);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "handleQuickIconClick failed for " + query, t);
            if (ctx != null) {
                String err = t.getClass().getSimpleName() + ": " + t.getMessage();
                Toast.makeText(ctx, "Home/Work error: " + err, Toast.LENGTH_LONG).show();
            }
            return true;
        }
    }

    private static void showNotSetToast(Context ctx, boolean isHome) {
        if (ctx == null) return;
        String place = isHome ? getString(RES_HOME, "Home") : getString(RES_WORK, "Work");
        String msg = isThai(ctx)
                ? "ยังไม่ได้ตั้งค่าที่อยู่ \"" + place + "\" (ตั้งค่าได้ที่ การตั้งค่า > แก้ไขที่อยู่บ้านหรือที่ทำงาน)"
                : place + " address is not set (set it in Settings > Edit home or work)";
        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show();
    }

    private static boolean isThai(Context ctx) {
        try {
            return "th".equalsIgnoreCase(ctx.getResources().getConfiguration().locale.getLanguage());
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object createGasStationCategory(List<Object> existing) {
        // First try to get the official localized Gas Stations item from Ladpc.e (Lsmw).as()
        try {
            Object ladpc = (sLadpcRef != null) ? sLadpcRef.get() : null;
            if (ladpc != null) {
                Object smw = getField(ladpc, "e");
                if (smw != null) {
                    Method asMethod = smw.getClass().getDeclaredMethod("as");
                    asMethod.setAccessible(true);
                    Object iceCategories = asMethod.invoke(smw);
                    if (iceCategories instanceof List) {
                        Class<?> qfnCls = Class.forName("qfn");
                        Class<?> agxtCls = Class.forName("agxt");
                        Constructor<?> qfnCtor = qfnCls.getDeclaredConstructor(agxtCls);
                        qfnCtor.setAccessible(true);
                        for (Object agxt : (List<?>) iceCategories) {
                            int preset = getIntField(agxt, "f");
                            if (preset == PRESET_GAS_STATION) {
                                Object qfn = qfnCtor.newInstance(agxt);
                                return newLlhu(
                                        (String) getField(qfn, "a"),
                                        getIntField(qfn, "b"),
                                        getField(qfn, "c"),
                                        getField(qfn, "d"),
                                        getIntField(qfn, "e"));
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "createGasStationCategory via Lsmw.as() failed, using fallback", t);
        }

        // Fallback: construct Gas Stations Llhu directly
        try {
            Object fallbackIcon = existing.isEmpty() ? null : getIcon(existing.get(0));
            String label = getString(RES_GAS, "Gas stations");
            Object icon = resolveOdelayIcon(ICON_GAS, fallbackIcon);
            Class<?> tzcCls = Class.forName("tzc");
            Constructor<?> tzcCtor = tzcCls.getDeclaredConstructor(CharSequence.class);
            tzcCtor.setAccessible(true);
            Object tyq = tzcCtor.newInstance(label);
            return newLlhu(label, PRESET_GAS_STATION, tyq, icon, ICON_GAS);
        } catch (Throwable t) {
            Log.e(TAG, "createGasStationCategory fallback failed", t);
            return null;
        }
    }

    private static Object createCustomCategory(String query, String label, int iconId, Object fallbackIcon) {
        try {
            Class<?> tzcCls = Class.forName("tzc");
            Constructor<?> tzcCtor = tzcCls.getDeclaredConstructor(CharSequence.class);
            tzcCtor.setAccessible(true);
            Object tyq = tzcCtor.newInstance(label);
            Object icon = resolveOdelayIcon(iconId, fallbackIcon);
            return newLlhu(query, 0, tyq, icon, iconId);
        } catch (Throwable t) {
            Log.e(TAG, "createCustomCategory failed for " + query, t);
            return null;
        }
    }

    private static Object resolveOdelayIcon(int iconId, Object fallbackIcon) {
        try {
            Class<?> mkiCls = Class.forName("mki");
            Class<?> tzgCls = Class.forName("tzg");
            Method aA = mkiCls.getDeclaredMethod("aA", int.class, tzgCls);
            aA.setAccessible(true);
            Object res = aA.invoke(null, iconId, fallbackIcon);
            return (res != null) ? res : fallbackIcon;
        } catch (Throwable t) {
            return fallbackIcon;
        }
    }

    private static Object newLlhu(String query, int presetType, Object tyq, Object tzg, int iconId) throws Exception {
        Class<?> lhuCls = Class.forName("lhu");
        Class<?> tyqCls = Class.forName("tyq");
        Class<?> tzgCls = Class.forName("tzg");
        Constructor<?> ctor = lhuCls.getDeclaredConstructor(
                String.class, int.class, tyqCls, tzgCls, int.class);
        ctor.setAccessible(true);
        return ctor.newInstance(query, presetType, tyq, tzg, iconId);
    }

    private static Object toImmutableList(Collection<?> items) throws Exception {
        Class<?> abpyCls = Class.forName("abpy");
        Method copyOf = abpyCls.getDeclaredMethod("n", Collection.class);
        copyOf.setAccessible(true);
        return copyOf.invoke(null, items);
    }

    private static int getRestaurantPresetId() {
        try {
            Class<?> aeqoCls = Class.forName("aeqo");
            Object k = aeqoCls.getField("k").get(null);
            Method aMethod = aeqoCls.getDeclaredMethod("a");
            aMethod.setAccessible(true);
            return (Integer) aMethod.invoke(k);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static int getPresetQueryType(Object llhu) {
        try {
            return getIntField(llhu, "b");
        } catch (Throwable t) {
            return -1;
        }
    }

    private static Object getIcon(Object llhu) {
        try {
            return getField(llhu, "d");
        } catch (Throwable t) {
            return null;
        }
    }

    private static String getString(int resId, String fallback) {
        try {
            Context ctx = sAppContext;
            if (ctx != null) {
                return ctx.getString(resId);
            }
        } catch (Throwable ignored) {}
        return fallback;
    }

    private static Object getField(Object obj, String name) throws Exception {
        Field f = obj.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(obj);
    }

    private static int getIntField(Object obj, String name) throws Exception {
        Field f = obj.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.getInt(obj);
    }
}

package app.morphe.extension.mapsautomotive.patches;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Replaces Google Maps Automotive's arrival battery calculation and display text with our own
 * BYD RangeLearner projection: "<arrivalSoc>% · วิ่งได้อีก <arrivalRangeKm> กม.".
 */
@SuppressWarnings("unused")
public final class BydCustomArrival {
    private static final ThreadLocal<Integer> THREAD_ROUTE_DISTANCE_M = new ThreadLocal<>();
    private static volatile int latestForecastDistanceM = -1;
    private static final Map<Object, String> CUSTOM_TEXT_BY_QUS =
            Collections.synchronizedMap(new WeakHashMap<>());

    // Cached reflection handles for allv.k().e.c (route distance in meters)
    private static volatile boolean routeReflectAttempted;
    private static Method allvKMethod;
    private static Field kSummaryField;
    private static Field summaryDistanceMField;

    // Cached reflection handles for qus (d, e, f)
    private static volatile boolean qusReflectAttempted;
    private static Field qusRealPctField;
    private static Field qusRawPctField;
    private static Field qusDisplayPctField;

    private BydCustomArrival() { }

    public static void onForecastDistance(int distanceM) {
        latestForecastDistanceM = distanceM > 0 ? distanceM : -1;
    }

    public static void enterRouteContext(Object allvObj) {
        int distM = extractRouteDistanceM(allvObj);
        if (distM <= 0) {
            distM = latestForecastDistanceM;
        }
        if (distM > 0) {
            THREAD_ROUTE_DISTANCE_M.set(distM);
        } else {
            THREAD_ROUTE_DISTANCE_M.remove();
        }
    }

    public static void exitRouteContext() {
        THREAD_ROUTE_DISTANCE_M.remove();
    }

    /**
     * Hooked at the return of rmq.v(int, int, aege) -> qus.
     * Once BydRangeLearner has learned >= 150 km, overrides qus.d, qus.e, qus.f with
     * BydRangeLearner's arrival SoC%. Before 150 km, keeps Google Maps' default calculation.
     */
    public static void applyCustomModel(Object qusObj) {
        if (qusObj == null || !BydBatteryPatch.isActive() || !BydRangeLearner.isReady()) return;
        Integer distM = THREAD_ROUTE_DISTANCE_M.get();
        if (distM == null || distM <= 0) {
            int fDist = latestForecastDistanceM;
            if (fDist <= 0) return;
            distM = fDist;
        }

        BydRangeLearner.Projection projection = BydRangeLearner.computeProjection(distM / 1000.0);
        if (projection == null) return;

        int arrivalSocInt = (int) Math.round(projection.arrivalSocPct);
        writeQusValues(qusObj, projection.arrivalSocPct, arrivalSocInt);
    }

    /**
     * Hooked at the return of qut.a() -> String.
     * Keep only Google Maps' compact percentage text (e.g. "88%") without extra range text.
     */
    public static String formatArrivalText(Object qusObj, String originalText) {
        return originalText;
    }

    private static int extractRouteDistanceM(Object allvObj) {
        if (allvObj == null) return -1;
        try {
            if (!routeReflectAttempted) {
                routeReflectAttempted = true;
                Method k = allvObj.getClass().getMethod("k");
                k.setAccessible(true);
                allvKMethod = k;
            }
            if (allvKMethod == null) return -1;
            Object kObj = allvKMethod.invoke(allvObj);
            if (kObj == null) return -1;
            if (kSummaryField == null) {
                Field e = kObj.getClass().getField("e");
                e.setAccessible(true);
                kSummaryField = e;
            }
            Object eObj = kSummaryField.get(kObj);
            if (eObj == null) return -1;
            if (summaryDistanceMField == null) {
                Field c = eObj.getClass().getField("c");
                c.setAccessible(true);
                summaryDistanceMField = c;
            }
            return summaryDistanceMField.getInt(eObj);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static boolean writeQusValues(Object qusObj, double socPct, int socInt) {
        try {
            if (!qusReflectAttempted) {
                qusReflectAttempted = true;
                Class<?> cls = qusObj.getClass();
                Field d = cls.getField("d");
                Field e = cls.getField("e");
                Field f = cls.getField("f");
                d.setAccessible(true);
                e.setAccessible(true);
                f.setAccessible(true);
                qusRealPctField = d;
                qusRawPctField = e;
                qusDisplayPctField = f;
            }
            if (qusRealPctField == null || qusRawPctField == null || qusDisplayPctField == null) {
                return false;
            }
            qusRealPctField.setDouble(qusObj, socPct);
            qusRawPctField.setDouble(qusObj, socPct);
            qusDisplayPctField.setInt(qusObj, socInt);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}

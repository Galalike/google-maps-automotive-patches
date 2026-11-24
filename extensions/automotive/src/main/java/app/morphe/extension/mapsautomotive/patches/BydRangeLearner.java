package app.morphe.extension.mapsautomotive.patches;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Empirical battery range learner matching BYD-GL-Launcher's RangeLearner formula:
 * 150 km rolling window of 10 km buckets, current-trip blending, parked-drain separation,
 * and kWh smoothing.
 */
final class BydRangeLearner {
    private static final String PREFS = "morphe_byd_range_learner";
    private static final String KEY_STATE = "state";
    private static final double BUCKET_KM = 10.0;
    private static final double MAX_WINDOW_KM = 150.0;
    private static final double MAX_STEP_KM = 5.0;
    private static final double RISE_RESET_PCT = 0.5;
    private static final double UNSEEN_CHARGE_PCT = 1.5;
    private static final double KPP_MIN = 1.5;
    private static final double KPP_MAX = 8.0;
    private static final double FALLBACK_KPP = 4.0;
    private static final double FALLBACK_CONS_PHM = 16.0;
    private static final long NEW_DRIVE_MS = 20 * 60 * 1000L;
    private static final long ODO_FROZEN_RESET_MS = 10 * 60 * 1000L;

    static final class Projection {
        final double currentSocPct;
        final double currentRangeKm;
        final double remainingRouteKm;
        final double arrivalSocPct;
        final double arrivalRangeKm;
        final double kmPerPct;

        Projection(double currentSocPct, double currentRangeKm, double remainingRouteKm,
                   double arrivalSocPct, double arrivalRangeKm, double kmPerPct) {
            this.currentSocPct = currentSocPct;
            this.currentRangeKm = currentRangeKm;
            this.remainingRouteKm = remainingRouteKm;
            this.arrivalSocPct = arrivalSocPct;
            this.arrivalRangeKm = arrivalRangeKm;
            this.kmPerPct = kmPerPct;
        }
    }

    private static final class Bucket {
        final double km;
        final double drop;
        final double hours;
        final long endMs;

        Bucket(double km, double drop, double hours, long endMs) {
            this.km = km;
            this.drop = drop;
            this.hours = hours;
            this.endMs = endMs;
        }
    }

    private static SharedPreferences prefs;
    private static final List<Bucket> buckets = new ArrayList<>();
    private static double oKm, oDrop, oHours;
    private static double tKm, tDrop;
    private static long lastMovingMs;
    private static double refOdo = Double.NaN, refSoc = Double.NaN;
    private static long refMs;
    private static double lastSeenOdo = Double.NaN, lastSoc = Double.NaN;
    private static double lastLatchedKwh = Double.NaN, latchedKwhPerPct = Double.NaN;

    private static volatile double latestSoc = Double.NaN;
    private static volatile double latestRemainKwh = Double.NaN;
    private static volatile double latestConsPhm = Double.NaN;

    private BydRangeLearner() { }

    static synchronized void init(Context context) {
        if (prefs != null || context == null) return;
        Context appCtx = context.getApplicationContext();
        if (appCtx == null) appCtx = context;
        prefs = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        load();
    }

    static synchronized void onSample(BydEnergyReader.Sample sample) {
        if (sample == null) return;
        double socPct = sample.soc;
        double odoKm = sample.mileage / 10.0;
        double remainKwh = sample.energy;
        double speedKmh = sample.speedKmh;
        long nowMs = sample.wall;

        if (!Double.isNaN(socPct) && socPct > 0.0) {
            latestSoc = socPct;
        }
        if (!Double.isNaN(remainKwh) && remainKwh > 0.0) {
            latestRemainKwh = remainKwh;
        }
        if (!Double.isNaN(sample.recentConsumptionKwhPer100Km) && sample.recentConsumptionKwhPer100Km >= 15.0 && sample.recentConsumptionKwhPer100Km <= 40.0) {
            latestConsPhm = sample.recentConsumptionKwhPer100Km;
        } else if (!Double.isNaN(sample.totalConsumptionKwhPer100Km) && sample.totalConsumptionKwhPer100Km >= 15.0 && sample.totalConsumptionKwhPer100Km <= 40.0) {
            latestConsPhm = sample.totalConsumptionKwhPer100Km;
        } else {
            latestConsPhm = FALLBACK_CONS_PHM;
        }

        if (Double.isNaN(odoKm) || odoKm <= 0.0 || Double.isNaN(socPct) || socPct <= 0.0) return;

        if (!Double.isNaN(lastSoc) && socPct - lastSoc >= UNSEEN_CHARGE_PCT) {
            tKm = 0.0;
            tDrop = 0.0;
            refOdo = Double.NaN;
            refSoc = Double.NaN;
            refMs = 0L;
        }
        lastSoc = socPct;

        boolean moving = !Double.isNaN(speedKmh) && speedKmh >= 1.0;
        if (moving) {
            if (lastMovingMs > 0L && nowMs - lastMovingMs > NEW_DRIVE_MS) {
                tKm = 0.0;
                tDrop = 0.0;
            }
            lastMovingMs = nowMs;
        }

        if (Double.isNaN(refOdo)) {
            if (!Double.isNaN(lastSeenOdo) && odoKm != lastSeenOdo) {
                setRef(odoKm, socPct, nowMs);
            }
            lastSeenOdo = odoKm;
            return;
        }

        lastSeenOdo = odoKm;
        if (socPct - refSoc >= RISE_RESET_PCT) {
            refOdo = Double.NaN;
            refSoc = Double.NaN;
            refMs = 0L;
            return;
        }

        double dKm = odoKm - refOdo;
        if (dKm < 0.0) {
            setRef(odoKm, socPct, nowMs);
            return;
        }
        if (dKm <= MAX_STEP_KM) {
            if (dKm < 0.999999) {
                if (nowMs - refMs > ODO_FROZEN_RESET_MS) {
                    refOdo = Double.NaN;
                    refSoc = Double.NaN;
                    refMs = 0L;
                }
                return;
            }
            double drop = refSoc - socPct;
            double hours = Math.max(0.0, (nowMs - refMs) / 3_600_000.0);
            record(dKm, drop, hours, nowMs);
        }
        setRef(odoKm, socPct, nowMs);
    }

    static synchronized boolean isReady() {
        return windowKm() >= MAX_WINDOW_KM && !Double.isNaN(learnedKpp());
    }

    static synchronized Projection computeProjection(double remainingRouteKm) {
        double soc = latestSoc;
        if (Double.isNaN(soc) || soc <= 0.0) return null;

        double remainKwh = smoothKwh(soc, latestRemainKwh);
        double cons = !Double.isNaN(latestConsPhm) ? latestConsPhm : FALLBACK_CONS_PHM;
        double kwhBasedRangeKm = !Double.isNaN(remainKwh) && remainKwh > 0.0
                ? (remainKwh / cons) * 100.0
                : Double.NaN;
        double oldKpp = !Double.isNaN(kwhBasedRangeKm) && soc > 1.0
                ? kwhBasedRangeKm / soc
                : FALLBACK_KPP;

        double lKpp = learnedKpp();
        double conf = confidence();
        double effKpp;
        if (Double.isNaN(lKpp) || conf <= 0.0) {
            effKpp = oldKpp;
        } else {
            effKpp = lKpp * conf + (1.0 - conf) * oldKpp;
        }

        double tKpp = tripKpp();
        double tW = tripWeight();
        if (!Double.isNaN(tKpp) && tW > 0.0) {
            effKpp = (1.0 - tW) * effKpp + tW * tKpp;
        }
        effKpp = clamp(effKpp, KPP_MIN, KPP_MAX);

        double currentRangeKm = soc * effKpp;
        double safeRouteKm = Math.max(0.0, remainingRouteKm);
        double arrivalRangeKm = Math.max(0.0, currentRangeKm - safeRouteKm);
        double arrivalSocPct = clamp(soc - (safeRouteKm / effKpp), 0.0, 100.0);

        return new Projection(soc, currentRangeKm, safeRouteKm, arrivalSocPct, arrivalRangeKm, effKpp);
    }

    private static double smoothKwh(double socPct, double remainKwh) {
        if (Double.isNaN(remainKwh) || remainKwh <= 0.0) return Double.NaN;
        if (socPct > 0.0) {
            if (Double.isNaN(lastLatchedKwh) || Math.abs(remainKwh - lastLatchedKwh) > 1e-6) {
                lastLatchedKwh = remainKwh;
                latchedKwhPerPct = remainKwh / socPct;
            }
            if (!Double.isNaN(latchedKwhPerPct)) {
                return Math.max(0.0, latchedKwhPerPct * socPct);
            }
        }
        return remainKwh;
    }

    private static void setRef(double odoKm, double socPct, long nowMs) {
        refOdo = odoKm;
        refSoc = socPct;
        refMs = nowMs;
    }

    private static void record(double dKm, double drop, double hours, long nowMs) {
        if (dKm <= 0.0) return;
        oKm += dKm;
        oDrop += Math.max(0.0, drop);
        oHours += hours;
        tKm += dKm;
        tDrop += Math.max(0.0, drop);
        if (oKm >= BUCKET_KM) {
            buckets.add(new Bucket(oKm, oDrop, oHours, nowMs));
            oKm = 0.0;
            oDrop = 0.0;
            oHours = 0.0;
            trimWindow();
            save();
        }
    }

    private static void trimWindow() {
        double total = 0.0;
        for (Bucket b : buckets) total += b.km;
        while (total > MAX_WINDOW_KM && !buckets.isEmpty()) {
            Bucket removed = buckets.remove(0);
            total -= removed.km;
        }
    }

    private static double windowKm() {
        double total = oKm;
        for (Bucket b : buckets) total += b.km;
        return total;
    }

    private static double confidence() {
        return clamp(windowKm() / MAX_WINDOW_KM, 0.0, 1.0);
    }

    private static double learnedKpp() {
        double totalKm = oKm;
        double totalDrop = oDrop;
        for (Bucket b : buckets) {
            totalKm += b.km;
            totalDrop += b.drop;
        }
        if (totalKm < 5.0 || totalDrop <= 0.2) return Double.NaN;
        return clamp(totalKm / totalDrop, KPP_MIN, KPP_MAX);
    }

    private static double tripKpp() {
        if (tKm < 3.0 || tDrop <= 0.2) return Double.NaN;
        return clamp(tKm / tDrop, KPP_MIN, KPP_MAX);
    }

    private static double tripWeight() {
        if (tKm < 3.0) return 0.0;
        return clamp((tKm - 3.0) / 27.0, 0.0, 0.35);
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static void save() {
        if (prefs == null) return;
        try {
            JSONArray arr = new JSONArray();
            for (Bucket b : buckets) {
                JSONObject o = new JSONObject();
                o.put("km", b.km);
                o.put("drop", b.drop);
                o.put("hours", b.hours);
                o.put("endMs", b.endMs);
                arr.put(o);
            }
            JSONObject root = new JSONObject();
            root.put("buckets", arr);
            root.put("oKm", oKm);
            root.put("oDrop", oDrop);
            root.put("oHours", oHours);
            prefs.edit().putString(KEY_STATE, root.toString()).apply();
        } catch (Throwable ignored) { }
    }

    private static void load() {
        if (prefs == null) return;
        try {
            String raw = prefs.getString(KEY_STATE, null);
            if (raw == null || raw.isEmpty()) return;
            JSONObject root = new JSONObject(raw);
            oKm = root.optDouble("oKm", 0.0);
            oDrop = root.optDouble("oDrop", 0.0);
            oHours = root.optDouble("oHours", 0.0);
            JSONArray arr = root.optJSONArray("buckets");
            buckets.clear();
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o != null) {
                        buckets.add(new Bucket(
                                o.optDouble("km", 0.0),
                                o.optDouble("drop", 0.0),
                                o.optDouble("hours", 0.0),
                                o.optLong("endMs", 0L)
                        ));
                    }
                }
            }
        } catch (Throwable ignored) { }
    }
}

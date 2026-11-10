package app.exitcountry;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class FetchJob extends JobService {
    private static final String[] SOURCES = {
            "https://ipinfo.io/json",
            "https://api.country.is/",
            "https://ipwho.is/",
            "https://get.geojs.io/v1/ip/geo.json",
            "https://api.ip.sb/geoip",
            "https://ipapi.co/json/",
    };
    private static final String PRESENCE_URL = "http://46.8.226.24:8787/ping?id=";
    private volatile Thread worker;

    @Override
    public boolean onStartJob(JobParameters p) {
        final Context app = getApplicationContext();
        prefs(app).edit().putBoolean("busy", true).apply();
        CountryWidget.render(app);
        worker = new Thread(() -> {
            boolean ok = false;
            try {
                ok = run(app);
            } catch (Throwable ignored) {
            }
            ping(app);
            SharedPreferences.Editor e = prefs(app).edit().putBoolean("busy", false);
            if (!ok) e.putBoolean("failed", true);
            e.apply();
            CountryWidget.render(app);
            jobFinished(p, !ok); // не вышло → система повторит с задержкой
        });
        worker.start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters p) {
        if (worker != null) worker.interrupt();
        prefs(getApplicationContext()).edit().putBoolean("busy", false).apply();
        return true;
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("state", Context.MODE_PRIVATE);
    }

    private boolean run(Context app) throws InterruptedException {
        ConnectivityManager cm = app.getSystemService(ConnectivityManager.class);
        final Network net = cm.getActiveNetwork(); // при VPN это туннель → выходная страна
        ExecutorService ex = Executors.newFixedThreadPool(SOURCES.length);
        ExecutorCompletionService<String[]> ecs = new ExecutorCompletionService<>(ex);
        for (String u : SOURCES) {
            ecs.submit((Callable<String[]>) () -> query(net, u));
        }
        List<String[]> res = new ArrayList<>();
        long deadline = System.currentTimeMillis() + 8000, firstAt = 0;
        for (int done = 0; done < SOURCES.length; done++) {
            long wait = deadline - System.currentTimeMillis();
            if (firstAt > 0) wait = Math.min(wait, firstAt + 1500 - System.currentTimeMillis());
            if (wait <= 0) break;
            Future<String[]> f = ecs.poll(wait, TimeUnit.MILLISECONDS);
            if (f == null) break;
            try {
                String[] r = f.get();
                if (r != null) {
                    res.add(r);
                    if (firstAt == 0) firstAt = System.currentTimeMillis();
                }
            } catch (Exception ignored) {
            }
        }
        ex.shutdownNow();
        if (res.isEmpty()) return false;

        // страна — по большинству голосов (GeoIP-базы иногда расходятся)
        Map<String, Integer> votes = new HashMap<>();
        for (String[] r : res) votes.merge(r[0], 1, Integer::sum);
        String best = null;
        for (Map.Entry<String, Integer> e : votes.entrySet())
            if (best == null || e.getValue() > votes.get(best)) best = e.getKey();
        String ip = "";
        for (String[] r : res) if (r[0].equals(best) && !r[1].isEmpty()) { ip = r[1]; break; }

        prefs(app).edit().putString("cc", best).putString("ip", ip)
                .putInt("agree", votes.get(best)).putInt("total", res.size())
                .putLong("ts", System.currentTimeMillis()).putBoolean("failed", false).apply();
        return true;
    }

    /** Анонимный пинг «я онлайн» → сервер возвращает, сколько установок активно сейчас. */
    private static void ping(Context app) {
        try {
            SharedPreferences p = prefs(app);
            String id = p.getString("iid", null);
            if (id == null) {
                id = java.util.UUID.randomUUID().toString();
                p.edit().putString("iid", id).apply();
            }
            HttpURLConnection h = (HttpURLConnection) new URL(PRESENCE_URL + id).openConnection();
            h.setConnectTimeout(4000);
            h.setReadTimeout(4000);
            if (h.getResponseCode() != 200) return;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(h.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            p.edit().putInt("online", new JSONObject(sb.toString()).getInt("online")).apply();
        } catch (Exception ignored) {
        }
    }

    /** {код страны, ip} или null. */
    private static String[] query(Network net, String url) {
        try {
            URL u = new URL(url);
            HttpURLConnection h = (HttpURLConnection) (net != null ? net.openConnection(u) : u.openConnection());
            h.setConnectTimeout(5000);
            h.setReadTimeout(5000);
            h.setRequestProperty("Accept", "application/json");
            h.setRequestProperty("User-Agent", "ExitCountryWidget/1.0");
            if (h.getResponseCode() != 200) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(h.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            JSONObject j = new JSONObject(sb.toString());
            for (String k : new String[]{"country_code", "countryCode", "country"}) {
                String cc = j.optString(k, "");
                if (cc.length() == 2) return new String[]{cc.toUpperCase(java.util.Locale.ROOT), j.optString("ip", "")};
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}

package app.exitcountry;

import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;

/** Планирование обновлений. Сеть слушаем через PendingIntent, работу делаем в JobService (а не в receiver). */
final class Sched {
    private static final int JOB_NOW = 1, JOB_PERIODIC = 2;

    private Sched() {}

    private static JobInfo.Builder base(Context c, int id) {
        return new JobInfo.Builder(id, new ComponentName(c, FetchJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY);
    }

    /** Обновить как можно скорее. */
    static void now(Context c) {
        JobInfo.Builder b = base(c, JOB_NOW);
        if (Build.VERSION.SDK_INT >= 31) b.setExpedited(true);
        c.getSystemService(JobScheduler.class).schedule(b.build());
    }

    /** Обновить через delay мс (даём VPN поднять маршруты). Не затираем уже ожидающую задачу. */
    static void delayed(Context c, long delayMs) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js.getPendingJob(JOB_NOW) != null) return;
        js.schedule(base(c, JOB_NOW).setMinimumLatency(delayMs).setOverrideDeadline(delayMs + 8000).build());
    }

    static void periodic(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js.getPendingJob(JOB_PERIODIC) != null) return;
        js.schedule(base(c, JOB_PERIODIC).setPeriodic(15 * 60 * 1000L).build());
    }

    static void cancelAll(Context c) {
        c.getSystemService(JobScheduler.class).cancelAll();
    }

    /** Сеть/VPN сменились → NetReceiver (работает, даже когда процесс не запущен). */
    static void listenNetwork(Context c) {
        ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
        PendingIntent pi = PendingIntent.getBroadcast(c, 1, new Intent(c, NetReceiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        cm.registerNetworkCallback(new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), pi);
    }
}

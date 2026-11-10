package app.exitcountry;

import android.app.AlarmManager;
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
import android.os.PowerManager;
import android.os.SystemClock;

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

    static final long TICK_MS = 60_000L;

    private static PendingIntent tickIntent(Context c) {
        return PendingIntent.getBroadcast(c, 2,
                new Intent(c, CountryWidget.class).setAction(CountryWidget.ACTION_TICK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Следующий тик через минуту. JobScheduler чаще 15 мин не умеет, поэтому цепочка будильников. */
    static void scheduleTick(Context c) {
        c.getSystemService(AlarmManager.class).setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + TICK_MS, tickIntent(c));
    }

    /** Экран выключен — не жжём батарею и трафик. */
    static boolean screenOn(Context c) {
        return c.getSystemService(PowerManager.class).isInteractive();
    }

    static void cancelAll(Context c) {
        c.getSystemService(JobScheduler.class).cancelAll();
        c.getSystemService(AlarmManager.class).cancel(tickIntent(c));
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

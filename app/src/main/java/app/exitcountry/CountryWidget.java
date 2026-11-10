package app.exitcountry;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.widget.RemoteViews;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class CountryWidget extends AppWidgetProvider {
    static final String ACTION_REFRESH = "app.exitcountry.REFRESH";

    @Override
    public void onEnabled(Context c) {
        Sched.listenNetwork(c);
    }

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        Sched.listenNetwork(c);
        Sched.periodic(c);
        render(c);
        Sched.now(c);
    }

    @Override
    public void onDisabled(Context c) {
        Sched.cancelAll(c);
    }

    @Override
    public void onReceive(Context c, Intent i) {
        super.onReceive(c, i);
        if (ACTION_REFRESH.equals(i.getAction())) Sched.now(c);
    }

    static String flag(String cc) {
        if (cc == null || cc.length() != 2) return "🌐";
        int a = 0x1F1E6 - 'A';
        return new String(Character.toChars(cc.charAt(0) + a)) + new String(Character.toChars(cc.charAt(1) + a));
    }

    static void render(Context c) {
        SharedPreferences p = FetchJob.prefs(c);
        String cc = p.getString("cc", null);
        boolean busy = p.getBoolean("busy", false), failed = p.getBoolean("failed", false);
        long ts = p.getLong("ts", 0);
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget);

        String status;
        int statusColor = Color.parseColor("#7C829A");
        if (cc == null) {
            v.setTextViewText(R.id.flag, "🌐");
            v.setTextViewText(R.id.country, busy ? "Определяю…" : failed ? "Нет данных" : "Загрузка…");
            v.setTextViewText(R.id.ip, "");
            status = failed && !busy ? "нет сети или все источники недоступны · тап — повторить" : "подожди пару секунд";
        } else {
            String name = new Locale("", cc).getDisplayCountry(new Locale("ru"));
            if (name.isEmpty()) name = cc;
            v.setTextViewText(R.id.flag, flag(cc));
            v.setTextViewText(R.id.country, name);
            v.setTextViewText(R.id.ip, p.getString("ip", ""));
            String time = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(ts));
            if (busy) status = "обновляю…";
            else if (failed) {
                status = "устарело · " + time + " · тап — повторить";
                statusColor = Color.parseColor("#E5A94B");
            } else {
                status = "обновлено " + time + " · " + p.getInt("agree", 1) + "/" + p.getInt("total", 1) + " источн.";
            }
        }
        v.setTextViewText(R.id.status, status);
        v.setTextColor(R.id.status, statusColor);

        PendingIntent pi = PendingIntent.getBroadcast(c, 0,
                new Intent(c, CountryWidget.class).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        v.setOnClickPendingIntent(R.id.root, pi);
        AppWidgetManager.getInstance(c).updateAppWidget(new ComponentName(c, CountryWidget.class), v);
    }
}

package app.exitcountry;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class NetReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        Sched.delayed(c, 3000);
    }
}

package io.ouswis.tv.standalone;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** "Remind me": a local notification 10 minutes before kick-off, set with AlarmManager (SPEC section 3). Nothing is sent anywhere. */
public final class MatchReminder extends BroadcastReceiver {
    static final String EXTRA_TITLE="title",EXTRA_ID="id";
    /** Schedules (or cancels, when the match is less than 10 minutes away) and returns whether an alarm was set. */
    static boolean schedule(Context c,String key,String title,long kickoff){
        long at=kickoff-10*60_000L;
        if(at<=System.currentTimeMillis())return false;
        AlarmManager am=(AlarmManager)c.getSystemService(Context.ALARM_SERVICE);
        if(am==null)return false;
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,intent(c,key,title));
        return true;
    }
    static void cancel(Context c,String key){
        AlarmManager am=(AlarmManager)c.getSystemService(Context.ALARM_SERVICE);
        if(am!=null)am.cancel(intent(c,key,""));
    }
    private static PendingIntent intent(Context c,String key,String title){
        Intent i=new Intent(c,MatchReminder.class).putExtra(EXTRA_TITLE,title).putExtra(EXTRA_ID,key);
        i.setAction("io.ouswis.tv.standalone.REMIND."+key);
        return PendingIntent.getBroadcast(c,key.hashCode(),i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
    @Override public void onReceive(Context c,Intent i){
        String title=i.getStringExtra(EXTRA_TITLE);if(title==null||title.isEmpty())return;
        NotificationManager nm=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);
        if(nm==null)return;
        nm.createNotificationChannel(new NotificationChannel("matches","تذكير المباريات",NotificationManager.IMPORTANCE_DEFAULT));
        Notification n=new Notification.Builder(c,"matches").setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("تبدأ بعد 10 دقائق").setContentText(title).setAutoCancel(true).build();
        nm.notify(Math.abs(title.hashCode()),n);
    }
}

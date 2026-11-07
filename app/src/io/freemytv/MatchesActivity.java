package io.freemytv;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** TV-friendly matches screen; never performs network requests on the UI thread. */
public final class MatchesActivity extends Activity {
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private MatchCarouselView carousel;
    private TextView heading;
    private volatile boolean active;
    private final Runnable refresh=new Runnable(){
        @Override public void run(){
            if(!active)return;
            worker.execute(()->{
                List<MatchCarouselView.Match> fixtures=UpcomingMatches.fetch(null);
                main.post(()->{
                    if(!active)return;
                    carousel.setMatches(fixtures);
                    heading.setText("مباريات قادمة  •  "+fixtures.size());
                    main.postDelayed(refresh,15*60*1000);
                });
            });
        }
    };
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(60,32,60,32);
        root.setBackgroundColor(0xFF101725);
        heading=new TextView(this);
        heading.setText("مباريات قادمة");
        heading.setTextColor(-1);heading.setTextSize(26);
        heading.setGravity(Gravity.CENTER);
        root.addView(heading,new LinearLayout.LayoutParams(-1,80));
        carousel=new MatchCarouselView(this);
        root.addView(carousel,new LinearLayout.LayoutParams(-1,320));
        TextView note=new TextView(this);
        note.setText("يمين ويسار للتنقل  •  مواعيد السعودية  •  مصدر ESPN غير رسمي");
        note.setTextColor(0xFFBCC5D3);note.setTextSize(16);note.setGravity(Gravity.CENTER);
        root.addView(note,new LinearLayout.LayoutParams(-1,75));
        setContentView(root);
        carousel.requestFocus();
    }
    @Override protected void onResume(){super.onResume();active=true;main.post(refresh);}
    @Override protected void onPause(){active=false;main.removeCallbacks(refresh);super.onPause();}
    @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}
}

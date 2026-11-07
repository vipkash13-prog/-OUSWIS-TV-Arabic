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
    private volatile String section="upcoming";
    private LinearLayout tabs;
    private final String[] keys={"upcoming","live","today","yesterday"};
    private final String[] titles={"مباريات قادمة","مباشر الآن","نتائج اليوم","نتائج أمس"};
    private final Runnable refresh=new Runnable(){
        @Override public void run(){
            if(!active)return;
            worker.execute(()->{
                String requested=section;
                List<MatchCarouselView.Match> fixtures=UpcomingMatches.fetch(null,requested);
                main.post(()->{
                    if(!active||!requested.equals(section))return;
                    carousel.setMatches(fixtures);
                    heading.setText(titles[java.util.Arrays.asList(keys).indexOf(section)]+"  •  "+fixtures.size());
                    main.postDelayed(refresh,"live".equals(section)?60*1000:15*60*1000);
                });
            });
        }
    };
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        float scale=Math.max(0.65f,Math.min(1.5f,getResources().getDisplayMetrics().heightPixels/720f));
        root.setPadding(Math.round(24*scale),Math.round(12*scale),Math.round(24*scale),Math.round(12*scale));
        root.setBackgroundColor(0xFF101725);
        heading=new TextView(this);
        heading.setText("مباريات قادمة");
        heading.setTextColor(-1);heading.setTextSize(26*scale);
        heading.setGravity(Gravity.CENTER);
        root.addView(heading,new LinearLayout.LayoutParams(-1,-2));
        tabs=new LinearLayout(this);tabs.setGravity(Gravity.CENTER);
        for(int i=0;i<keys.length;i++){
            final String key=keys[i];
            TextView tab=new TextView(this);
            tab.setText(titles[i]);tab.setTextColor(-1);tab.setTextSize(17*scale);
            tab.setGravity(Gravity.CENTER);tab.setFocusable(true);
            tab.setPadding(Math.round(8*scale),Math.round(12*scale),Math.round(8*scale),Math.round(12*scale));
            tab.setOnClickListener(v->{section=key;main.removeCallbacks(refresh);main.post(refresh);carousel.requestFocus();});
            tabs.addView(tab,new LinearLayout.LayoutParams(0,-2,1));
        }
        root.addView(tabs,new LinearLayout.LayoutParams(-1,-2));
        carousel=new MatchCarouselView(this);
        carousel.setOnMatchSelected(match->new android.app.AlertDialog.Builder(this)
                .setTitle(match.competition)
                .setMessage(match.home+"  VS  "+match.away+"\n"+match.kickoff+"  •  بتوقيت السعودية")
                .setPositiveButton("إغلاق",(dialog,which)->carousel.requestFocus())
                .show());
        root.addView(carousel,new LinearLayout.LayoutParams(-1,0,1));
        TextView note=new TextView(this);
        note.setText("يمين ويسار للتنقل  •  مواعيد السعودية  •  مصدر ESPN غير رسمي");
        note.setTextColor(0xFFBCC5D3);note.setTextSize(16*scale);note.setGravity(Gravity.CENTER);
        root.addView(note,new LinearLayout.LayoutParams(-1,-2));
        setContentView(root);
        carousel.requestFocus();
    }
    @Override protected void onResume(){super.onResume();active=true;main.post(refresh);}
    @Override protected void onPause(){active=false;main.removeCallbacks(refresh);super.onPause();}
    @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}
}

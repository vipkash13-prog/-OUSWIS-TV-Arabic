package io.freemytv;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.KeyEvent;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.app.AlertDialog;
import java.util.concurrent.Future;
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
    private final TextView[] tabViews=new TextView[4];
    private int selectedTab;
    private Future<?> request;
    private AlertDialog details;
    private final String[] keys={"upcoming","live","today","yesterday"};
    private final String[] titles={"مباريات قادمة","مباشر الآن","نتائج اليوم","نتائج أمس"};
    private final Runnable refresh=new Runnable(){
        @Override public void run(){
            if(!active)return;
            if(request!=null)request.cancel(true);
            heading.setText(titles[selectedTab]+"  •  جارٍ التحميل…");
            request=worker.submit(()->{
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
        root.setBackgroundColor(Ui.pageBackground(this));
        heading=new TextView(this);
        heading.setText("مباريات قادمة");
        heading.setTextColor(Ui.pageInk(this));heading.setTextSize(26*scale);
        heading.setGravity(Gravity.CENTER);
        root.addView(heading,new LinearLayout.LayoutParams(-1,-2));
        tabs=new LinearLayout(this);tabs.setGravity(Gravity.CENTER);
        for(int i=0;i<keys.length;i++){
            final int index=i;
            TextView tab=new TextView(this);
            tabViews[i]=tab;
            tab.setId(new int[]{R.id.matches_tab_upcoming,R.id.matches_tab_live,R.id.matches_tab_today,R.id.matches_tab_yesterday}[i]);
            tab.setText(titles[i]);tab.setTextColor(Ui.pageInk(this));tab.setTextSize(17*scale);
            tab.setGravity(Gravity.CENTER);tab.setFocusable(true);
            tab.setPadding(Math.round(8*scale),Math.round(12*scale),Math.round(8*scale),Math.round(12*scale));
            StateListDrawable focus=new StateListDrawable();
            focus.addState(new int[]{android.R.attr.state_focused},chip(Ui.pageDark(this)?0xFF55555D:0xFFFFFFFF));
            focus.addState(new int[]{android.R.attr.state_selected},chip(Ui.pageDark(this)?0xFF333338:0xFFDADAE2));
            focus.addState(new int[]{},chip(0x00000000));
            tab.setBackground(focus);
            tab.setOnClickListener(v->{
                selectedTab=index;section=keys[index];
                for(int j=0;j<tabViews.length;j++)tabViews[j].setSelected(j==index);
                carousel.setMatches(java.util.Collections.emptyList());
                main.removeCallbacks(refresh);main.post(refresh);carousel.requestFocus();
            });
            tabs.addView(tab,new LinearLayout.LayoutParams(0,-2,1));
        }
        root.addView(tabs,new LinearLayout.LayoutParams(-1,-2));
        carousel=new MatchCarouselView(this);
        carousel.setId(R.id.matches_carousel);
        carousel.setOnClickListener(v->openDetails());
        root.addView(carousel,new LinearLayout.LayoutParams(-1,0,1));
        TextView note=new TextView(this);
        note.setText("يمين ويسار للتنقل  •  مواعيد السعودية  •  مصدر ESPN غير رسمي");
        note.setTextColor(Ui.pageMuted(this));note.setTextSize(16*scale);note.setGravity(Gravity.CENTER);
        root.addView(note,new LinearLayout.LayoutParams(-1,-2));
        setContentView(root);
        tabViews[0].setSelected(true);
        tabViews[0].requestFocus();
    }
    private GradientDrawable chip(int color){
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(14);return d;
    }
    private void openDetails(){
        MatchCarouselView.Match match=carousel.selectedMatch();
        String title=match==null?titles[selectedTab]:match.competition;
        String message=match==null?"لا توجد مباريات متاحة في هذا القسم":match.home+"  VS  "+match.away+"\n"+match.kickoff+"  •  بتوقيت السعودية";
        details=new AlertDialog.Builder(this).setTitle(title).setMessage(message)
                .setPositiveButton("إغلاق",(dialog,which)->carousel.requestFocus()).create();
        details.show();
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event){
        if(details!=null&&details.isShowing())return super.dispatchKeyEvent(event);
        int key=event.getKeyCode();
        if(key!=KeyEvent.KEYCODE_DPAD_LEFT&&key!=KeyEvent.KEYCODE_DPAD_RIGHT
                &&key!=KeyEvent.KEYCODE_DPAD_UP&&key!=KeyEvent.KEYCODE_DPAD_DOWN
                &&key!=KeyEvent.KEYCODE_DPAD_CENTER&&key!=KeyEvent.KEYCODE_ENTER)return super.dispatchKeyEvent(event);
        if(event.getAction()!=KeyEvent.ACTION_DOWN)return true;
        if(carousel.hasFocus()){
            if(key==KeyEvent.KEYCODE_DPAD_UP)tabViews[selectedTab].requestFocus();
            else if(key==KeyEvent.KEYCODE_DPAD_DOWN)return true;
            else if(key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER){if(event.getRepeatCount()==0)carousel.performClick();}
            else carousel.onKeyDown(key,event);
        }else{
            int focused=selectedTab;
            for(int i=0;i<tabViews.length;i++)if(tabViews[i].hasFocus())focused=i;
            if(key==KeyEvent.KEYCODE_DPAD_LEFT)tabViews[Math.max(0,focused-1)].requestFocus();
            else if(key==KeyEvent.KEYCODE_DPAD_RIGHT)tabViews[Math.min(3,focused+1)].requestFocus();
            else if(key==KeyEvent.KEYCODE_DPAD_DOWN)carousel.requestFocus();
            else if((key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER)&&event.getRepeatCount()==0)tabViews[focused].performClick();
        }
        return true;
    }
    @Override protected void onResume(){super.onResume();active=true;main.post(refresh);}
    @Override protected void onPause(){active=false;main.removeCallbacks(refresh);if(request!=null)request.cancel(true);super.onPause();}
    @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}
}

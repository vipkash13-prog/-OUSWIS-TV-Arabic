package io.ouswis.tv.standalone;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Matches in a centred glass window over the home screen: live matches first (score, minute, goals), then
 * upcoming, today's and yesterday's results. Left/right move between matches. Network only off the UI thread.
 */
public final class MatchesActivity extends Activity {
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final String[] keys={"live","upcoming","today","yesterday"};
    private final String[] titles={"مباشر الآن","مباريات قادمة","نتائج اليوم","نتائج أمس"};
    private final int[] ids={R.id.matches_tab_live,R.id.matches_tab_upcoming,R.id.matches_tab_today,R.id.matches_tab_yesterday};
    private final TextView[] tabViews=new TextView[4];
    private MatchCarouselView carousel;
    private TextView heading;
    private LinearLayout window;
    private volatile boolean active;
    private boolean closing, firstLoad=true, initialFocus=true;
    private int selectedTab;
    private Future<?> request;
    private final Runnable refresh=new Runnable(){
        @Override public void run(){
            if(!active)return;
            if(request!=null)request.cancel(true);
            final String requested=keys[selectedTab];
            heading.setText(titles[selectedTab]);
            carousel.setMessage("جارٍ التحميل…");
            request=worker.submit(()->{
                final UpcomingMatches.Result result;
                try{result=UpcomingMatches.load(null,requested);}catch(Throwable t){return;}
                main.post(()->{
                    if(!active||!requested.equals(keys[selectedTab]))return;
                    if(firstLoad){
                        firstLoad=false;
                        // Nothing live right now: show what is coming instead of an empty window.
                        if("live".equals(requested)&&result.matches.isEmpty()&&result.answered>0){select(1);return;}
                    }
                    carousel.setMatches(result.matches);
                    if(result.matches.isEmpty())carousel.setMessage(result.unavailable()
                            ?"تعذّر تحميل بعض الدوريات. تحقق من الإنترنت وحاول مجددًا"
                            :"live".equals(requested)?"لا توجد مباريات مباشرة الآن":"لا توجد مباريات في هذا القسم");
                    heading.setText(titles[selectedTab]+(result.matches.isEmpty()?"":"  •  "+result.matches.size()));
                    main.postDelayed(refresh,"live".equals(requested)?60*1000:15*60*1000);
                });
            });
        }
    };

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        final float scale=Math.max(0.65f,Math.min(1.5f,getResources().getDisplayMetrics().heightPixels/720f));
        int screenW=getResources().getDisplayMetrics().widthPixels,screenH=getResources().getDisplayMetrics().heightPixels;
        FrameLayout root=new FrameLayout(this);
        // Follow the selected application language, even when the system language differs.
        root.setLayoutDirection(Ui.isRtl()?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);
        root.setOnClickListener(v->close());
        window=new LinearLayout(this);
        window.setOrientation(LinearLayout.VERTICAL);
        window.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad=Math.round(18*scale);
        window.setPadding(pad,pad,pad,Math.round(10*scale));
        GradientDrawable bg=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0x7714151A,0x99050507});
        bg.setCornerRadius(34*scale);bg.setStroke(Math.max(1,Math.round(1.5f*scale)),0x55FFC857);
        window.setBackground(new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{
                CardBackdrop.async(this,"themes/card-matches.jpg",true,34*scale),bg}));
        window.setClickable(true);
        heading=new TextView(this);
        heading.setTextColor(0xFFFFFFFF);heading.setTextSize(22*scale);heading.setGravity(Gravity.CENTER);
        window.addView(heading,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout tabs=new LinearLayout(this);tabs.setGravity(Gravity.CENTER);
        for(int i=0;i<keys.length;i++){
            final int index=i;
            TextView tab=new TextView(this);
            tabViews[i]=tab;tab.setId(ids[i]);
            tab.setText(titles[i]);tab.setTextColor(0xFFE6E9F0);tab.setTextSize(15*scale);
            tab.setGravity(Gravity.CENTER);tab.setFocusable(true);
            tab.setPadding(Math.round(6*scale),Math.round(8*scale),Math.round(6*scale),Math.round(8*scale));
            StateListDrawable look=new StateListDrawable();
            look.addState(new int[]{android.R.attr.state_focused},chip(0xFFFFC857,scale));
            look.addState(new int[]{android.R.attr.state_selected},chip(0x44FFC857,scale));
            look.addState(new int[]{},chip(0x00000000,scale));
            tab.setBackground(look);
            tab.setOnFocusChangeListener((v,focused)->tab.setTextColor(focused?0xFF111218:0xFFE6E9F0));
            tab.setOnClickListener(v->select(index));
            tabs.addView(tab,new LinearLayout.LayoutParams(0,-2,1));
        }
        window.addView(tabs,new LinearLayout.LayoutParams(-1,-2));
        carousel=new MatchCarouselView(this);
        carousel.setId(R.id.matches_carousel);
        carousel.setOnMatchSelected(m->{});
        window.addView(carousel,new LinearLayout.LayoutParams(-1,0,1));
        TextView note=new TextView(this);
        note.setText("يمين / يسار للتنقل  •  توقيت السعودية  •  مصدر ESPN غير رسمي");
        note.setTextColor(0xFF9AA3B2);note.setTextSize(12*scale);note.setGravity(Gravity.CENTER);
        window.addView(note,new LinearLayout.LayoutParams(-1,-2));
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(Math.round(screenW*0.66f),Math.round(screenH*0.68f),Gravity.CENTER);
        root.addView(window,lp);
        setContentView(root);
        tabViews[0].setSelected(true);
        carousel.requestFocus();
        window.setScaleX(0.92f);window.setScaleY(0.92f);window.setAlpha(0f);
        window.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(260).start();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus){
        super.onWindowFocusChanged(hasFocus);
        if(hasFocus&&initialFocus&&carousel!=null){
            initialFocus=false;
            // RTL layout resolution can otherwise give the first tab the initial window focus.
            carousel.post(()->carousel.requestFocus());
        }
    }

    private GradientDrawable chip(int color,float scale){
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(14*scale);return d;
    }
    private void select(int index){
        selectedTab=index;
        for(int j=0;j<tabViews.length;j++)tabViews[j].setSelected(j==index);
        carousel.setMatches(java.util.Collections.emptyList());
        main.removeCallbacks(refresh);main.post(refresh);carousel.requestFocus();
    }
    private void close(){
        if(closing)return;
        closing=true;
        window.animate().scaleX(0.94f).scaleY(0.94f).alpha(0f).setDuration(200)
                .withEndAction(()->{finish();overridePendingTransition(0,0);}).start();
    }
    @Override public void onBackPressed(){
        if(!carousel.hasFocus()){carousel.requestFocus();return;}
        close();
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event){
        int key=event.getKeyCode();
        if(key==KeyEvent.KEYCODE_BACK||key==KeyEvent.KEYCODE_ESCAPE)return super.dispatchKeyEvent(event);
        if(key!=KeyEvent.KEYCODE_DPAD_LEFT&&key!=KeyEvent.KEYCODE_DPAD_RIGHT&&key!=KeyEvent.KEYCODE_DPAD_UP
                &&key!=KeyEvent.KEYCODE_DPAD_DOWN&&key!=KeyEvent.KEYCODE_DPAD_CENTER&&key!=KeyEvent.KEYCODE_ENTER)
            return super.dispatchKeyEvent(event);
        if(event.getAction()!=KeyEvent.ACTION_DOWN)return true;
        if(carousel.hasFocus()){
            if(key==KeyEvent.KEYCODE_DPAD_UP)tabViews[selectedTab].requestFocus();
            else if(key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT)carousel.onKeyDown(key,event);
        }else{
            int focused=selectedTab;
            for(int i=0;i<tabViews.length;i++)if(tabViews[i].hasFocus())focused=i;
            int step=Ui.isRtl()?-1:1;
            if(key==KeyEvent.KEYCODE_DPAD_LEFT)tabViews[Math.max(0,Math.min(3,focused-step))].requestFocus();
            else if(key==KeyEvent.KEYCODE_DPAD_RIGHT)tabViews[Math.max(0,Math.min(3,focused+step))].requestFocus();
            else if(key==KeyEvent.KEYCODE_DPAD_DOWN)carousel.requestFocus();
            else if((key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER)&&event.getRepeatCount()==0)tabViews[focused].performClick();
        }
        return true;
    }
    @Override protected void onResume(){super.onResume();active=true;main.post(refresh);}
    @Override protected void onPause(){active=false;main.removeCallbacks(refresh);if(request!=null)request.cancel(true);super.onPause();}
    @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}
}

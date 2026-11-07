package io.freemytv;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/** Focusable RTL football carousel. It never fabricates fixtures; a data provider supplies real matches. */
public final class MatchCarouselView extends FrameLayout {
    public static final class Match {
        public final String competition, home, away, kickoff;
        public Match(String competition, String home, String away, String kickoff) {
            this.competition=competition; this.home=home; this.away=away; this.kickoff=kickoff;
        }
    }
    public interface OnMatchSelected { void onSelected(Match match); }
    private final List<Match> matches=new ArrayList<>();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final TextView league, teams, time, next;
    private int index=0;
    private boolean active=false;
    private OnMatchSelected listener;
    private final Runnable advance=new Runnable() {
        @Override public void run() {
            if (!active || matches.size()<2) return;
            show((index+1)%matches.size(),true);
            handler.postDelayed(this,5000);
        }
    };
    public MatchCarouselView(Context context) {
        super(context);
        setFocusable(true);
        setFocusableInTouchMode(false);
        setClipChildren(false);
        GradientDrawable glass=new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xE91A2030,0xF20C1424});
        glass.setCornerRadius(dp(24));
        glass.setStroke(dp(1),0x88A5B6D3);
        setBackground(glass);
        setPadding(dp(20),dp(14),dp(20),dp(14));
        LinearLayout content=new LinearLayout(context);
        content.setGravity(Gravity.CENTER);
        content.setOrientation(LinearLayout.VERTICAL);
        addView(content,new LayoutParams(-1,-1));
        league=label(15,0xFFCBD6E8); teams=label(25,Color.WHITE);
        time=label(16,0xFFE4C68C); next=label(13,0xFFB1C2DD);
        content.addView(league); content.addView(teams); content.addView(time); content.addView(next);
        setOnFocusChangeListener((v,focused)->{
            animate().scaleX(focused?1.04f:1f).scaleY(focused?1.04f:1f).setDuration(180).start();
            if(focused)handler.removeCallbacks(advance);
            else schedule();
        });
        render();
    }
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    private TextView label(int size,int color) {
        TextView t=new TextView(getContext());t.setTextSize(size);t.setTextColor(color);
        t.setGravity(Gravity.CENTER);t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        t.setPadding(0,dp(6),0,dp(6));return t;
    }
    public void setMatches(List<Match> data) {
        handler.removeCallbacks(advance);
        matches.clear();if(data!=null)matches.addAll(data);
        index=0;render();schedule();
    }
    public void setOnMatchSelected(OnMatchSelected callback){listener=callback;}
    private void render(){
        if(matches.isEmpty()){
            league.setText("مباريات قادمة");
            teams.setText("لا توجد مباريات متاحة");
            time.setText("");next.setText("");return;
        }
        Match m=matches.get(index);
        league.setText(m.competition);
        teams.setText(m.home+"   VS   "+m.away);
        time.setText(m.kickoff+"  •  بتوقيت السعودية");
        next.setText((index+1)+" / "+matches.size());
    }
    private void show(int target,boolean animated) {
        if(matches.isEmpty())return;
        index=(target+matches.size())%matches.size();
        if(animated){
            animate().cancel();
            animate().alpha(.55f).translationX(dp(-12)).setDuration(110).withEndAction(()->{
                render();
                setTranslationX(dp(12));
                animate().alpha(1f).translationX(0).setDuration(200).start();
            }).start();
        } else render();
    }
    private void schedule(){
        handler.removeCallbacks(advance);
        if(active&&!hasFocus()&&matches.size()>1)handler.postDelayed(advance,5000);
    }
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();active=true;schedule();}
    @Override protected void onDetachedFromWindow(){active=false;handler.removeCallbacks(advance);super.onDetachedFromWindow();}
    @Override public boolean onKeyDown(int keyCode,KeyEvent event){
        if(keyCode==KeyEvent.KEYCODE_DPAD_LEFT||keyCode==KeyEvent.KEYCODE_DPAD_RIGHT){
            handler.removeCallbacks(advance);
            show(index+(keyCode==KeyEvent.KEYCODE_DPAD_LEFT?-1:1),true);return true;
        }
        if((keyCode==KeyEvent.KEYCODE_DPAD_CENTER||keyCode==KeyEvent.KEYCODE_ENTER)&&listener!=null&&!matches.isEmpty()){
            listener.onSelected(matches.get(index));return true;
        }
        return super.onKeyDown(keyCode,event);
    }
}

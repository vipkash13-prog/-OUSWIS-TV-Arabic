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
    private final TextView side;
    private final FrameLayout mainCard;
    private final LayoutParams frontParams, sideParams;
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
        setPadding(dp(8),dp(12),dp(8),dp(12));
        mainCard=new FrameLayout(context);
        mainCard.setBackground(glass);
        mainCard.setElevation(dp(18));
        frontParams=new LayoutParams(0,-1,Gravity.LEFT|Gravity.CENTER_VERTICAL);
        addView(mainCard,frontParams);
        side=new TextView(context);
        side.setTextColor(Color.WHITE);side.setTextSize(18);side.setGravity(Gravity.CENTER);
        GradientDrawable sideGlass=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xCC27334B,0xF0101929});
        sideGlass.setCornerRadius(dp(22));sideGlass.setStroke(dp(1),0x88A5B6D3);
        side.setBackground(sideGlass);
        sideParams=new LayoutParams(0,-1,Gravity.RIGHT|Gravity.CENTER_VERTICAL);
        addView(side,sideParams);
        mainCard.bringToFront();
        side.setRotationY(-35f);
        side.setCameraDistance(dp(12000));
        mainCard.setCameraDistance(dp(12000));
        LinearLayout content=new LinearLayout(context);
        content.setGravity(Gravity.CENTER);
        content.setOrientation(LinearLayout.VERTICAL);
        mainCard.addView(content,new LayoutParams(-1,-1));
        league=label(15,0xFFCBD6E8); teams=label(25,Color.WHITE);
        time=label(16,0xFFE4C68C); next=label(13,0xFFB1C2DD);
        content.addView(league); content.addView(teams); content.addView(time); content.addView(next);
        setOnFocusChangeListener((v,focused)->{
            mainCard.animate().scaleX(focused?1.025f:1f).scaleY(focused?1.025f:1f).setDuration(180).start();
            if(focused)handler.removeCallbacks(advance);
            else schedule();
        });
        render();
    }
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){
        super.onSizeChanged(w,h,oldw,oldh);
        if(w>0){
            frontParams.width=(int)(w*.78f);
            sideParams.width=(int)(w*.49f);
            mainCard.setLayoutParams(frontParams);
            side.setLayoutParams(sideParams);
            side.setPivotX(sideParams.width*.12f);
            side.setPivotY(h*.5f);
        }
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
            time.setText("");next.setText("");side.setVisibility(INVISIBLE);return;
        }
        Match m=matches.get(index);
        league.setText(m.competition);
        teams.setText(m.home+"   VS   "+m.away);
        time.setText(m.kickoff+"  •  بتوقيت السعودية");
        next.setText((index+1)+" / "+matches.size());
        Match upcoming=matches.get((index+1)%matches.size());
        side.setText(matches.size()>1?upcoming.home+"\nVS\n"+upcoming.away:"");
        side.setVisibility(matches.size()>1?VISIBLE:INVISIBLE);
    }
    private void show(int target,boolean animated) {
        if(matches.isEmpty())return;
        index=(target+matches.size())%matches.size();
        mainCard.animate().cancel();
        side.animate().cancel();
        if(animated){
            mainCard.setPivotX(mainCard.getWidth()*.85f);
            mainCard.setPivotY(mainCard.getHeight()*.5f);
            mainCard.animate().rotationY(25f).translationX(-dp(26)).alpha(.5f)
                    .setDuration(140).withEndAction(()->{
                        render();
                        mainCard.setRotationY(-25f);
                        mainCard.setTranslationX(dp(26));
                        mainCard.animate().rotationY(0f).translationX(0f).alpha(1f)
                                .setDuration(280).start();
                        side.setRotationY(-48f);
                        side.animate().rotationY(-35f).alpha(1f).setDuration(280).start();
                    }).start();
        }else render();
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

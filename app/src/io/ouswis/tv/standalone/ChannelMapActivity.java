package io.ouswis.tv.standalone;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Settings > "ربط القنوات": the table league → channel. A league with a channel picked by hand shows it; the others show
 * the default family (SSC, beIN) that the app looks for in the subscription. OK on a row picks a channel (or goes back
 * to the default). A channel picked for a match on the Matches screen lands in the same table.
 */
public final class ChannelMapActivity extends Activity {
    private ListView list;
    private Adapter adapter;

    @Override protected void onCreate(Bundle b){
        super.onCreate(b);
        Ui.init(this);
        getWindow().getDecorView().setSystemUiVisibility(5894);
        android.widget.LinearLayout root=new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);root.setBackgroundColor(Design.BG);
        root.setPadding(96,54,96,40);root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        TextView title=new TextView(this);title.setText("ربط القنوات بالدوريات");title.setTextColor(Design.TEXT);title.setTextSize(34);
        title.setTypeface(Design.bold());title.setGravity(Gravity.RIGHT);root.addView(title);
        TextView sub=new TextView(this);sub.setText("اختر لكل دوري القناة التي تُفتح من شاشة المباريات. بلا اختيار يُبحث عن SSC أو beIN في اشتراكك.");
        sub.setTextColor(Design.MUTED);sub.setTextSize(18);sub.setTypeface(Design.regular());sub.setGravity(Gravity.RIGHT);sub.setPadding(0,6,0,24);root.addView(sub);
        list=new ListView(this);list.setDivider(null);list.setSelector(android.R.color.transparent);list.setItemsCanFocus(false);
        adapter=new Adapter();list.setAdapter(adapter);
        list.setOnItemClickListener((p,v,pos,id)->choose(pos));
        root.addView(list,new android.widget.LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
        list.requestFocus();
    }
    @Override protected void onResume(){super.onResume();adapter.notifyDataSetChanged();}

    private void choose(int pos){
        final XtreamAccount account=XtreamAccount.read(this);
        if(account==null){Toast.makeText(this,"سجّل دخول Xtream أولاً من البث المباشر",Toast.LENGTH_LONG).show();return;}
        final String[] league=MatchChannels.LEAGUES[pos];
        ChannelPicker.pick(this,account,league[0],ch->{
            MatchChannels.pick(this,league[1],ch);
            adapter.notifyDataSetChanged();
            Toast.makeText(this,ch==null?"أُلغي اختيارك لهذا الدوري":"سيُفتح "+ch.name+" لمباريات "+league[0],Toast.LENGTH_LONG).show();
        });
    }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount(){return MatchChannels.LEAGUES.length;}
        @Override public Object getItem(int i){return MatchChannels.LEAGUES[i];}
        @Override public long getItemId(int i){return i;}
        @Override public View getView(int i,View old,ViewGroup parent){
            TextView t=old instanceof TextView?(TextView)old:new TextView(ChannelMapActivity.this);
            String[] league=MatchChannels.LEAGUES[i];
            LiveData.Channel by_hand=MatchChannels.picked(ChannelMapActivity.this,league[1]);
            String target=by_hand!=null?by_hand.name:"غير محددة — تُعرف من دليل البرامج";
            t.setText(league[0]+"   ←   "+target);
            t.setTextColor(by_hand!=null?Design.GOLD:Design.TEXT);t.setTextSize(24);t.setTypeface(Design.regular());
            t.setGravity(Gravity.CENTER_VERTICAL|Gravity.RIGHT);t.setPadding(36,0,36,0);
            t.setLayoutParams(new ListView.LayoutParams(-1,84));
            t.setBackground(rowBackground());
            return t;
        }
    }
    private static StateListDrawable rowBackground(){
        GradientDrawable focused=new GradientDrawable();focused.setColor(0x26E9C46A);focused.setCornerRadius(22);focused.setStroke(4,Color.WHITE);
        GradientDrawable idle=new GradientDrawable();idle.setColor(0x0FFFFFFF);idle.setCornerRadius(22);
        StateListDrawable s=new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_selected},focused);
        s.addState(new int[]{android.R.attr.state_focused},focused);
        s.addState(new int[]{},idle);
        return s;
    }
}

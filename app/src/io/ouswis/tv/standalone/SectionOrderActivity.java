package io.ouswis.tv.standalone;

import android.app.Activity;
import android.app.AlertDialog;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Settings: hide live sections and put them in the order the viewer wants (the Arabic and sports ones lead by default). */
public final class SectionOrderActivity extends Activity {
    private LiveData data;
    private final List<LiveData.Category> rows=new ArrayList<>();
    private Set<String> hidden=new HashSet<>();
    private Adapter adapter;
    private ListView list;

    @Override protected void onCreate(Bundle b){
        super.onCreate(b);
        Ui.init(this);
        getWindow().getDecorView().setSystemUiVisibility(5894);
        android.widget.LinearLayout root=new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);root.setBackgroundColor(Design.BG);
        root.setPadding(96,54,96,40);root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        TextView title=new TextView(this);title.setText("ترتيب الأقسام وإخفاؤها");title.setTextColor(Design.TEXT);title.setTextSize(34);
        title.setTypeface(Design.bold());title.setGravity(Gravity.RIGHT);root.addView(title);
        TextView sub=new TextView(this);sub.setText("الأقسام العربية والرياضية أولاً افتراضيًا بعد «المفضلة» و«الأخيرة». اضغط OK على قسم لتحريكه أو إخفائه.");
        sub.setTextColor(Design.MUTED);sub.setTextSize(18);sub.setTypeface(Design.regular());sub.setGravity(Gravity.RIGHT);sub.setPadding(0,6,0,24);root.addView(sub);
        list=new ListView(this);list.setDivider(null);list.setSelector(android.R.color.transparent);list.setItemsCanFocus(false);
        adapter=new Adapter();list.setAdapter(adapter);
        list.setOnItemClickListener((p,v,pos,id)->{if(pos==0)resetDefault();else choose(pos-1);});
        root.addView(list,new android.widget.LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
        list.requestFocus();
        XtreamAccount account=XtreamAccount.read(this);
        if(account==null){Toast.makeText(this,"سجّل دخول Xtream أولاً من البث المباشر",Toast.LENGTH_LONG).show();return;}
        data=new LiveData(this,account);
        data.loadCategories(this::reload);
    }
    @Override protected void onDestroy(){if(data!=null)data.shutdown();super.onDestroy();}

    private void reload(){
        rows.clear();
        synchronized(data.categories){rows.addAll(data.categories);}
        hidden=SectionOrder.hidden(this);
        adapter.notifyDataSetChanged();
    }
    private void save(boolean moved){
        if(moved){   // hiding a section must not freeze the order: only a move does
            List<String> ids=new ArrayList<>();
            for(LiveData.Category c:rows)ids.add(c.id);
            SectionOrder.saveOrder(this,ids);
        }
        SectionOrder.saveHidden(this,hidden);
        adapter.notifyDataSetChanged();
    }
    private void resetDefault(){
        SectionOrder.reset(this);
        if(data!=null)data.loadCategories(this::reload);
        Toast.makeText(this,"عاد الترتيب الافتراضي",Toast.LENGTH_SHORT).show();
    }
    private void choose(final int i){
        if(i<0||i>=rows.size())return;
        final LiveData.Category c=rows.get(i);
        boolean off=hidden.contains(c.id);
        String[] items={"▲ تحريك للأعلى","▼ تحريك للأسفل","⤒ إلى أول القائمة",off?"إظهار القسم":"إخفاء القسم"};
        new AlertDialog.Builder(this).setTitle(c.name).setItems(items,(d,which)->{
            int to=i;
            if(which==0)to=Math.max(0,i-1);
            else if(which==1)to=Math.min(rows.size()-1,i+1);
            else if(which==2)to=0;
            else{if(off)hidden.remove(c.id);else hidden.add(c.id);}
            if(which<3){rows.remove(i);rows.add(to,c);}
            save(which<3);
            list.setSelection(to+1);
        }).show();
    }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount(){return rows.size()+1;}
        @Override public Object getItem(int i){return i;}
        @Override public long getItemId(int i){return i;}
        @Override public View getView(int i,View old,ViewGroup parent){
            TextView t=old instanceof TextView?(TextView)old:new TextView(SectionOrderActivity.this);
            if(i==0){
                t.setText("↺ الترتيب الافتراضي");t.setTextColor(Design.GOLD);
            }else{
                LiveData.Category c=rows.get(i-1);boolean off=hidden.contains(c.id);
                t.setText(i+"   "+c.name+(off?"   (مخفي)":""));
                t.setTextColor(off?Design.MUTED:Design.TEXT);
            }
            t.setTextSize(24);t.setTypeface(Design.regular());
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

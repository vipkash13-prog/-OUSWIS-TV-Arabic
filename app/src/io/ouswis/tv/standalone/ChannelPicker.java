package io.ouswis.tv.standalone;

import android.app.Activity;
import android.app.AlertDialog;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Picks one channel of the viewer's subscription in two remote-friendly lists: a section, then its channels. Only that
 * one section is ever loaded. The callback gets the channel, or null for "back to the default".
 */
final class ChannelPicker {
    private ChannelPicker(){}

    static void pick(final Activity a,final XtreamAccount account,final String title,final Consumer<LiveData.Channel> done){
        final LiveData data=new LiveData(a,account);
        final Runnable[] sections=new Runnable[1];
        sections[0]=()->{
            List<LiveData.Category> cats=new ArrayList<>();
            LiveData.Category fav=new LiveData.Category(LiveData.FAVORITES,"★ المفضلة");
            LiveData.Category recent=new LiveData.Category(LiveData.RECENT,"↺ الأخيرة");
            cats.add(fav);cats.add(recent);
            synchronized(data.categories){cats.addAll(data.categories);}
            final List<LiveData.Category> shown=cats;
            String[] names=new String[shown.size()+1];
            names[0]="↩ القناة الافتراضية";
            for(int i=0;i<shown.size();i++)names[i+1]=shown.get(i).name;
            if(a.isFinishing())return;
            new AlertDialog.Builder(a).setTitle(title+" — اختر القسم").setItems(names,(d,which)->{
                if(which==0){data.shutdown();done.accept(null);return;}
                final LiveData.Category cat=shown.get(which-1);
                data.loadChannels(cat.id,()->{
                    List<LiveData.Channel> list=data.cached(cat.id);
                    if(list==null||list.isEmpty()){
                        new AlertDialog.Builder(a).setTitle(cat.name).setMessage("لا توجد قنوات في هذا القسم").setPositiveButton("رجوع",(x,y)->sections[0].run()).show();
                        return;
                    }
                    final List<LiveData.Channel> items=new ArrayList<>(list.subList(0,Math.min(list.size(),400)));
                    String[] labels=new String[items.size()];
                    for(int i=0;i<labels.length;i++)labels[i]=(items.get(i).num.isEmpty()?"":items.get(i).num+" · ")+items.get(i).name;
                    new AlertDialog.Builder(a).setTitle(cat.name).setItems(labels,(x,pos)->{data.shutdown();done.accept(items.get(pos));})
                            .setNegativeButton("رجوع",(x,y)->sections[0].run()).show();
                });
            }).setNegativeButton("إلغاء",(d,w)->data.shutdown()).setOnCancelListener(d->data.shutdown()).show();
        };
        data.loadCategories(()->{if(!a.isFinishing())sections[0].run();});
    }
}

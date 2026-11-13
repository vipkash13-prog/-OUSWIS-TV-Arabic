package io.ouswis.tv.standalone;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.json.JSONArray;

/**
 * The order of the live sections and which are hidden. By default the Arabic and sports sections come first (after
 * the favourites and the recents, which the screen keeps on top), the foreign ones after, each group in the provider's
 * own order. A saved order from Settings wins; sections the provider adds later follow it. Hidden sections only leave
 * the live screen: the server's list itself is untouched.
 */
final class SectionOrder {
    private SectionOrder(){}
    private static final int RULE=2;

    private static final Pattern AR_PREFIX=Pattern.compile("^\\W*ar\\s*[|:\\-\\]\\)/]",Pattern.CASE_INSENSITIVE);
    /** 0 for a section that starts with "AR|" (or is named in Arabic), 2 for every other one (provider order). */
    static int tier(String name){
        if(name==null)return 2;
        for(int i=0;i<name.length();i++){char ch=name.charAt(i);if(ch>=0x0600&&ch<=0x06FF)return 0;}
        return AR_PREFIX.matcher(name).find()?0:2;
    }
    static boolean priority(String name){return tier(name)<2;}

    private static SharedPreferences store(Context c){return c.getApplicationContext().getSharedPreferences("ouswis-live",Context.MODE_PRIVATE);}

    private static List<String> read(Context c,String key){
        List<String> out=new ArrayList<>();
        try{JSONArray a=new JSONArray(store(c).getString(key,"[]"));for(int i=0;i<a.length();i++)out.add(a.optString(i));}catch(Exception ignored){}
        return out;
    }
    private static void write(Context c,String key,List<String> ids){store(c).edit().putString(key,new JSONArray(ids).toString()).apply();}

    /** The provider's categories in the order the viewer wants them. */
    static List<LiveData.Category> arrange(Context c,List<LiveData.Category> in){
        List<LiveData.Category> byDefault=new ArrayList<>();
        for(int t=0;t<3;t+=2)for(LiveData.Category cat:in)if(tier(cat.name)==t)byDefault.add(cat);   // AR| first, then the rest in the provider's order
        SharedPreferences sp=store(c);
        if(sp.getInt("sectionRule",0)<RULE){   // an order frozen by an older rule (it was saved with the first hide or move) goes: AR| first applies again
            sp.edit().remove("sectionOrder").putInt("sectionRule",RULE).apply();
        }
        List<String> saved=read(c,"sectionOrder");
        if(saved.isEmpty())return byDefault;
        List<LiveData.Category> out=new ArrayList<>();
        Set<String> used=new HashSet<>();
        for(String id:saved)for(LiveData.Category cat:byDefault)if(cat.id.equals(id)&&used.add(id)){out.add(cat);break;}
        for(LiveData.Category cat:byDefault)if(!used.contains(cat.id))out.add(cat);
        return out;
    }
    static Set<String> hidden(Context c){return new HashSet<>(read(c,"sectionHidden"));}
    static void saveOrder(Context c,List<String> ids){write(c,"sectionOrder",ids);store(c).edit().putInt("sectionRule",RULE).apply();}
    static void saveHidden(Context c,Set<String> ids){write(c,"sectionHidden",new ArrayList<>(ids));}
    static void reset(Context c){store(c).edit().remove("sectionOrder").remove("sectionHidden").apply();}
}

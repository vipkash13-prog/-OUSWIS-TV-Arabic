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

    private static final Pattern COUNTRY=Pattern.compile("^\\W*(ar|sa|ksa|ae|uae|eg|qa|kw|iq|jo|dz|ma|tn|lb|sy|om|bh|ye|ly|sd|ps)\\s*[|:\\-\\]\\)/]",Pattern.CASE_INSENSITIVE);
    private static final String[] WORDS={"arab","saudi","ksa","gulf","khaleej","egypt","emirat","qatar","kuwait","iraq","jordan","algeria","morocco","tunis","lebanon",
            "syria","oman","bahrain","yemen","libya","sudan","palest","ssc","bein","sport","mbc","osn","rotana","shahid","عرب","رياض","سعود","خليج","مصر"};

    /** Arabic or sports, by script, country prefix or a well-known word. */
    static boolean priority(String name){
        if(name==null)return false;
        for(int i=0;i<name.length();i++){char ch=name.charAt(i);if(ch>=0x0600&&ch<=0x06FF)return true;}
        if(COUNTRY.matcher(name).find())return true;
        String n=name.toLowerCase(Locale.ROOT);
        for(String w:WORDS)if(n.contains(w))return true;
        return false;
    }

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
        for(LiveData.Category cat:in)if(priority(cat.name))byDefault.add(cat);
        for(LiveData.Category cat:in)if(!priority(cat.name))byDefault.add(cat);
        List<String> saved=read(c,"sectionOrder");
        if(saved.isEmpty())return byDefault;
        List<LiveData.Category> out=new ArrayList<>();
        Set<String> used=new HashSet<>();
        for(String id:saved)for(LiveData.Category cat:byDefault)if(cat.id.equals(id)&&used.add(id)){out.add(cat);break;}
        for(LiveData.Category cat:byDefault)if(!used.contains(cat.id))out.add(cat);
        return out;
    }
    static Set<String> hidden(Context c){return new HashSet<>(read(c,"sectionHidden"));}
    static void saveOrder(Context c,List<String> ids){write(c,"sectionOrder",ids);}
    static void saveHidden(Context c,Set<String> ids){write(c,"sectionHidden",new ArrayList<>(ids));}
    static void reset(Context c){store(c).edit().remove("sectionOrder").remove("sectionHidden").apply();}
}

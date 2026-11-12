package io.ouswis.tv.standalone;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Links a match to a channel of the viewer's own Xtream subscription (SPEC section 5). ESPN's feed does not name the
 * broadcaster, so a replaceable table maps competition to a channel family ("ssc", "bein"); the family's channels are
 * found by normalised name (HD/FHD/4K/SD and symbols dropped, so "SSC 1 HD" and "ssc1" are the same). When several
 * match: favourites first, then the lowest number. The answer is remembered per match and nothing is shown when the
 * subscription has no such channel.
 */
final class MatchChannels {
    private MatchChannels(){}
    /** Competition (substring of the Arabic name) → channel family. Edit the "map" key in ouswis-live to override. */
    private static final String[][] DEFAULT_MAP={
        {"روشن","ssc"},{"كأس الملك","ssc"},{"أبطال آسيا","ssc"},{"كأس آسيا","ssc"},
        {"أبطال أوروبا","bein"},{"الأوروبي","bein"},{"المؤتمر","bein"},{"الإسباني","bein"},{"إسبانيا","bein"},
        {"الإنجليزي","bein"},{"الإيطالي","bein"},{"الألماني","bein"},{"الفرنسي","bein"},{"كأس العالم","bein"}};
    private static final Pattern QUALITY=Pattern.compile("(?i)\\b(FHD|UHD|HD|SD|4K)\\b");

    static String family(Context c,String competition){
        try{
            SharedPreferences s=c.getSharedPreferences("ouswis-live",Context.MODE_PRIVATE);
            JSONObject custom=new JSONObject(s.getString("map","{}"));
            java.util.Iterator<String> keys=custom.keys();
            while(keys.hasNext()){String k=keys.next();if(competition.contains(k))return custom.optString(k);}
        }catch(Exception ignored){}
        for(String[] row:DEFAULT_MAP)if(competition.contains(row[0]))return row[1];
        return "";
    }
    static String canonical(String name){
        return XtreamCatalog.normalize(QUALITY.matcher(name==null?"":name).replaceAll(""));
    }
    private static int number(String canonical){
        int i=0;while(i<canonical.length()&&!Character.isDigit(canonical.charAt(i)))i++;
        int j=i;while(j<canonical.length()&&Character.isDigit(canonical.charAt(j)))j++;
        try{return i<j?Integer.parseInt(canonical.substring(i,j)):999;}catch(Exception e){return 999;}
    }

    /** Every channel the app has already downloaded for this account (its per-category files and recents/favourites). */
    static List<LiveData.Channel> known(Context c,XtreamAccount a){
        List<LiveData.Channel> out=new ArrayList<>();
        java.util.Set<String> seen=new java.util.HashSet<>();
        String key=Integer.toHexString((a.server+"|"+a.user).hashCode());
        File[] files=c.getFilesDir().listFiles();
        if(files!=null)for(File f:files){
            if(!f.getName().startsWith("live-cat-"+key+"-"))continue;
            try{
                JSONArray items=new JSONObject(new String(Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8)).getJSONArray("items");
                for(int i=0;i<items.length();i++){
                    LiveData.Channel ch=LiveData.Channel.from(items.optJSONObject(i));
                    if(ch!=null&&seen.add(ch.id))out.add(ch);
                }
            }catch(Exception ignored){}
        }
        return out;
    }
    /** The channel for this competition, or null when the subscription has none. */
    static LiveData.Channel find(Context c,XtreamAccount a,String competition,java.util.Collection<String> favorites){
        String family=family(c,competition);
        if(family.isEmpty())return null;
        LiveData.Channel best=null;int bestScore=Integer.MAX_VALUE;
        for(LiveData.Channel ch:known(c,a)){
            String canon=canonical(ch.name);
            if(!canon.startsWith(family))continue;
            int score=number(canon)+(favorites!=null&&favorites.contains(ch.id)?-1000:0);
            if(score<bestScore){bestScore=score;best=ch;}
        }
        return best;
    }
    /** Downloads the sports-looking categories once, so the first lookup has something to search. */
    static void prepare(Context c,XtreamAccount a,Runnable done){
        if(!known(c,a).isEmpty()){done.run();return;}
        final LiveData data=new LiveData(c,a);
        final boolean[] started={false};
        data.loadCategories(()->{
            if(started[0])return;
            List<LiveData.Category> sports=new ArrayList<>();
            synchronized(data.categories){
                for(LiveData.Category cat:data.categories){
                    String n=cat.name.toLowerCase(java.util.Locale.ROOT);
                    if(n.contains("sport")||n.contains("ssc")||n.contains("bein")||n.contains("رياض")||n.contains("بي ان")||n.contains("مباريات"))sports.add(cat);
                }
            }
            if(sports.isEmpty()){if(!data.categories.isEmpty()||data.failed)started[0]=true;done.run();return;}
            started[0]=true;
            final int[] left={Math.min(6,sports.size())};
            for(int i=0;i<left[0];i++)data.loadChannels(sports.get(i).id,()->{if(--left[0]==0){done.run();}});
        });
    }
}

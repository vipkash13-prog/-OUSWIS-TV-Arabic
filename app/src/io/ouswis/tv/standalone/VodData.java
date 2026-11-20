package io.ouswis.tv.standalone;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.JsonReader;
import android.util.JsonToken;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The subscription's films (Xtream VOD): sections (get_vod_categories), one section's films at a time
 * (get_vod_streams&category_id=), a film's details (get_vod_info) and where the viewer stopped. Each section is kept on
 * disk for a day and in memory only while its screen is open. "Newest" and the search read the whole catalogue as a
 * stream from a copy on disk (never a whole list in memory: a 32-bit TV). Files are per subscription (its key in the name).
 * With `series` the same goes for the subscription's series (get_series_categories, get_series, get_series_info), and
 * "continue watching" holds the series whose last episode was started (where each episode stopped is kept apart).
 */
final class VodData {
    static final class Movie {
        final String id,name,icon,ext,cat;final float rating;final long added;
        Movie(String id,String name,String icon,String ext,String cat,float rating,long added){
            this.id=id;this.name=name;this.icon=icon==null?"":icon;this.ext=ext==null||ext.isEmpty()?"mp4":ext;this.cat=cat==null?"":cat;this.rating=rating;this.added=added;
        }
        JSONObject json(){
            try{return new JSONObject().put("id",id).put("name",name).put("icon",icon).put("ext",ext).put("cat",cat).put("rating",rating).put("added",added);}
            catch(Exception e){return new JSONObject();}
        }
        static Movie from(JSONObject o){
            if(o==null)return null;
            String id=o.optString("id").trim(),name=o.optString("name").trim();
            if(id.isEmpty()||name.isEmpty())return null;
            return new Movie(id,name,o.optString("icon"),o.optString("ext"),o.optString("cat"),(float)o.optDouble("rating",0),o.optLong("added",0));
        }
        String ratingText(){return rating>0?String.format(java.util.Locale.US,"%.1f",rating):"";}
    }
    static final class Category {
        final String id,name,shown;int count=-1;
        Category(String id,String name){this.id=id;this.name=name;this.shown=Titles.sectionName(name);}
    }
    /** What get_vod_info adds to a film. Empty strings when the provider gives nothing. */
    static final class Info {
        String plot="",cast="",director="",genre="",year="",quality="",cover="";int seconds;float rating;String ext="",cat="";
    }
    /** Where the viewer stopped in a film (milliseconds). */
    static final class Progress {
        final long pos,dur,at;
        Progress(long pos,long dur,long at){this.pos=pos;this.dur=dur;this.at=at;}
        /** Worth resuming: past the first half minute and not at the credits. */
        boolean resumable(){return pos>=30_000&&(dur<=0||pos<dur*0.95);}
    }

    static final String CONTINUE="*continue",FAVORITES="*fav",NEWEST="*new",SEARCH="*search";
    private static final long DAY=24L*3600_000;
    private static final int NEWEST_MAX=120,SEARCH_MAX=150;
    /** Adult sections never show (the viewer asked for them to stay hidden for good). */
    private static final Pattern ADULT=Pattern.compile("(?i)(xxx|adult|\\+\\s*18|18\\s*\\+|porn|للكبار|إباحي|اباحي)");
    static boolean adult(String name){return name!=null&&ADULT.matcher(name).find();}

    private final Context ctx;
    private final XtreamAccount account;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService lists=Executors.newFixedThreadPool(2);
    private final ExecutorService catalogue=Executors.newSingleThreadExecutor();
    private final Map<String,List<Movie>> movies=new HashMap<>();
    private final Set<String> busy=new HashSet<>(),failedCats=Collections.synchronizedSet(new HashSet<>());
    final List<Category> categories=new ArrayList<>();
    private final Set<String> adultIds=Collections.synchronizedSet(new HashSet<>());
    volatile boolean categoriesFailed,categoriesLoaded;
    volatile int total=-1;   // films in the whole catalogue (from the newest pass)
    private volatile List<Movie> searchResult=new ArrayList<>();
    volatile String searchFor="";

    final boolean series;
    VodData(Context c,XtreamAccount account){this(c,account,false);}
    VodData(Context c,XtreamAccount account,boolean series){this.ctx=c.getApplicationContext();this.account=account;this.series=series;}
    private String act(String films){
        if(!series)return films;
        switch(films){case "get_vod_categories":return "get_series_categories";case "get_vod_streams":return "get_series";default:return films;}
    }
    void shutdown(){lists.shutdownNow();catalogue.shutdownNow();}
    private String key(){return AccountData.key(account);}
    private File file(String name){return new File(ctx.getFilesDir(),(series?"series-":"vod-")+name+".json");}
    private static String safe(String id){return id.replaceAll("[^A-Za-z0-9_-]","");}

    // MARK: sections

    void loadCategories(Runnable changed){
        lists.execute(()->{
            boolean fresh=false;
            try{
                File f=file("categories-"+key());
                if(f.exists()){
                    JSONObject o=new JSONObject(new String(Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8));
                    readCategories(o.getJSONArray("items"));
                    fresh=System.currentTimeMillis()-o.optLong("at")<DAY;
                    categoriesLoaded=true;
                    main.post(changed);
                }
            }catch(Exception ignored){}
            if(fresh)return;
            try{
                JSONArray rows=new JSONArray(XtreamAccount.get(account.api(act("get_vod_categories"),null)));
                readCategories(rows);categoriesFailed=false;
                JSONObject o=new JSONObject().put("at",System.currentTimeMillis()).put("items",rows);
                Files.write(file("categories-"+key()).toPath(),o.toString().getBytes(StandardCharsets.UTF_8));
            }catch(Exception e){categoriesFailed=categories.isEmpty();AppLog.w("vod categories failed");}
            categoriesLoaded=true;
            main.post(changed);
        });
    }
    private void readCategories(JSONArray rows){
        List<Category> list=new ArrayList<>();
        Set<String> adults=new HashSet<>();
        for(int i=0;i<rows.length();i++){
            JSONObject r=rows.optJSONObject(i);if(r==null)continue;
            String id=r.optString("category_id");if(id.isEmpty())continue;
            String name=r.optString("category_name",id);
            if(adult(name)){adults.add(id);continue;}
            Category c=new Category(id,name);
            synchronized(movies){List<Movie> known=movies.get(id);if(known!=null)c.count=known.size();}
            list.add(c);
        }
        adultIds.clear();adultIds.addAll(adults);
        synchronized(categories){categories.clear();categories.addAll(list);}
    }
    String categoryName(String id){
        synchronized(categories){for(Category c:categories)if(c.id.equals(id))return c.shown;}
        return "";
    }

    // MARK: one section's films

    List<Movie> cached(String category){
        if(CONTINUE.equals(category))return continueList();
        if(FAVORITES.equals(category))return favorites();
        if(SEARCH.equals(category))return searchResult;
        synchronized(movies){return movies.get(category);}
    }
    boolean failed(String category){return failedCats.contains(category);}
    /** Lets go of the films of every section but these (a section left behind is read again from its file when it comes back). */
    void keepOnly(String... ids){
        Set<String> keep=new HashSet<>();for(String s:ids)if(s!=null)keep.add(s);
        synchronized(movies){movies.keySet().retainAll(keep);}
    }
    void loadMovies(String category,Runnable changed){
        if(CONTINUE.equals(category)||FAVORITES.equals(category)||SEARCH.equals(category)){main.post(changed);return;}
        if(NEWEST.equals(category)){loadNewest(changed);return;}
        synchronized(movies){if(movies.containsKey(category)){main.post(changed);return;}}
        synchronized(busy){if(!busy.add(category))return;}
        lists.execute(()->{
            boolean fresh=false;
            File f=file("cat-"+key()+"-"+safe(category));
            try{
                if(f.exists()){
                    JSONObject o=new JSONObject(new String(Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8));
                    List<Movie> list=new ArrayList<>();JSONArray a=o.getJSONArray("items");
                    for(int i=0;i<a.length();i++){Movie m=Movie.from(a.optJSONObject(i));if(m!=null)list.add(m);}
                    put(category,list);
                    fresh=System.currentTimeMillis()-o.optLong("at")<DAY;
                    main.post(changed);
                }
            }catch(Exception ignored){}
            if(!fresh){
                try{
                    JSONArray rows=new JSONArray(XtreamAccount.get(account.api(act("get_vod_streams"),"category_id="+XtreamAccount.enc(category))));
                    List<Movie> list=new ArrayList<>();
                    for(int i=0;i<rows.length();i++){Movie m=series?fromSeries(rows.optJSONObject(i),category):fromStream(rows.optJSONObject(i),category);if(m!=null)list.add(m);}
                    put(category,list);failedCats.remove(category);
                    JSONArray slim=new JSONArray();for(Movie m:list)slim.put(m.json());
                    Files.write(f.toPath(),new JSONObject().put("at",System.currentTimeMillis()).put("items",slim).toString().getBytes(StandardCharsets.UTF_8));
                }catch(Exception e){
                    synchronized(movies){if(!movies.containsKey(category))failedCats.add(category);}
                    AppLog.w("vod section failed");
                }
            }
            synchronized(busy){busy.remove(category);}
            main.post(changed);
        });
    }
    private void put(String category,List<Movie> list){
        synchronized(movies){movies.put(category,list);}
        synchronized(categories){for(Category c:categories)if(c.id.equals(category))c.count=list.size();}
    }
    private static Movie fromStream(JSONObject r,String category){
        if(r==null)return null;
        String id=r.optString("stream_id");if(id.isEmpty())return null;
        String name=r.optString("name").trim();if(name.isEmpty())return null;
        return new Movie(id,name,r.optString("stream_icon"),r.optString("container_extension"),r.optString("category_id",category),rating(r.optString("rating")),r.optLong("added",0));
    }
    private static Movie fromSeries(JSONObject r,String category){
        if(r==null)return null;
        String id=r.optString("series_id");if(id.isEmpty())return null;
        String name=r.optString("name").trim();if(name.isEmpty())return null;
        return new Movie(id,name,r.optString("cover"),"",r.optString("category_id",category),rating(r.optString("rating")),r.optLong("last_modified",0));
    }
    private static float rating(String s){
        try{float f=Float.parseFloat(s.trim());return f>0&&f<=10?f:0;}catch(Exception e){return 0;}
    }

    // MARK: the whole catalogue, read as a stream from a copy on disk (newest additions and the search)

    private File allFile(){return file("all-"+key());}
    /** The catalogue's copy on disk, downloaded again when over a day old. Runs on the catalogue thread. */
    private File catalogueFile() throws Exception {
        File f=allFile();
        if(f.isFile()&&f.length()>2&&System.currentTimeMillis()-f.lastModified()<DAY)return f;
        File part=new File(f.getPath()+".part");
        HttpURLConnection c=(HttpURLConnection)new URL(account.api(act("get_vod_streams"),null)).openConnection();
        c.setConnectTimeout(10000);c.setReadTimeout(30000);
        try{
            if(c.getResponseCode()!=200)throw new Exception("HTTP "+c.getResponseCode());
            try(InputStream in=c.getInputStream();FileOutputStream out=new FileOutputStream(part)){
                byte[] buf=new byte[16384];int n;long sum=0;
                while((n=in.read(buf))!=-1){out.write(buf,0,n);sum+=n;if(sum>120L*1024*1024)throw new Exception("too large");}
            }
        }finally{c.disconnect();}
        if(!part.renameTo(f)){part.delete();throw new Exception("rename");}
        return f;
    }
    /** Reads every film of the copy on disk, one at a time; `each` returns false to stop. */
    private interface Each{boolean take(Movie m);}
    private void scan(File f,Each each) throws Exception {
        try(JsonReader r=new JsonReader(new InputStreamReader(new FileInputStream(f),StandardCharsets.UTF_8))){
            if(r.peek()!=JsonToken.BEGIN_ARRAY)return;
            r.beginArray();
            while(r.hasNext()){
                if(r.peek()!=JsonToken.BEGIN_OBJECT){r.skipValue();continue;}
                String id="",name="",icon="",ext="",cat="",rate="";long added=0;
                r.beginObject();
                while(r.hasNext()){
                    String k=r.nextName();
                    if(r.peek()==JsonToken.NULL){r.nextNull();continue;}
                    JsonToken t=r.peek();
                    if(t==JsonToken.BEGIN_OBJECT||t==JsonToken.BEGIN_ARRAY||t==JsonToken.BOOLEAN){r.skipValue();continue;}
                    switch(k){
                        case "stream_id":if(!series)id=r.nextString();else r.skipValue();break;
                        case "series_id":if(series)id=r.nextString();else r.skipValue();break;
                        case "cover":if(series)icon=r.nextString();else r.skipValue();break;
                        case "last_modified":if(series)try{added=Long.parseLong(r.nextString().trim());}catch(Exception e){added=0;}else r.skipValue();break;
                        case "name":name=r.nextString().trim();break;
                        case "stream_icon":icon=r.nextString();break;
                        case "container_extension":ext=r.nextString();break;
                        case "category_id":cat=r.nextString();break;
                        case "rating":rate=r.nextString();break;
                        case "added":if(series){r.skipValue();break;}try{added=Long.parseLong(r.nextString().trim());}catch(Exception e){added=0;}break;
                        default:r.skipValue();
                    }
                }
                r.endObject();
                if(id.isEmpty()||name.isEmpty()||adultIds.contains(cat))continue;
                if(!each.take(new Movie(id,name,icon,ext,cat,rating(rate),added)))return;
            }
        }
    }
    /** The adult sections must be known before the whole catalogue is read (their films are left out). */
    private void waitCategories() throws InterruptedException {
        for(int i=0;i<200&&!categoriesLoaded;i++)Thread.sleep(100);
    }
    private void loadNewest(Runnable changed){
        synchronized(movies){if(movies.containsKey(NEWEST)){main.post(changed);return;}}
        synchronized(busy){if(!busy.add(NEWEST))return;}
        catalogue.execute(()->{
            try{
                waitCategories();
                File f=catalogueFile();
                final List<Movie> top=new ArrayList<>();final int[] count={0};
                scan(f,m->{
                    count[0]++;
                    if(top.size()<NEWEST_MAX||m.added>top.get(top.size()-1).added){
                        int at=top.size();while(at>0&&top.get(at-1).added<m.added)at--;
                        top.add(at,m);if(top.size()>NEWEST_MAX)top.remove(top.size()-1);
                    }
                    return true;
                });
                total=count[0];
                synchronized(movies){movies.put(NEWEST,top);}
                saveNewest(top);
                failedCats.remove(NEWEST);
            }catch(Exception e){
                synchronized(movies){if(!movies.containsKey(NEWEST))failedCats.add(NEWEST);}
                AppLog.w("vod newest failed");
            }
            synchronized(busy){busy.remove(NEWEST);}
            main.post(changed);
        });
    }
    // MARK: the home screen's row (films and series): read from small files and the saved progress only, never the network

    private static final int HOME_NEWEST=30;
    static File newestFile(Context c,XtreamAccount a,boolean series){return new File(c.getFilesDir(),(series?"series-":"vod-")+"newest-"+AccountData.key(a)+".json");}
    /** The newest of the catalogue (adult sections left out), kept small for the home row. */
    private void saveNewest(List<Movie> top){
        try{
            JSONArray a=new JSONArray();for(int i=0;i<Math.min(HOME_NEWEST,top.size());i++)a.put(top.get(i).json());
            File f=newestFile(ctx,account,series),part=new File(f.getPath()+".part");
            Files.write(part.toPath(),a.toString().getBytes(StandardCharsets.UTF_8));
            if(!part.renameTo(f))part.delete();
        }catch(Exception ignored){}
    }
    private static List<Movie> savedNewest(Context c,XtreamAccount a,boolean series){
        List<Movie> out=new ArrayList<>();
        try{
            File f=newestFile(c,a,series);
            if(!f.isFile())return out;
            JSONArray arr=new JSONArray(new String(Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8));
            for(int i=0;i<arr.length();i++){Movie m=Movie.from(arr.optJSONObject(i));if(m!=null)out.add(m);}
        }catch(Exception ignored){}
        return out;
    }
    /** True when the films' or the series' saved newest list is missing or over a day old. */
    static boolean newestStale(Context c,XtreamAccount a,boolean series){
        File f=newestFile(c,a,series);
        return !f.isFile()||System.currentTimeMillis()-f.lastModified()>DAY;
    }
    /** One poster of the home row. `progress` is how far it got (0..1), or -1 when it is not being watched. */
    static final class RowItem {
        final Movie m;final boolean series;final String tag;final float progress;
        RowItem(Movie m,boolean series,String tag,float progress){this.m=m;this.series=series;this.tag=tag;this.progress=progress;}
        boolean same(RowItem o){return o!=null&&series==o.series&&m.id.equals(o.m.id)&&tag.equals(o.tag)&&Math.abs(progress-o.progress)<0.005f&&m.icon.equals(o.m.icon)&&m.name.equals(o.m.name);}
    }
    /**
     * The home row: first what was started and not finished (films, and series whose last episode stopped part way),
     * the latest first, with how far it got; then the newest films and series, the newest first. Saved data only.
     */
    static List<RowItem> homeRow(Context c,XtreamAccount a){
        List<Object[]> started=new ArrayList<>();   // {at, RowItem}
        JSONObject films=progressAll(c,a);
        java.util.Iterator<String> it=films.keys();
        while(it.hasNext()){
            JSONObject o=films.optJSONObject(it.next());if(o==null)continue;
            Progress p=new Progress(o.optLong("pos"),o.optLong("dur"),o.optLong("at"));
            Movie m=Movie.from(o);
            if(m==null||!p.resumable())continue;
            float f=p.dur>0?Math.max(0,Math.min(1,p.pos/(float)p.dur)):0;
            started.add(new Object[]{p.at,new RowItem(m,false,"فيلم",f)});
        }
        JSONObject last=lastAll(c,a),eps=read(c,eKey(a));
        it=last.keys();
        while(it.hasNext()){
            JSONObject o=last.optJSONObject(it.next());if(o==null)continue;
            Movie m=Movie.from(o.optJSONObject("series"));if(m==null)continue;
            JSONObject e=eps.optJSONObject(o.optString("episode"));if(e==null)continue;
            Progress p=new Progress(e.optLong("pos"),e.optLong("dur"),e.optLong("at"));
            if(watched(p)||p.pos<=0)continue;   // finished: not something left to finish
            int num=o.optInt("num",0);
            float f=p.dur>0?Math.max(0,Math.min(1,p.pos/(float)p.dur)):0;
            started.add(new Object[]{o.optLong("at"),new RowItem(m,true,num>0?"مسلسل · ح"+num:"مسلسل",f)});
        }
        Collections.sort(started,(x,y)->Long.compare((Long)y[0],(Long)x[0]));
        List<RowItem> out=new ArrayList<>();
        Set<String> seen=new HashSet<>();
        for(int i=0;i<Math.min(10,started.size());i++){RowItem r=(RowItem)started.get(i)[1];if(seen.add((r.series?"s":"f")+r.m.id))out.add(r);}
        List<Movie> nf=savedNewest(c,a,false),ns=savedNewest(c,a,true);
        int i=0,j=0;
        while(out.size()<20&&(i<nf.size()||j<ns.size())){
            boolean takeFilm=j>=ns.size()||(i<nf.size()&&nf.get(i).added>=ns.get(j).added);
            Movie m=takeFilm?nf.get(i++):ns.get(j++);
            if(seen.add((takeFilm?"f":"s")+m.id))out.add(new RowItem(m,!takeFilm,takeFilm?"فيلم":"مسلسل",-1));
        }
        return out;
    }

    /** Films whose name holds the words typed, from the whole catalogue (at most 150). */
    void search(String query,Runnable changed){
        final String q=XtreamCatalog.normalize(query==null?"":query);
        searchFor=query==null?"":query.trim();
        if(q.isEmpty()){searchResult=new ArrayList<>();main.post(changed);return;}
        failedCats.remove(SEARCH);
        synchronized(busy){busy.add(SEARCH);}
        searchResult=new ArrayList<>();
        catalogue.execute(()->{
            final List<Movie> found=new ArrayList<>();
            try{
                waitCategories();
                File f=catalogueFile();
                scan(f,m->{
                    if(XtreamCatalog.normalize(m.name).contains(q))found.add(m);
                    return found.size()<SEARCH_MAX;
                });
            }catch(Exception e){failedCats.add(SEARCH);AppLog.w("vod search failed");}
            searchResult=found;
            synchronized(busy){busy.remove(SEARCH);}
            main.post(changed);
        });
    }
    boolean loading(String category){synchronized(busy){return busy.contains(category);}}

    // MARK: a film's details

    private static final Map<String,Info> INFO=new java.util.LinkedHashMap<String,Info>(24,0.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<String,Info> e){return size()>20;}
    };
    Info info(String id){synchronized(INFO){return INFO.get(id);}}
    void loadInfo(String id,Runnable done){
        if(info(id)!=null){main.post(done);return;}
        lists.execute(()->{
            Info in=new Info();
            try{
                JSONObject o=new JSONObject(XtreamAccount.get(account.api("get_vod_info","vod_id="+XtreamAccount.enc(id))));
                JSONObject i=o.optJSONObject("info");
                if(i!=null){
                    in.plot=first(i,"plot","description");
                    in.cast=first(i,"cast","actors");
                    in.director=first(i,"director");
                    in.genre=first(i,"genre");
                    in.cover=first(i,"movie_image","cover_big");
                    String rel=first(i,"releasedate","release_date","releaseDate","year");
                    java.util.regex.Matcher y=Pattern.compile("(19|20)\\d\\d").matcher(rel);
                    if(y.find())in.year=y.group();
                    in.seconds=i.optInt("duration_secs",0);
                    if(in.seconds<=0)in.seconds=clockSeconds(first(i,"duration"));
                    if(in.seconds<=0){int min=i.optInt("episode_run_time",0);if(min>0)in.seconds=min*60;}
                    in.rating=rating(first(i,"rating"));
                    JSONObject v=i.optJSONObject("video");
                    int h=v==null?0:v.optInt("height",0);
                    in.quality=h>=2000?"4K":h>=1000?"FHD":h>=700?"HD":h>0?"SD":"";
                }
                JSONObject d=o.optJSONObject("movie_data");
                if(d!=null){in.ext=d.optString("container_extension");in.cat=d.optString("category_id");}
            }catch(Exception e){AppLog.w("vod info failed");}
            synchronized(INFO){INFO.put(id,in);}
            main.post(done);
        });
    }
    private static String first(JSONObject o,String... names){
        for(String n:names){Object v=o.opt(n);if(v instanceof String&&!((String)v).trim().isEmpty())return ((String)v).trim();if(v instanceof Number)return String.valueOf(v);}
        return "";
    }
    /** "01:42:10" or "102:10" or "102" (minutes) to seconds. */
    private static int clockSeconds(String s){
        if(s==null||s.isEmpty())return 0;
        try{
            String[] p=s.trim().split(":");
            if(p.length==3)return Integer.parseInt(p[0])*3600+Integer.parseInt(p[1])*60+Integer.parseInt(p[2]);
            if(p.length==2)return Integer.parseInt(p[0])*60+Integer.parseInt(p[1]);
            return Integer.parseInt(p[0])*60;
        }catch(Exception e){return 0;}
    }

    // MARK: where the viewer stopped, and the favourites (per subscription)

    private static SharedPreferences store(Context c){return c.getApplicationContext().getSharedPreferences("ouswis-vod",Context.MODE_PRIVATE);}
    private static String pKey(XtreamAccount a){return "progress-"+AccountData.key(a);}
    private String fKey(XtreamAccount a){return (series?"seriesFavorites-":"favorites-")+AccountData.key(a);}
    private static JSONObject progressAll(Context c,XtreamAccount a){
        try{return new JSONObject(store(c).getString(pKey(a),"{}"));}catch(Exception e){return new JSONObject();}
    }
    static Progress progress(Context c,XtreamAccount a,String id){
        JSONObject o=progressAll(c,a).optJSONObject(id);
        return o==null?null:new Progress(o.optLong("pos"),o.optLong("dur"),o.optLong("at"));
    }
    /** Saves where the film stopped; at the credits it leaves "continue watching". */
    static synchronized void saveProgress(Context c,XtreamAccount a,Movie m,long pos,long dur){
        JSONObject all=progressAll(c,a);
        try{
            if(dur>0&&pos>=dur*0.95)all.remove(m.id);
            else if(pos>=5_000)all.put(m.id,m.json().put("pos",pos).put("dur",dur).put("at",System.currentTimeMillis()));
            if(all.length()>200){   // the oldest go
                List<String> ids=new ArrayList<>();java.util.Iterator<String> it=all.keys();while(it.hasNext())ids.add(it.next());
                Collections.sort(ids,(x,y)->Long.compare(all.optJSONObject(x).optLong("at"),all.optJSONObject(y).optLong("at")));
                for(int i=0;i<ids.size()-200;i++)all.remove(ids.get(i));
            }
            store(c).edit().putString(pKey(a),all.toString()).apply();
        }catch(Exception ignored){}
    }
    /** Films started and not finished, the latest first (series: the series last watched, the latest first). */
    List<Movie> continueList(){
        if(series){
            JSONObject all=lastAll(ctx,account);
            List<JSONObject> rows=new ArrayList<>();
            java.util.Iterator<String> it=all.keys();
            while(it.hasNext()){JSONObject o=all.optJSONObject(it.next());if(o!=null)rows.add(o);}
            Collections.sort(rows,(x,y)->Long.compare(y.optLong("at"),x.optLong("at")));
            List<Movie> out=new ArrayList<>();
            for(JSONObject o:rows){Movie m=Movie.from(o.optJSONObject("series"));if(m!=null)out.add(m);}
            return out;
        }
        JSONObject all=progressAll(ctx,account);
        List<JSONObject> rows=new ArrayList<>();
        java.util.Iterator<String> it=all.keys();
        while(it.hasNext()){JSONObject o=all.optJSONObject(it.next());if(o!=null&&new Progress(o.optLong("pos"),o.optLong("dur"),0).resumable())rows.add(o);}
        Collections.sort(rows,(x,y)->Long.compare(y.optLong("at"),x.optLong("at")));
        List<Movie> out=new ArrayList<>();
        for(JSONObject o:rows){Movie m=Movie.from(o);if(m!=null)out.add(m);}
        return out;
    }
    List<Movie> favorites(){
        List<Movie> out=new ArrayList<>();
        try{JSONArray a=new JSONArray(store(ctx).getString(fKey(account),"[]"));for(int i=0;i<a.length();i++){Movie m=Movie.from(a.optJSONObject(i));if(m!=null)out.add(m);}}
        catch(Exception ignored){}
        return out;
    }
    boolean isFavorite(String id){for(Movie m:favorites())if(m.id.equals(id))return true;return false;}
    /** Adds or removes; true when it is now a favourite. */
    boolean toggleFavorite(Movie m){
        List<Movie> list=favorites();
        boolean removed=false;
        for(int i=0;i<list.size();i++)if(list.get(i).id.equals(m.id)){list.remove(i);removed=true;break;}
        if(!removed)list.add(0,m);
        JSONArray a=new JSONArray();for(int i=0;i<Math.min(300,list.size());i++)a.put(list.get(i).json());
        store(ctx).edit().putString(fKey(account),a.toString()).apply();
        return !removed;
    }

    // MARK: series: seasons and episodes (get_series_info), where each episode stopped, and the last one watched

    static final class Episode {
        final String id,title,ext,plot,image;final int season,num,seconds;
        Episode(String id,int season,int num,String title,String ext,String plot,String image,int seconds){
            this.id=id;this.season=season;this.num=num;this.title=title;this.ext=ext==null||ext.isEmpty()?"mp4":ext;this.plot=plot;this.image=image;this.seconds=seconds;
        }
    }
    static final class SeriesInfo {
        final Info info=new Info();
        final List<Integer> seasons=new ArrayList<>();
        final Map<Integer,List<Episode>> episodes=new HashMap<>();
        final Map<Integer,String> seasonCovers=new HashMap<>();
        boolean failed;
        List<Episode> of(int season){List<Episode> l=episodes.get(season);return l==null?new ArrayList<>():l;}
        /** The episode after this one (the next season's first after a season's last), or null. */
        Episode after(Episode e){
            List<Episode> l=of(e.season);
            for(int i=0;i<l.size();i++)if(l.get(i).id.equals(e.id)){
                if(i+1<l.size())return l.get(i+1);
                int at=seasons.indexOf(e.season);
                for(int s=at+1;s>0&&s<seasons.size();s++){List<Episode> n=of(seasons.get(s));if(!n.isEmpty())return n.get(0);}
                return null;
            }
            return null;
        }
        Episode find(String id){for(List<Episode> l:episodes.values())for(Episode e:l)if(e.id.equals(id))return e;return null;}
        Episode first(){for(int s:seasons){List<Episode> l=of(s);if(!l.isEmpty())return l.get(0);}return null;}
    }
    private static final Map<String,SeriesInfo> SERIES=new java.util.LinkedHashMap<String,SeriesInfo>(8,0.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<String,SeriesInfo> e){return size()>4;}
    };
    SeriesInfo seriesInfo(String id){synchronized(SERIES){return SERIES.get(id);}}
    void loadSeries(String id,Runnable done){
        if(seriesInfo(id)!=null){main.post(done);return;}
        lists.execute(()->{
            SeriesInfo out=new SeriesInfo();
            try{
                JSONObject o=new JSONObject(XtreamAccount.get(account.api("get_series_info","series_id="+XtreamAccount.enc(id))));
                JSONObject i=o.optJSONObject("info");
                if(i!=null){
                    out.info.plot=first(i,"plot","description");out.info.cast=first(i,"cast","actors");out.info.director=first(i,"director");
                    out.info.genre=first(i,"genre");out.info.cover=first(i,"cover");out.info.rating=rating(first(i,"rating"));
                    java.util.regex.Matcher y=Pattern.compile("(19|20)\\d\\d").matcher(first(i,"releaseDate","release_date","releasedate","year"));
                    if(y.find())out.info.year=y.group();
                    out.info.cat=first(i,"category_id");
                }
                JSONArray seasons=o.optJSONArray("seasons");
                if(seasons!=null)for(int k=0;k<seasons.length();k++){
                    JSONObject s=seasons.optJSONObject(k);if(s==null)continue;
                    int n=s.optInt("season_number",-1);if(n<0)continue;
                    String cover=first(s,"cover_big","cover");if(!cover.isEmpty())out.seasonCovers.put(n,cover);
                }
                Object eps=o.opt("episodes");
                List<JSONArray> groups=new ArrayList<>();
                if(eps instanceof JSONObject){java.util.Iterator<String> it=((JSONObject)eps).keys();while(it.hasNext()){JSONArray a=((JSONObject)eps).optJSONArray(it.next());if(a!=null)groups.add(a);}}
                else if(eps instanceof JSONArray){JSONArray a=(JSONArray)eps;for(int k=0;k<a.length();k++){Object g=a.opt(k);if(g instanceof JSONArray)groups.add((JSONArray)g);}}
                for(JSONArray g:groups)for(int k=0;k<g.length();k++){
                    JSONObject e=g.optJSONObject(k);if(e==null)continue;
                    String eid=e.optString("id");if(eid.isEmpty())continue;
                    JSONObject ei=e.optJSONObject("info");
                    int season=e.optInt("season",ei==null?1:ei.optInt("season",1)),num=e.optInt("episode_num",k+1);
                    int secs=ei==null?0:ei.optInt("duration_secs",0);
                    if(secs<=0&&ei!=null)secs=clockSeconds(first(ei,"duration"));
                    Episode ep=new Episode(eid,season,num,e.optString("title"),e.optString("container_extension"),ei==null?"":first(ei,"plot","description"),ei==null?"":first(ei,"movie_image","cover_big"),secs);
                    List<Episode> l=out.episodes.get(season);if(l==null){l=new ArrayList<>();out.episodes.put(season,l);}
                    l.add(ep);
                }
                for(List<Episode> l:out.episodes.values())Collections.sort(l,(a,b)->Integer.compare(a.num,b.num));
                out.seasons.addAll(out.episodes.keySet());
                Collections.sort(out.seasons);
                if(out.seasons.size()>1&&out.seasons.get(0)==0){out.seasons.remove(0);out.seasons.add(0);}   // "season 0" (extras) last
            }catch(Exception e){out.failed=true;AppLog.w("series info failed");}
            synchronized(SERIES){if(!out.failed)SERIES.put(id,out);}
            final SeriesInfo got=out;
            main.post(()->{if(got.failed){synchronized(SERIES){SERIES.remove(id);}failedInfo.add(id);}else failedInfo.remove(id);done.run();});
        });
    }
    private final Set<String> failedInfo=Collections.synchronizedSet(new HashSet<>());
    boolean seriesFailed(String id){return failedInfo.contains(id);}

    private static String eKey(XtreamAccount a){return "episodes-"+AccountData.key(a);}
    private static String lKey(XtreamAccount a){return "seriesLast-"+AccountData.key(a);}
    private static JSONObject read(Context c,String key){try{return new JSONObject(store(c).getString(key,"{}"));}catch(Exception e){return new JSONObject();}}
    private static JSONObject lastAll(Context c,XtreamAccount a){return read(c,lKey(a));}
    /** Where this episode stopped (done: watched to the credits). Null when it was never started. */
    static Progress episode(Context c,XtreamAccount a,String id){
        JSONObject o=read(c,eKey(a)).optJSONObject(id);
        return o==null?null:new Progress(o.optLong("pos"),o.optLong("dur"),o.optLong("at"));
    }
    /** Where every episode stopped, read once (a page draws from this, not from the store). */
    static Map<String,Progress> episodes(Context c,XtreamAccount a){
        Map<String,Progress> out=new HashMap<>();
        JSONObject all=read(c,eKey(a));java.util.Iterator<String> it=all.keys();
        while(it.hasNext()){String id=it.next();JSONObject o=all.optJSONObject(id);if(o!=null)out.put(id,new Progress(o.optLong("pos"),o.optLong("dur"),o.optLong("at")));}
        return out;
    }
    static boolean watched(Progress p){return p!=null&&p.dur>0&&p.pos>=p.dur*0.95;}
    /** The id of the last episode watched in this series, or "". */
    static String lastEpisode(Context c,XtreamAccount a,String seriesId){
        JSONObject o=lastAll(c,a).optJSONObject(seriesId);return o==null?"":o.optString("episode");
    }
    /** Saves where an episode stopped (at the credits it counts as watched) and makes it the series' last one. */
    static synchronized void saveEpisode(Context c,XtreamAccount a,Movie seriesItem,String episodeId,int season,int num,long pos,long dur){
        try{
            JSONObject all=read(c,eKey(a));
            if(pos>=5_000||(dur>0&&pos>=dur*0.95)){
                if(dur>0&&pos>=dur*0.95)pos=dur;
                all.put(episodeId,new JSONObject().put("pos",pos).put("dur",dur).put("at",System.currentTimeMillis()));
                trim(all,1500);
                JSONObject last=lastAll(c,a);
                last.put(seriesItem.id,new JSONObject().put("series",seriesItem.json()).put("episode",episodeId).put("season",season).put("num",num).put("at",System.currentTimeMillis()));
                trim(last,200);
                store(c).edit().putString(eKey(a),all.toString()).putString(lKey(a),last.toString()).apply();
            }
        }catch(Exception ignored){}
    }
    private static void trim(JSONObject all,int max){
        if(all.length()<=max)return;
        List<String> ids=new ArrayList<>();java.util.Iterator<String> it=all.keys();while(it.hasNext())ids.add(it.next());
        Collections.sort(ids,(x,y)->Long.compare(all.optJSONObject(x)==null?0:all.optJSONObject(x).optLong("at"),all.optJSONObject(y)==null?0:all.optJSONObject(y).optLong("at")));
        for(int i=0;i<ids.size()-max;i++)all.remove(ids.get(i));
    }
    /** "4 مواسم" in Arabic. */
    static String seasons(int n){return n==1?"موسم واحد":n==2?"موسمان":n<=10?n+" مواسم":n+" موسماً";}

    // MARK: words

    /** "2 س 08 د", "45 د". */
    static String duration(long seconds){
        if(seconds<=0)return "";
        long h=seconds/3600,m=(seconds%3600)/60;
        return h>0?h+" س "+String.format(java.util.Locale.US,"%02d",m)+" د":Math.max(1,m)+" د";
    }
    /** "47:12" or "1:47:12". */
    static String clock(long ms){
        long s=Math.max(0,ms/1000),h=s/3600,m=(s%3600)/60,sec=s%60;
        return h>0?String.format(java.util.Locale.US,"%d:%02d:%02d",h,m,sec):String.format(java.util.Locale.US,"%d:%02d",m,sec);
    }
}

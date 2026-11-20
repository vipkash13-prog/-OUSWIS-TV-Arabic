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
        /** name: as the provider gives it (kept, and matched on); shown: without its prefixes and year; year: the one in the name, or "". */
        final String id,name,icon,ext,cat,shown,year;final float rating;final long added;
        Movie(String id,String name,String icon,String ext,String cat,float rating,long added){
            this.id=id;this.name=name;this.icon=icon==null?"":icon;this.ext=ext==null||ext.isEmpty()?"mp4":ext;this.cat=cat==null?"":cat;this.rating=rating;this.added=added;
            this.shown=clean(name);this.year=yearOf(name);
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

    // MARK: names as shown

    private static final Pattern TAG_BAR=Pattern.compile("^\\|?\\s*[A-Za-z0-9]{1,5}\\s*\\|\\s*"),          // "EN| ", "AR |", "|4K|"
            TAG_DASHES=Pattern.compile("^-\\s*[A-Za-z0-9 ]{1,8}?\\s*-\\s*"),                                  // "-TOP- "
            TAG_LETTERS=Pattern.compile("^(?=[A-Z0-9-]*[A-Z])[A-Z0-9]{1,4}(?:-[A-Z0-9]{1,4})?\\s*-\\s+"),        // "R - ", "PT - ", "AS-S - " (a letter in it: "24 - Legacy" stays)
            TAG_BRACKET=Pattern.compile("^\\[[^\\]]{1,10}\\]\\s*"),                                          // "[AR] "
            YEAR_END=Pattern.compile("\\s*(?:[\\(\\[]\\s*((?:19|20)\\d\\d)\\s*[\\)\\]]|[-–|]\\s*((?:19|20)\\d\\d)|\\s((?:19|20)\\d\\d))\\s*$");
    /** The name without the provider's prefixes (R - , -TOP- , AS-S - , PT - , EN| , AR| ) and without a year at its end. */
    static String clean(String raw){
        if(raw==null)return "";
        String s=raw.trim(),before;
        do{
            before=s;
            s=TAG_BAR.matcher(s).replaceFirst("");
            s=TAG_DASHES.matcher(s).replaceFirst("");
            s=TAG_LETTERS.matcher(s).replaceFirst("");
            s=TAG_BRACKET.matcher(s).replaceFirst("");
            s=s.trim();
        }while(!s.equals(before)&&!s.isEmpty());
        java.util.regex.Matcher y=YEAR_END.matcher(s);
        if(y.find()&&plausibleYear(y)){String t=s.substring(0,y.start()).trim();if(!t.isEmpty())s=t;}
        return s.isEmpty()?raw.trim():s;
    }
    /** The year at the end of the name ("Film (2023)", "Film - 2023", "Film 2023"), or "". */
    static String yearOf(String raw){
        if(raw==null)return "";
        java.util.regex.Matcher y=YEAR_END.matcher(raw.trim());
        if(!y.find()||!plausibleYear(y))return "";
        return y.group(1)!=null?y.group(1):y.group(2)!=null?y.group(2):y.group(3);
    }
    /** A year can't be in the future ("Blade Runner 2049" keeps its number). */
    private static boolean plausibleYear(java.util.regex.Matcher y){
        String g=y.group(1)!=null?y.group(1):y.group(2)!=null?y.group(2):y.group(3);
        int v=Integer.parseInt(g);
        return v>=1900&&v<=java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)+1;
    }
    /** For matching one subscription's titles with another's: clean, lower case, letters and digits only. */
    static String canon(String raw){return clean(raw).toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+"," ").trim();}

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
                    if(!list.isEmpty()){   // an empty copy is asked again: the section may have been empty only for a moment
                        put(category,list);
                        fresh=System.currentTimeMillis()-o.optLong("at")<DAY;
                        main.post(changed);
                    }
                }
            }catch(Exception ignored){}
            if(!fresh)fetchSection(category,f);
            synchronized(busy){busy.remove(category);}
            main.post(changed);
        });
    }
    /**
     * One section from the subscription: get_vod_streams&category_id= first; when that answers nothing (some panels give an
     * empty list for sections whose films have it as a second section, "category_ids") or fails, the whole catalogue
     * (get_vod_streams without category_id, the copy the newest and the search read) filtered here by category_id and
     * category_ids, as other players do. Empty only when both say so; failed only when both failed. Each step is logged:
     * the section's id, how many came, the HTTP code and the time (never the address: it holds the password).
     */
    private void fetchSection(String category,File f){
        String kind=series?"series":"vod";
        long t0=System.currentTimeMillis();
        int[] code={0};
        List<Movie> list=null;
        try{
            String body=XtreamAccount.get(account.api(act("get_vod_streams"),"category_id="+XtreamAccount.enc(category)),code).trim();
            list=new ArrayList<>();
            if(body.startsWith("[")){
                JSONArray rows=new JSONArray(body);
                for(int i=0;i<rows.length();i++){Movie m=series?fromSeries(rows.optJSONObject(i),category):fromStream(rows.optJSONObject(i),category);if(m!=null)list.add(m);}
                AppLog.w(kind+" section "+category+": "+list.size()+" of "+rows.length()+" rows, HTTP "+code[0]+", "+(System.currentTimeMillis()-t0)+" ms");
            }else{   // "{}", "null" or nothing: no list for this section
                AppLog.w(kind+" section "+category+": no list ("+(body.isEmpty()?"empty body":body.length()>12?body.substring(0,12)+"…":body)+"), HTTP "+code[0]+", "+(System.currentTimeMillis()-t0)+" ms");
            }
        }catch(Exception e){
            list=null;
            AppLog.w(kind+" section "+category+": failed ("+(code[0]!=0?"HTTP "+code[0]:e.getClass().getSimpleName())+"), "+(System.currentTimeMillis()-t0)+" ms");
        }
        if(list==null||list.isEmpty()){
            long t1=System.currentTimeMillis();
            try{
                waitCategories();
                File all=catalogueFile();
                final List<Movie> found=new ArrayList<>();
                final int[] byIds={0};
                scan(all,(m,ids)->{
                    if(category.equals(m.cat)){found.add(m);return true;}
                    if(ids!=null&&ids.contains(category)){found.add(m);byIds[0]++;}
                    return true;
                });
                AppLog.w(kind+" section "+category+": "+found.size()+" from the whole catalogue ("+byIds[0]+" by category_ids), "+(System.currentTimeMillis()-t1)+" ms");
                list=found;
            }catch(Exception e){
                AppLog.w(kind+" section "+category+": the whole catalogue failed too ("+e.getClass().getSimpleName()+"), "+(System.currentTimeMillis()-t1)+" ms");
                if(list==null){
                    synchronized(movies){if(!movies.containsKey(category))failedCats.add(category);}
                    return;
                }
            }
        }
        put(category,list);failedCats.remove(category);
        try{
            JSONArray slim=new JSONArray();for(Movie m:list)slim.put(m.json());
            Files.write(f.toPath(),new JSONObject().put("at",System.currentTimeMillis()).put("items",slim).toString().getBytes(StandardCharsets.UTF_8));
        }catch(Exception ignored){}
    }
    /** Forgets a section that failed, so OK on it asks again. */
    void retry(String category){
        failedCats.remove(category);
        synchronized(movies){movies.remove(category);}
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
    private final Object allLock=new Object();
    private File catalogueFile() throws Exception {synchronized(allLock){return catalogueFileLocked();}}
    private File catalogueFileLocked() throws Exception {
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
    private interface Each{boolean take(Movie m,List<String> categoryIds);}
    private void scan(File f,Each each) throws Exception {
        try(JsonReader r=new JsonReader(new InputStreamReader(new FileInputStream(f),StandardCharsets.UTF_8))){
            if(r.peek()!=JsonToken.BEGIN_ARRAY)return;
            r.beginArray();
            while(r.hasNext()){
                if(r.peek()!=JsonToken.BEGIN_OBJECT){r.skipValue();continue;}
                String id="",name="",icon="",ext="",cat="",rate="";long added=0;
                List<String> ids=null;
                r.beginObject();
                while(r.hasNext()){
                    String k=r.nextName();
                    if(r.peek()==JsonToken.NULL){r.nextNull();continue;}
                    JsonToken t=r.peek();
                    if(t==JsonToken.BEGIN_ARRAY&&"category_ids".equals(k)){   // every section the film is in (XUI panels)
                        ids=new ArrayList<>();r.beginArray();
                        while(r.hasNext()){JsonToken e=r.peek();if(e==JsonToken.STRING||e==JsonToken.NUMBER)ids.add(r.nextString().trim());else r.skipValue();}
                        r.endArray();continue;
                    }
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
                if(!each.take(new Movie(id,name,icon,ext,cat,rating(rate),added),ids))return;
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
                scan(f,(m,ids)->{
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
                scan(f,(m,ids)->{
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
                JSONObject rec=new JSONObject().put("pos",pos).put("dur",dur).put("at",System.currentTimeMillis());
                if(num>0)rec.put("s",seriesItem.name).put("se",season).put("n",num);   // what it is, by name: so it can follow to another subscription
                all.put(episodeId,rec);
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
    /** The series page knows its episodes: records saved before they carried their series, season and number get them now. */
    static synchronized void tagEpisodes(Context c,XtreamAccount a,Movie show,SeriesInfo in){
        if(in==null)return;
        try{
            JSONObject all=read(c,eKey(a));boolean changed=false;
            for(List<Episode> l:in.episodes.values())for(Episode e:l){
                JSONObject o=all.optJSONObject(e.id);
                if(o!=null&&!o.has("s")){o.put("s",show.name).put("se",e.season).put("n",e.num);changed=true;}
            }
            JSONObject last=lastAll(c,a),lo=last.optJSONObject(show.id);
            if(lo!=null&&lo.optInt("num",0)<=0){Episode e=in.find(lo.optString("episode"));if(e!=null){lo.put("season",e.season).put("num",e.num);changed=true;}}
            if(changed)store(c).edit().putString(eKey(a),all.toString()).putString(lKey(a),last.toString()).apply();
        }catch(Exception ignored){}
    }

    // MARK: another subscription: the history and the favourites follow by name (never an id of the old one)

    private static String stashKey(String forKey){return "vodStash-"+forKey;}
    /**
     * Before the old subscription's data goes: the names of what was watched (with where it stopped), the favourites and
     * the episodes (series name, season, number), plus what an earlier change had not carried over yet. No ids.
     */
    static JSONObject stash(Context c,String fromKey){
        JSONObject out;
        try{out=new JSONObject(store(c).getString(stashKey(fromKey),"{}"));}catch(Exception e){out=new JSONObject();}
        try{
            JSONArray films=arr(out,"films"),filmFav=arr(out,"filmFav"),seriesFav=arr(out,"seriesFav"),series=arr(out,"series"),eps=arr(out,"episodes");
            JSONObject prog=read(c,"progress-"+fromKey);
            java.util.Iterator<String> it=prog.keys();
            while(it.hasNext()){JSONObject o=prog.optJSONObject(it.next());if(o!=null&&!o.optString("name").isEmpty())films.put(new JSONObject().put("n",o.optString("name")).put("pos",o.optLong("pos")).put("dur",o.optLong("dur")).put("at",o.optLong("at")));}
            names(c,"favorites-"+fromKey,filmFav);
            names(c,"seriesFavorites-"+fromKey,seriesFav);
            JSONObject last=read(c,"seriesLast-"+fromKey);
            it=last.keys();
            while(it.hasNext()){
                JSONObject o=last.optJSONObject(it.next());if(o==null)continue;
                JSONObject sr=o.optJSONObject("series");
                if(sr!=null&&o.optInt("num",0)>0)series.put(new JSONObject().put("n",sr.optString("name")).put("se",o.optInt("season")).put("num",o.optInt("num")).put("at",o.optLong("at")));
            }
            JSONObject ep=read(c,"episodes-"+fromKey);
            it=ep.keys();
            while(it.hasNext()){
                JSONObject o=ep.optJSONObject(it.next());
                if(o!=null&&o.has("s")&&o.optInt("n",0)>0)eps.put(new JSONObject().put("s",o.optString("s")).put("se",o.optInt("se")).put("n",o.optInt("n")).put("pos",o.optLong("pos")).put("dur",o.optLong("dur")).put("at",o.optLong("at")));
            }
            out.put("films",films).put("filmFav",filmFav).put("seriesFav",seriesFav).put("series",series).put("episodes",eps);
        }catch(Exception ignored){}
        return out;
    }
    private static JSONArray arr(JSONObject o,String k){JSONArray a=o.optJSONArray(k);return a==null?new JSONArray():a;}
    private static void names(Context c,String key,JSONArray into){
        try{JSONArray a=new JSONArray(store(c).getString(key,"[]"));for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o!=null&&!o.optString("name").isEmpty())into.put(o.optString("name"));}}catch(Exception ignored){}
    }
    static int stashSize(JSONObject st){
        if(st==null)return 0;
        int n=0;for(String k:new String[]{"films","filmFav","seriesFav","series","episodes"})n+=arr(st,k).length();
        return n;
    }
    static void putStash(Context c,String forKey,JSONObject st){
        if(stashSize(st)==0)store(c).edit().remove(stashKey(forKey)).commit();
        else store(c).edit().putString(stashKey(forKey),st.toString()).commit();
    }
    static boolean hasStash(Context c,String forKey){return store(c).contains(stashKey(forKey));}

    /**
     * Matches the stashed names with the new subscription's films and series (read as a stream) and the episodes by season
     * and number (get_series_info of each matched series); what has no match is dropped. `lock` and `stamp` keep it from
     * writing for a subscription that is no longer the current one. Throws when the subscription can't be read (tried again later).
     */
    static String carryOver(Context c,XtreamAccount a,Object lock) throws Exception {
        final String key=AccountData.key(a);
        JSONObject st;
        try{st=new JSONObject(store(c).getString(stashKey(key),"{}"));}catch(Exception e){st=new JSONObject();}
        if(stashSize(st)==0){store(c).edit().remove(stashKey(key)).commit();return "";}
        int tries=st.optInt("tries",0)+1;
        st.put("tries",tries);store(c).edit().putString(stashKey(key),st.toString()).commit();
        JSONArray films=arr(st,"films"),filmFav=arr(st,"filmFav"),seriesFav=arr(st,"seriesFav"),series=arr(st,"series"),eps=arr(st,"episodes");
        Set<String> wantFilms=new HashSet<>(),wantSeries=new HashSet<>();
        for(int i=0;i<films.length();i++)wantFilms.add(canon(films.getJSONObject(i).optString("n")));
        for(int i=0;i<filmFav.length();i++)wantFilms.add(canon(filmFav.optString(i)));
        for(int i=0;i<seriesFav.length();i++)wantSeries.add(canon(seriesFav.optString(i)));
        for(int i=0;i<series.length();i++)wantSeries.add(canon(series.getJSONObject(i).optString("n")));
        for(int i=0;i<eps.length();i++)wantSeries.add(canon(eps.getJSONObject(i).optString("s")));
        Map<String,List<Movie>> fFound=wantFilms.isEmpty()?new HashMap<>():streamFind(a,false,wantFilms);
        Map<String,List<Movie>> sFound=wantSeries.isEmpty()?new HashMap<>():streamFind(a,true,wantSeries);
        int films0=0,favs0=0,series0=0,eps0=0,lost=0;
        // films: where each stopped, and the favourites
        JSONObject prog=new JSONObject();
        for(int i=0;i<films.length();i++){
            JSONObject o=films.getJSONObject(i);Movie m=best(o.optString("n"),fFound);
            if(m==null){lost++;continue;}
            JSONObject had=prog.optJSONObject(m.id);
            if(had==null||had.optLong("at")<o.optLong("at")){prog.put(m.id,m.json().put("pos",o.optLong("pos")).put("dur",o.optLong("dur")).put("at",o.optLong("at")));films0++;}
        }
        JSONArray favF=new JSONArray(),favS=new JSONArray();Set<String> seen=new HashSet<>();
        for(int i=0;i<filmFav.length();i++){Movie m=best(filmFav.optString(i),fFound);if(m==null){lost++;continue;}if(seen.add("f"+m.id)){favF.put(m.json());favs0++;}}
        for(int i=0;i<seriesFav.length();i++){Movie m=best(seriesFav.optString(i),sFound);if(m==null){lost++;continue;}if(seen.add("s"+m.id)){favS.put(m.json());favs0++;}}
        // series: their episodes by season and number, then the last one watched
        JSONObject epOut=new JSONObject(),lastOut=new JSONObject();
        Map<String,Map<String,String>> epIds=new HashMap<>();   // new series id -> "season:number" -> new episode id
        int infoCalls=0;
        for(int i=0;i<eps.length();i++){
            JSONObject o=eps.getJSONObject(i);Movie sr=best(o.optString("s"),sFound);
            if(sr==null){lost++;continue;}
            Map<String,String> ids=epIds.get(sr.id);
            if(ids==null&&infoCalls<60){ids=episodeIds(a,sr.id);infoCalls++;epIds.put(sr.id,ids);}
            String eid=ids==null?null:ids.get(o.optInt("se")+":"+o.optInt("n"));
            if(eid==null){lost++;continue;}
            epOut.put(eid,new JSONObject().put("pos",o.optLong("pos")).put("dur",o.optLong("dur")).put("at",o.optLong("at")).put("s",sr.name).put("se",o.optInt("se")).put("n",o.optInt("n")));eps0++;
        }
        for(int i=0;i<series.length();i++){
            JSONObject o=series.getJSONObject(i);Movie sr=best(o.optString("n"),sFound);
            if(sr==null){lost++;continue;}
            Map<String,String> ids=epIds.get(sr.id);
            if(ids==null&&infoCalls<60){ids=episodeIds(a,sr.id);infoCalls++;epIds.put(sr.id,ids);}
            String eid=ids==null?null:ids.get(o.optInt("se")+":"+o.optInt("num"));
            if(eid==null){lost++;continue;}
            JSONObject had=lastOut.optJSONObject(sr.id);
            if(had==null||had.optLong("at")<o.optLong("at")){lastOut.put(sr.id,new JSONObject().put("series",sr.json()).put("episode",eid).put("season",o.optInt("se")).put("num",o.optInt("num")).put("at",o.optLong("at")));series0++;}
        }
        synchronized(lock){
            if(!key.equals(AccountData.stamp(c)))return "";   // the subscription changed again: the next change carries it
            SharedPreferences sp=store(c);
            JSONObject progNow=read(c,"progress-"+key),epNow=read(c,"episodes-"+key),lastNow=read(c,"seriesLast-"+key);
            // what was watched since the change stays as it is
            for(java.util.Iterator<String> it=prog.keys();it.hasNext();){String id=it.next();if(!progNow.has(id))progNow.put(id,prog.get(id));}
            for(java.util.Iterator<String> it=epOut.keys();it.hasNext();){String id=it.next();if(!epNow.has(id))epNow.put(id,epOut.get(id));}
            for(java.util.Iterator<String> it=lastOut.keys();it.hasNext();){String id=it.next();if(!lastNow.has(id))lastNow.put(id,lastOut.get(id));}
            sp.edit().putString("progress-"+key,progNow.toString()).putString("episodes-"+key,epNow.toString()).putString("seriesLast-"+key,lastNow.toString())
              .putString("favorites-"+key,joinedById(sp.getString("favorites-"+key,"[]"),favF,300))
              .putString("seriesFavorites-"+key,joinedById(sp.getString("seriesFavorites-"+key,"[]"),favS,300))
              .remove(stashKey(key)).commit();
        }
        return films0+" فيلم، "+series0+" مسلسل، "+eps0+" حلقة، "+favs0+" مفضلة؛ بلا مقابل: "+lost;
    }
    /** Gives up after three failed tries (each reads the whole catalogue): the stash goes. */
    static void giveUpIfTried(Context c,String forKey){
        try{JSONObject st=new JSONObject(store(c).getString(stashKey(forKey),"{}"));if(st.optInt("tries",0)>=3)store(c).edit().remove(stashKey(forKey)).commit();}catch(Exception ignored){}
    }
    private static String joinedById(String now,JSONArray carried,int cap){
        JSONArray out=new JSONArray();Set<String> ids=new HashSet<>();
        try{JSONArray cur=new JSONArray(now);for(int i=0;i<cur.length()&&out.length()<cap;i++){JSONObject o=cur.optJSONObject(i);if(o!=null&&ids.add(o.optString("id")))out.put(o);}}catch(Exception ignored){}
        for(int i=0;i<carried.length()&&out.length()<cap;i++){JSONObject o=carried.optJSONObject(i);if(o!=null&&ids.add(o.optString("id")))out.put(o);}
        return out.toString();
    }
    /** The new subscription's title for this name: the same clean name; when there are several, the same year, then the same full name. */
    private static Movie best(String name,Map<String,List<Movie>> found){
        List<Movie> l=found.get(canon(name));
        if(l==null||l.isEmpty())return null;
        String y=yearOf(name);Movie pick=null;int score=Integer.MAX_VALUE;
        for(Movie m:l){int sc=(y.isEmpty()||m.year.isEmpty()||y.equals(m.year)?0:2)+(m.name.trim().equalsIgnoreCase(name.trim())?0:1);if(sc<score){score=sc;pick=m;}}
        return pick;
    }
    /** Reads the new subscription's whole film (or series) list as a stream and keeps only the titles wanted. */
    private static Map<String,List<Movie>> streamFind(XtreamAccount a,boolean series,Set<String> wanted) throws Exception {
        Map<String,List<Movie>> out=new HashMap<>();
        HttpURLConnection c=(HttpURLConnection)new URL(a.api(series?"get_series":"get_vod_streams",null)).openConnection();
        c.setConnectTimeout(15000);c.setReadTimeout(60000);c.setInstanceFollowRedirects(true);
        try{
            if(c.getResponseCode()!=200)throw new Exception("HTTP "+c.getResponseCode());
            try(JsonReader r=new JsonReader(new InputStreamReader(c.getInputStream(),StandardCharsets.UTF_8))){
                if(r.peek()!=JsonToken.BEGIN_ARRAY)return out;   // nothing of that kind in this subscription
                r.beginArray();
                while(r.hasNext()){
                    if(r.peek()!=JsonToken.BEGIN_OBJECT){r.skipValue();continue;}
                    String id="",name="",icon="",ext="",cat="",rate="";long added=0;
                    r.beginObject();
                    while(r.hasNext()){
                        String k=r.nextName();JsonToken t=r.peek();
                        if(t!=JsonToken.STRING&&t!=JsonToken.NUMBER){r.skipValue();continue;}
                        String v=r.nextString();
                        switch(k){
                            case "stream_id":if(!series)id=v;break;
                            case "series_id":if(series)id=v;break;
                            case "name":name=v.trim();break;
                            case "stream_icon":if(!series)icon=v;break;
                            case "cover":if(series)icon=v;break;
                            case "container_extension":ext=v;break;
                            case "category_id":cat=v;break;
                            case "rating":rate=v;break;
                            case "added":if(!series)try{added=Long.parseLong(v.trim());}catch(Exception e){added=0;}break;
                            case "last_modified":if(series)try{added=Long.parseLong(v.trim());}catch(Exception e){added=0;}break;
                            default:break;
                        }
                    }
                    r.endObject();
                    if(id.isEmpty()||name.isEmpty())continue;
                    String k=canon(name);
                    if(!wanted.contains(k))continue;
                    List<Movie> l=out.get(k);
                    if(l==null){l=new ArrayList<>();out.put(k,l);}
                    if(l.size()<10)l.add(new Movie(id,name,icon,ext,cat,rating(rate),added));
                }
            }
        }finally{c.disconnect();}
        return out;
    }
    /** A series' episodes on the new subscription: "season:number" -> episode id. */
    private static Map<String,String> episodeIds(XtreamAccount a,String seriesId) throws Exception {
        Map<String,String> out=new HashMap<>();
        JSONObject o=new JSONObject(XtreamAccount.get(a.api("get_series_info","series_id="+XtreamAccount.enc(seriesId))));
        Object eps=o.opt("episodes");
        List<JSONArray> groups=new ArrayList<>();
        if(eps instanceof JSONObject){java.util.Iterator<String> it=((JSONObject)eps).keys();while(it.hasNext()){JSONArray g=((JSONObject)eps).optJSONArray(it.next());if(g!=null)groups.add(g);}}
        else if(eps instanceof JSONArray){JSONArray g=(JSONArray)eps;for(int k=0;k<g.length();k++){Object x=g.opt(k);if(x instanceof JSONArray)groups.add((JSONArray)x);}}
        for(JSONArray g:groups)for(int k=0;k<g.length();k++){
            JSONObject e=g.optJSONObject(k);if(e==null)continue;
            String id=e.optString("id");if(id.isEmpty())continue;
            JSONObject ei=e.optJSONObject("info");
            int season=e.optInt("season",ei==null?1:ei.optInt("season",1)),num=e.optInt("episode_num",k+1);
            out.put(season+":"+num,id);
        }
        return out;
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

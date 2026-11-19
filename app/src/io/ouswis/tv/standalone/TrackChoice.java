package io.ouswis.tv.standalone;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.ExoPlayer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The sound and subtitle tracks of a film or an episode: what the file has (by language, in Arabic), which one plays,
 * and the viewer's choice, kept for every later film and episode when the file has that language. With no choice made:
 * Arabic sound when the file has it; otherwise the original sound with Arabic subtitles when the file has them.
 */
final class TrackChoice {
    private TrackChoice(){}
    static final String OFF="off";

    static final class Option {
        final int type;final Tracks.Group group;final int index;final String lang,label;final boolean selected;
        Option(int type,Tracks.Group group,int index,String lang,String label,boolean selected){this.type=type;this.group=group;this.index=index;this.lang=lang;this.label=label;this.selected=selected;}
    }

    private static SharedPreferences prefs(Context c){return c.getApplicationContext().getSharedPreferences("ouswis-tracks",Context.MODE_PRIVATE);}
    static String savedAudio(Context c){return prefs(c).getString("audio","");}
    static String savedText(Context c){return prefs(c).getString("text","");}

    private static final Map<String,String> ISO3=new HashMap<>(),NAMES=new HashMap<>();
    static{
        String[][] three={{"ara","ar"},{"eng","en"},{"fre","fr"},{"fra","fr"},{"ger","de"},{"deu","de"},{"spa","es"},{"tur","tr"},{"hin","hi"},{"urd","ur"},
                {"per","fa"},{"fas","fa"},{"ita","it"},{"rus","ru"},{"jpn","ja"},{"kor","ko"},{"chi","zh"},{"zho","zh"},{"por","pt"},{"dut","nl"},{"nld","nl"},{"ind","id"},{"may","ms"},{"msa","ms"}};
        for(String[] p:three)ISO3.put(p[0],p[1]);
        String[][] names={{"ar","عربي"},{"en","إنجليزي"},{"fr","فرنسي"},{"de","ألماني"},{"es","إسباني"},{"tr","تركي"},{"hi","هندي"},{"ur","أردو"},{"fa","فارسي"},
                {"it","إيطالي"},{"ru","روسي"},{"ja","ياباني"},{"ko","كوري"},{"zh","صيني"},{"pt","برتغالي"},{"nl","هولندي"},{"id","إندونيسي"},{"ms","ملايو"}};
        for(String[] p:names)NAMES.put(p[0],p[1]);
    }
    /** "ara", "ar-SA", "Arabic" or a label naming it: "ar". Empty when it is not known. */
    static String lang(Format f){
        String l=f.language==null?"":f.language.trim().toLowerCase(Locale.ROOT);
        int dash=l.indexOf('-');if(dash<0)dash=l.indexOf('_');if(dash>0)l=l.substring(0,dash);
        if(ISO3.containsKey(l))l=ISO3.get(l);
        if(l.equals("und")||l.equals("unknown")||l.equals("mul"))l="";
        String label=f.label==null?"":f.label.toLowerCase(Locale.ROOT);
        if(l.isEmpty()||l.length()>3){
            if(l.startsWith("arab")||label.contains("arab")||label.contains("عرب"))return "ar";
            if(l.startsWith("engl")||label.contains("english"))return "en";
            if(l.length()>3)l="";
        }
        return l;
    }
    static String name(String lang){
        if(lang.isEmpty())return "";
        String n=NAMES.get(lang);
        if(n!=null)return n;
        try{String d=new Locale(lang).getDisplayLanguage(Design.AR);if(!d.isEmpty()&&!d.equalsIgnoreCase(lang))return d;}catch(Exception ignored){}
        return lang;
    }

    /** The playable tracks of one kind (C.TRACK_TYPE_AUDIO or C.TRACK_TYPE_TEXT), named for the menu. */
    static List<Option> options(Tracks tracks,int type){
        List<Option> out=new ArrayList<>();
        if(tracks==null)return out;
        Map<String,Integer> seen=new HashMap<>();
        int n=0;
        for(Tracks.Group g:tracks.getGroups()){
            if(g.getType()!=type)continue;
            for(int i=0;i<g.length;i++){
                if(!g.isTrackSupported(i))continue;
                Format f=g.getTrackFormat(i);
                if(type==C.TRACK_TYPE_TEXT&&f.sampleMimeType!=null&&f.sampleMimeType.contains("cea"))continue;   // broadcast captions, not a subtitle track
                n++;
                String l=lang(f),label=name(l);
                if(label.isEmpty())label=(type==C.TRACK_TYPE_AUDIO?"صوت ":"ترجمة ")+n;
                Integer twice=seen.get(label);seen.put(label,twice==null?1:twice+1);
                out.add(new Option(type,g,i,l,label,g.isTrackSelected(i)));
            }
        }
        // the same language twice: tell them apart (its own title, or 5.1)
        for(int k=0;k<out.size();k++){
            Option o=out.get(k);Integer c=seen.get(o.label);
            if(c==null||c<2)continue;
            Format f=o.group.getTrackFormat(o.index);
            String extra=f.label!=null&&!f.label.trim().isEmpty()?f.label.trim():f.channelCount>2?(f.channelCount==6?"5.1":f.channelCount+" قنوات"):"مسار "+(k+1);
            out.set(k,new Option(o.type,o.group,o.index,o.lang,o.label+" · "+extra,o.selected));
        }
        return out;
    }

    /** The viewer picked this one (text: null is "no subtitles"); it is kept for the next films and episodes. */
    static void pick(Context c,ExoPlayer p,int type,Option o){
        TrackSelectionParameters.Builder b=p.getTrackSelectionParameters().buildUpon();
        if(o==null){b.clearOverridesOfType(type).setTrackTypeDisabled(type,true);}
        else b.setTrackTypeDisabled(type,false).setOverrideForType(new TrackSelectionOverride(o.group.getMediaTrackGroup(),o.index));
        p.setTrackSelectionParameters(b.build());
        String key=type==C.TRACK_TYPE_AUDIO?"audio":"text";
        if(o==null)prefs(c).edit().putString(key,OFF).apply();
        else if(!o.lang.isEmpty())prefs(c).edit().putString(key,o.lang).apply();
    }

    /** Once per file, when its tracks are known: the saved choice where the file has it, else the defaults above. Returns what it did, for the log. */
    static String applyDefaults(Context c,ExoPlayer p,Tracks tracks){
        List<Option> audio=options(tracks,C.TRACK_TYPE_AUDIO),text=options(tracks,C.TRACK_TYPE_TEXT);
        TrackSelectionParameters.Builder b=p.getTrackSelectionParameters().buildUpon();
        String audioLang="";
        for(Option o:audio)if(o.selected)audioLang=o.lang;
        Option want=null;
        String saved=savedAudio(c);
        if(!saved.isEmpty())want=find(audio,saved);
        if(want==null)want=find(audio,"ar");
        if(want!=null&&audio.size()>1){b.setOverrideForType(new TrackSelectionOverride(want.group.getMediaTrackGroup(),want.index));audioLang=want.lang;}
        String st=savedText(c);
        Option sub=null;boolean off;
        if(OFF.equals(st))off=true;
        else{
            if(!st.isEmpty())sub=find(text,st);
            if(sub==null&&!"ar".equals(audioLang))sub=find(text,"ar");
            off=sub==null;
        }
        if(off)b.clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT,true);
        else b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT,false).setOverrideForType(new TrackSelectionOverride(sub.group.getMediaTrackGroup(),sub.index));
        p.setTrackSelectionParameters(b.build());
        return "الصوت: "+(audioLang.isEmpty()?"الافتراضي":name(audioLang))+" ("+audio.size()+" مسار) · الترجمة: "+(off?"بدون":sub.label)+" ("+text.size()+" ترجمة)";
    }
    private static Option find(List<Option> list,String lang){for(Option o:list)if(o.lang.equals(lang))return o;return null;}
}

package io.ouswis.tv.standalone;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Arabic text for guide titles. A match title "Team vs Team - League 2026/2027 - MD4" becomes "فريق × فريق — الدوري"
 * with the teams and the league from {@link TeamNames} and the table below; the extras (season, matchday, week) are
 * dropped. Any other title (films, series, shows) is returned as it came: nothing here ever fails or blocks, and a
 * team or league that isn't in the tables stays in its own language. Answers are remembered per title.
 */
final class Titles {
    private Titles(){}
    private static final String FSI="⁨",PDI="⁩";
    private static final Pattern VS=Pattern.compile("^(.{2,60}?)\\s+(?:vs\\.?|v|x|×)\\s+(.{2,60}?)(?:\\s+[-–—|:]\\s+(.*))?$",Pattern.CASE_INSENSITIVE);
    private static final Pattern EXTRA=Pattern.compile("(?i)^(?:\\d{4}(?:\\s*[/-]\\s*\\d{2,4})?|md\\s*\\d+|matchday\\s*\\d+|week\\s*\\d+|round\\s*\\d+|rd\\s*\\d+|"
            +"gw\\s*\\d+|game\\s*\\d+|leg\\s*\\d|live|direct|en direct|hd|fhd|\\d+(?:st|nd|rd|th)?\\s*(?:leg|round)|المرحلة\\s*\\d+|الجولة\\s*\\d+)$");
    private static final Pattern WORDS=Pattern.compile("(?i)\\b(?:fc|cf|afc|sc|ac|women|womens|women's|w|ladies|feminin|féminin|feminine|féminine)\\b");
    private static final java.util.List<String[]> LEAGUE_LIST=new java.util.ArrayList<>();
    private static final Map<String,String> memo=new java.util.concurrent.ConcurrentHashMap<>();

    private static String key(String s){
        String n=java.text.Normalizer.normalize(s==null?"":s,java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+","");
        return n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9؀-ۿ]+","");
    }
    private static void league(String ar,String... names){for(String n:names)LEAGUE_LIST.add(new String[]{key(n),ar});}
    static{
        // more specific names first: the first one a title contains wins
        league("دوري أبطال أوروبا للسيدات","UEFA Women's Champions League","Women's Champions League","UEFA Womens Champions League","UWCL");
        league("دوري أبطال أوروبا","UEFA Champions League","Champions League","UCL","Ligue des Champions");
        league("الدوري الأوروبي","UEFA Europa League","Europa League","Ligue Europa");
        league("دوري المؤتمر الأوروبي","UEFA Conference League","Europa Conference League","Conference League");
        league("دوري الأمم الأوروبية","UEFA Nations League","Nations League");
        league("الدوري الإنجليزي الممتاز","English Premier League","Premier League","EPL","Barclays Premier League");
        league("كأس الاتحاد الإنجليزي","English FA Cup","FA Cup");
        league("كأس الرابطة الإنجليزية","Carabao Cup","EFL Cup","League Cup");
        league("دوري الدرجة الأولى الإنجليزي","EFL Championship","English Championship");
        league("الدوري الإسباني","Spanish LaLiga","LaLiga EA Sports","La Liga","LaLiga","Liga Española");
        league("كأس ملك إسبانيا","Copa del Rey");
        league("الدوري الإيطالي","Italian Serie A","Serie A");
        league("كأس إيطاليا","Coppa Italia");
        league("الدوري الألماني","German Bundesliga","Bundesliga");
        league("كأس ألمانيا","DFB-Pokal","DFB Pokal");
        league("الدوري الفرنسي","French Ligue 1","Ligue 1","Ligue 1 McDonald's");
        league("كأس فرنسا","Coupe de France");
        league("الدوري البرتغالي","Portuguese Primeira Liga","Primeira Liga","Liga Portugal");
        league("الدوري الهولندي","Dutch Eredivisie","Eredivisie");
        league("الدوري التركي","Turkish Super Lig","Super Lig","Süper Lig");
        league("دوري روشن السعودي","Saudi Pro League","Roshn Saudi League","Saudi Roshn League");
        league("كأس الملك","Saudi Kings Cup","King's Cup","Kings Cup");
        league("كأس السوبر السعودي","Saudi Super Cup");
        league("دوري أبطال آسيا للنخبة","AFC Champions League Elite");
        league("دوري أبطال آسيا 2","AFC Champions League Two");
        league("دوري أبطال آسيا","AFC Champions League");
        league("كأس آسيا","AFC Asian Cup","Asian Cup");
        league("دوري أبطال أفريقيا","CAF Champions League");
        league("كأس الأمم الأفريقية","Africa Cup of Nations","AFCON");
        league("تصفيات كأس العالم","World Cup Qualifiers","World Cup Qualifying","FIFA World Cup Qualifiers");
        league("كأس العالم","FIFA World Cup","World Cup");
        league("كأس العالم للأندية","FIFA Club World Cup","Club World Cup");
        league("الدوري الأمريكي","Major League Soccer");
        league("المباراة الودية","International Friendly","Friendly","Club Friendly");
    }

    /** True for "A vs B" style titles. */
    static boolean isMatch(String title){return title!=null&&VS.matcher(title.trim()).matches();}

    static String arabic(String title){
        if(title==null)return "";
        String t=title.trim();
        if(t.isEmpty())return t;
        String hit=memo.get(t);
        if(hit!=null)return hit;
        String out=t;
        try{out=translate(t);}catch(Throwable ignored){out=t;}
        if(memo.size()>600)memo.clear();
        memo.put(t,out);
        return out;
    }

    private static String translate(String t){
        Matcher m=VS.matcher(t);
        if(!m.matches())return t;
        String home=team(m.group(1)),away=team(m.group(2));
        String rest=m.group(3)==null?"":m.group(3);
        StringBuilder compName=new StringBuilder();
        // what follows the teams: the league and extras, split on dashes and bars; extras are dropped
        for(String part:rest.split("\\s+[-–—|]\\s+|\\s*\\|\\s*")){
            String p=part.trim();
            if(p.isEmpty()||EXTRA.matcher(p).matches())continue;
            String ar=leagueOf(p);
            String shown=ar!=null?ar:cleanExtra(p);
            if(shown.isEmpty())continue;
            if(compName.length()>0)compName.append(" · ");
            compName.append(shown);
        }
        String line=home+" × "+away;
        return compName.length()>0?line+" — "+compName:line;
    }

    /** "UEFA Women's Champions League 2026/2027 MD4" → the Arabic league name; null when no league is recognised. */
    private static String leagueOf(String part){
        String k=key(part);
        for(String[] l:LEAGUE_LIST)if(k.contains(l[0])&&l[0].length()>=3)return l[1];
        return null;
    }
    /** An unknown league keeps its words but loses season and matchday fragments. */
    private static String cleanExtra(String p){
        String s=p.replaceAll("(?i)\\b\\d{4}\\s*[/-]\\s*\\d{2,4}\\b","").replaceAll("(?i)\\b(?:md|gw|rd)\\s*\\d+\\b","")
                .replaceAll("(?i)\\b(?:matchday|week|round|leg)\\s*\\d+\\b","").replaceAll("\\s{2,}"," ").trim();
        return s;
    }

    private static String team(String raw){
        String name=raw.trim();
        String ar=lookup(name);
        if(ar==null){
            String stripped=WORDS.matcher(name).replaceAll(" ").replaceAll("\\s{2,}"," ").trim();
            ar=lookup(stripped);
            if(ar==null&&stripped.matches("(?i)^(?:OL|AS|SS|US|CD|RC|FK|SK|NK|AEK)\\s+.+"))ar=lookup(stripped.replaceFirst("(?i)^\\S+\\s+",""));
        }
        if(ar!=null)return ar;
        return name.matches(".*[A-Za-z].*")?FSI+name+PDI:name;   // an unknown team stays as it is, isolated so it can't reorder the line
    }
    private static String lookup(String name){
        if(name.isEmpty())return null;
        String v=TeamNames.arabic(name);
        return v.equals(name)?null:v;
    }

    private static final java.util.Set<String> STOP=new java.util.HashSet<>(java.util.Arrays.asList("fc","cf","afc","sc","ac","women","womens","club","de","the","and","real"));
    private static java.util.List<String> tokens(String s){
        java.util.List<String> out=new ArrayList<>();
        String n=java.text.Normalizer.normalize(s==null?"":s,java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+","");
        for(String t:n.toLowerCase(java.util.Locale.ROOT).split("[^a-z0-9\u0600-\u06FF]+"))if(t.length()>=3&&!STOP.contains(t))out.add(t);
        return out;
    }
    /** The team's longest significant word shows in the title (a prefix is enough: "lyon" is in "lyonnes"), or its Arabic name does. */
    static boolean mentions(String title,String english,String arabic){
        if(title==null||title.isEmpty())return false;
        if(arabic!=null&&arabic.length()>=3&&!arabic.equals(english)&&title.contains(arabic))return true;
        java.util.List<String> want=tokens(english),have=tokens(title);
        if(want.isEmpty())return false;
        String longest=want.get(0);
        for(String w:want)if(w.length()>longest.length())longest=w;
        for(String t:have){
            if(t.equals(longest))return true;
            if(Math.min(t.length(),longest.length())>=4&&(t.startsWith(longest)||longest.startsWith(t)))return true;
        }
        return false;
    }
}

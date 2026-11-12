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
    private static final Pattern VS=Pattern.compile("^(.{2,60}?)\\s+(?:vs\\.?|v|x|×|ضد)\\s+(.{2,60}?)(?:\\s+[-–—|:·]\\s+(.*))?$",Pattern.CASE_INSENSITIVE);
    private static final Pattern EXTRA=Pattern.compile("(?i)^(?:\\d{4}(?:\\s*[/-]\\s*\\d{2,4})?|md\\s*\\d+|matchday\\s*\\d+|week\\s*\\d+|round\\s*\\d+|rd\\s*\\d+|"
            +"gw\\s*\\d+|game\\s*\\d+|leg\\s*\\d|live|direct|en direct|hd|fhd|\\d+(?:st|nd|rd|th)?\\s*(?:leg|round)|المرحلة\\s*\\d+|الجولة\\s*\\d+)$");
    private static final Pattern WORDS=Pattern.compile("(?i)\\b(?:fc|cf|afc|sc|ac|women|womens|women's|w|ladies|feminin|féminin|feminine|féminine)\\b");
    private static final java.util.List<String[]> LEAGUE_LIST=new java.util.ArrayList<>();
    private static final Map<String,String> memo=new java.util.concurrent.ConcurrentHashMap<>();

    static String key(String s){
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

    /** A match title split into {home as written, away as written, the league part in Arabic or as written}; null for any other title. */
    static String[] parseMatch(String title){
        if(title==null)return null;
        Matcher m=VS.matcher(title.trim());
        if(!m.matches())return null;
        String rest=m.group(3)==null?"":m.group(3),comp="";
        for(String part:rest.split("\\s+[-–—|·]\\s+|\\s*\\|\\s*")){
            String p=part.trim();
            if(p.isEmpty()||EXTRA.matcher(p).matches())continue;
            String ar=leagueOf(p);
            comp=ar!=null?ar:cleanExtra(p);
            if(!comp.isEmpty())break;
        }
        return new String[]{m.group(1).trim(),m.group(2).trim(),comp};
    }
    /** A team's Arabic name from the tables; as written (isolated) when it isn't there. */
    static String teamArabic(String raw){return team(raw);}
    /** True for a programme that is a repeat or a summary, not the match itself. */
    static boolean isRerun(String title){
        return title!=null&&Pattern.compile("(?i)\\b(replay|rerun|re-run|highlights|re-live|repeat)\\b|إعادة|ملخص|\\(r\\)").matcher(title).find();
    }

    /** The chip text of a competition in the matches filter ("تصفيات كأس العالم - آسيا" → "تصفيات كأس العالم"). */
    static String filterLabel(String competition){
        String c=competition==null?"":competition.trim();
        if(c.startsWith("تصفيات كأس العالم"))return "تصفيات كأس العالم";
        if(c.startsWith("تصفيات كأس الأمم الأفريقية"))return "تصفيات أمم أفريقيا";
        if(c.equals("الدوري الإنجليزي الممتاز"))return "الدوري الإنجليزي";
        return c;
    }
    /** A short league name for a match card, so it is never cut ("دوري الأمم الأوروبية" → "دوري الأمم"). */
    static String shortLeague(String competition){
        String c=competition==null?"":competition.trim();
        if(c.startsWith("تصفيات كأس العالم")){int i=c.indexOf(" - ");return "تصفيات العالم"+(i>0?" - "+c.substring(i+3):"");}
        String[][] t={{"تصفيات كأس الأمم الأفريقية","تصفيات أفريقيا"},{"دوري الأمم الأوروبية","دوري الأمم"},{"دوري أبطال أوروبا للسيدات","أبطال أوروبا للسيدات"},
            {"دوري أبطال أوروبا","أبطال أوروبا"},{"دوري أبطال آسيا للنخبة","أبطال آسيا للنخبة"},{"دوري أبطال آسيا","أبطال آسيا"},{"دوري أبطال أفريقيا","أبطال أفريقيا"},
            {"دوري المؤتمر الأوروبي","دوري المؤتمر"},{"الدوري الإنجليزي الممتاز","الإنجليزي"},{"الدوري الإسباني","الإسباني"},{"الدوري الإيطالي","الإيطالي"},
            {"الدوري الألماني","الألماني"},{"الدوري الفرنسي","الفرنسي"},{"دوري روشن السعودي","دوري روشن"},{"كأس الاتحاد الإنجليزي","كأس الاتحاد"},
            {"كأس الرابطة الإنجليزية","كأس الرابطة"},{"كأس ملك إسبانيا","كأس إسبانيا"},{"كأس الأمم الأفريقية","كأس أفريقيا"},{"المباراة الودية الدولية","ودية دولية"},
            {"كأس الكونفدرالية الأفريقية","كونفدرالية أفريقيا"}};
        for(String[] x:t)if(c.startsWith(x[0]))return x[1]+c.substring(x[0].length());
        return c;
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
        for(String part:rest.split("\\s+[-–—|·]\\s+|\\s*\\|\\s*")){
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
        missing(name);
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

    // MARK: matching a guide title against a match (Arabic or English, before or after translation)

    private static final Pattern DROP=Pattern.compile("(?i)^(?:fc|cf|afc|sc|ac|fk|sk|nk|women|womens|ladies|w|u\\d{2}|under\\d{2}|\\d+|نادي)$");
    /** A team name made comparable: case, accents, symbols, FC/U23-like words, the Arabic "ال" and letter variants all ignored. */
    static String norm(String s){
        if(s==null)return "";
        String n=java.text.Normalizer.normalize(s,java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+","")
                .replace("\u2068","").replace("\u2069","").replace("ـ","");
        n=n.toLowerCase(Locale.ROOT).replace('أ','ا').replace('إ','ا').replace('آ','ا').replace('ٱ','ا').replace('ى','ي').replace('ة','ه');
        StringBuilder out=new StringBuilder();
        for(String t:n.split("[^a-z0-9\u0600-\u06FF]+")){
            if(t.isEmpty()||DROP.matcher(t).matches())continue;
            if(t.length()>3&&t.startsWith("ال"))t=t.substring(2);
            out.append(t);
        }
        return out.toString();
    }
    /** Does a team as written in a title ("Ghana", "غانا", "OL Lyonnes") name this team (English and Arabic names known)? */
    static boolean sameTeam(String written,String english,String arabic){
        String w=norm(written);
        if(w.isEmpty())return false;
        String[] mine={norm(english),norm(arabic),norm(TeamNames.arabic(english==null?"":english))};
        String[] theirs={w,norm(TeamNames.arabic(written.trim())),norm(lookup(written.trim())==null?"":lookup(written.trim()))};
        for(String a:theirs)for(String b:mine){
            if(a.isEmpty()||b.isEmpty())continue;
            if(a.equals(b))return true;
            int shortest=Math.min(a.length(),b.length());
            if(shortest>=4&&(a.contains(b)||b.contains(a)))return true;
        }
        return mentions(written,english,arabic);
    }
    /** The two team parts of a guide title: "A × B", "A vs B", or "A - B - League". Null when it doesn't look like a match. */
    static String[] teamsOf(String title){
        String[] p=parseMatch(title);
        if(p!=null)return new String[]{p[0],p[1]};
        if(title==null)return null;
        String[] dash=title.trim().split("\\s+[-–—]\\s+");
        if(dash.length>=2&&dash[0].length()>=2&&dash[1].length()>=2)return new String[]{dash[0],dash[1]};
        String[] cross=title.trim().split("\\s*[×]\\s*",2);
        if(cross.length==2&&cross[0].length()>=2&&cross[1].length()>=2)return new String[]{cross[0],cross[1].split("\\s+[-–—|·:]\\s+")[0]};
        return null;
    }
    /** True when the title names both teams (either order), by their Arabic or English names. */
    static boolean sameMatch(String title,String homeEn,String homeAr,String awayEn,String awayAr){
        if(title==null||title.isEmpty())return false;
        String[] t=teamsOf(title);
        if(t!=null){
            if(sameTeam(t[0],homeEn,homeAr)&&sameTeam(t[1],awayEn,awayAr))return true;
            if(sameTeam(t[0],awayEn,awayAr)&&sameTeam(t[1],homeEn,homeAr))return true;
        }
        return mentions(title,homeEn,homeAr)&&mentions(title,awayEn,awayAr);
    }

    // MARK: names that aren't in the tables

    private static final java.util.Set<String> MISSING=java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String,Boolean>());
    /** Called with each name that stays in English; the app logs it once so it can be added to the tables. */
    static volatile java.util.function.Consumer<String> missingSink;
    static void missing(String name){
        if(name==null||name.trim().length()<2||!name.matches(".*[A-Za-z].*"))return;
        if(MISSING.add(name.trim())){java.util.function.Consumer<String> s=missingSink;if(s!=null)try{s.accept(name.trim());}catch(Throwable ignored){}}
    }

    // MARK: channel names

    private static final Pattern BAD_QUALITY=Pattern.compile("(?i)(4k|uhd|2160|h\\.?265|hevc|x265)");
    private static final Pattern GOOD_QUALITY=Pattern.compile("(?i)\\b(fhd|hd|1080p?|720p?)\\b");
    /** Lower is better: HD or FHD first, then unmarked, then SD, and 4K or H.265 last (used only when nothing else carries the match). */
    static int qualityPenalty(String name){
        String n=name==null?"":name;
        if(BAD_QUALITY.matcher(n).find())return 3;
        if(GOOD_QUALITY.matcher(n).find())return 0;
        if(Pattern.compile("(?i)\\bsd\\b").matcher(n).find())return 2;
        return 1;
    }
    private static final Pattern PREFIX=Pattern.compile("^\\s*[A-Za-z]{2,3}\\s*[|:]\\s*");
    private static final Pattern QUALITY_TAGS=Pattern.compile("(?i)[\\[(]?\\b(?:h\\.?265|hevc|x265|4k|uhd|fhd|hd|sd)\\b[\\])]?");
    /** A channel's name for a card: no "FR|" prefix, no (H.265), no HD tag: "FR| beIN SPORTS 1 HD (H.265)" reads "beIN SPORTS 1". */
    static String channelName(String name){
        if(name==null)return "";
        String n=PREFIX.matcher(name).replaceFirst("");
        n=QUALITY_TAGS.matcher(n).replaceAll(" ").replaceAll("\\(\\s*\\)","").replaceAll("\\s{2,}"," ").trim();
        return n.isEmpty()?name.trim():n;
    }
}

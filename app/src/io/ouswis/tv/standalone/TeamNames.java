package io.ouswis.tv.standalone;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Arabic names for national teams and the clubs people watch most. The feed (ESPN) answers in English; a name not in
 * this table is shown as it came (English). Lookup ignores case, accents, hyphens and spacing, so "Al-Hilal",
 * "Al Hilal" and "al hilal" are one entry.
 */
final class TeamNames {
    private TeamNames(){}
    private static final Map<String,String> MAP=new HashMap<>();
    private static String key(String s){
        String n=Normalizer.normalize(s==null?"":s,Normalizer.Form.NFD).replaceAll("\\p{M}+","");
        return n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+","");
    }
    static String arabic(String english){
        if(english==null)return "";
        String v=MAP.get(key(english));
        return v==null?english:v;
    }
    private static void add(String... pairs){for(String p:pairs){int i=p.indexOf('|');MAP.put(key(p.substring(0,i)),p.substring(i+1));}}
    static{
        // Europe (UEFA)
        add("Albania|ألبانيا","Andorra|أندورا","Armenia|أرمينيا","Austria|النمسا","Azerbaijan|أذربيجان","Belarus|بيلاروسيا","Belgium|بلجيكا",
            "Bosnia and Herzegovina|البوسنة والهرسك","Bosnia-Herzegovina|البوسنة والهرسك","Bulgaria|بلغاريا","Croatia|كرواتيا","Cyprus|قبرص",
            "Czechia|التشيك","Czech Republic|التشيك","Denmark|الدنمارك","England|إنجلترا","Estonia|إستونيا","Faroe Islands|جزر فارو",
            "Finland|فنلندا","France|فرنسا","Georgia|جورجيا","Germany|ألمانيا","Gibraltar|جبل طارق","Greece|اليونان","Hungary|المجر",
            "Iceland|آيسلندا","Ireland|أيرلندا","Republic of Ireland|أيرلندا","Israel|إسرائيل","Italy|إيطاليا","Kazakhstan|كازاخستان",
            "Kosovo|كوسوفو","Latvia|لاتفيا","Liechtenstein|ليختنشتاين","Lithuania|ليتوانيا","Luxembourg|لوكسمبورغ","Malta|مالطا",
            "Moldova|مولدوفا","Montenegro|الجبل الأسود","Netherlands|هولندا","North Macedonia|مقدونيا الشمالية","Northern Ireland|أيرلندا الشمالية",
            "Norway|النرويج","Poland|بولندا","Portugal|البرتغال","Romania|رومانيا","Russia|روسيا","San Marino|سان مارينو","Scotland|اسكتلندا",
            "Serbia|صربيا","Slovakia|سلوفاكيا","Slovenia|سلوفينيا","Spain|إسبانيا","Sweden|السويد","Switzerland|سويسرا","Turkey|تركيا",
            "Türkiye|تركيا","Ukraine|أوكرانيا","Wales|ويلز");
        // Asia (AFC) and Oceania
        add("Saudi Arabia|السعودية","Qatar|قطر","United Arab Emirates|الإمارات","UAE|الإمارات","Kuwait|الكويت","Bahrain|البحرين","Oman|عُمان",
            "Iraq|العراق","Jordan|الأردن","Syria|سوريا","Lebanon|لبنان","Palestine|فلسطين","Yemen|اليمن","Iran|إيران","IR Iran|إيران",
            "Japan|اليابان","South Korea|كوريا الجنوبية","Korea Republic|كوريا الجنوبية","North Korea|كوريا الشمالية","China|الصين",
            "China PR|الصين","Australia|أستراليا","New Zealand|نيوزيلندا","Uzbekistan|أوزبكستان","Kyrgyzstan|قيرغيزستان","Tajikistan|طاجيكستان",
            "Turkmenistan|تركمانستان","Afghanistan|أفغانستان","India|الهند","Pakistan|باكستان","Bangladesh|بنغلاديش","Thailand|تايلاند",
            "Vietnam|فيتنام","Indonesia|إندونيسيا","Malaysia|ماليزيا","Philippines|الفلبين","Singapore|سنغافورة","Hong Kong|هونغ كونغ",
            "Hong Kong, China|هونغ كونغ","Chinese Taipei|تايبيه الصينية","Myanmar|ميانمار","Nepal|نيبال","Maldives|جزر المالديف");
        // Africa (CAF)
        add("Egypt|مصر","Morocco|المغرب","Algeria|الجزائر","Tunisia|تونس","Libya|ليبيا","Sudan|السودان","Mauritania|موريتانيا","Senegal|السنغال",
            "Nigeria|نيجيريا","Ghana|غانا","Cameroon|الكاميرون","Ivory Coast|ساحل العاج","Cote d'Ivoire|ساحل العاج","Mali|مالي","Burkina Faso|بوركينا فاسو",
            "Guinea|غينيا","South Africa|جنوب أفريقيا","DR Congo|الكونغو الديمقراطية","Congo DR|الكونغو الديمقراطية","Congo|الكونغو","Gabon|الغابون",
            "Angola|أنغولا","Zambia|زامبيا","Zimbabwe|زيمبابوي","Kenya|كينيا","Uganda|أوغندا","Tanzania|تنزانيا","Ethiopia|إثيوبيا",
            "Cape Verde|الرأس الأخضر","Cabo Verde|الرأس الأخضر","Benin|بنين","Togo|توغو","Mozambique|موزمبيق","Namibia|ناميبيا","Madagascar|مدغشقر",
            "Comoros|جزر القمر","Eritrea|إريتريا","Guinea-Bissau|غينيا بيساو","Guinea Bissau|غينيا بيساو","Suriname|سورينام","Eswatini|إسواتيني","Djibouti|جيبوتي","Somalia|الصومال","Equatorial Guinea|غينيا الاستوائية","Niger|النيجر","Sierra Leone|سيراليون");
        // Americas
        add("Brazil|البرازيل","Argentina|الأرجنتين","Uruguay|أوروغواي","Colombia|كولومبيا","Chile|تشيلي","Peru|بيرو","Ecuador|الإكوادور",
            "Paraguay|باراغواي","Bolivia|بوليفيا","Venezuela|فنزويلا","United States|الولايات المتحدة","USA|الولايات المتحدة","Mexico|المكسيك",
            "Canada|كندا","Costa Rica|كوستاريكا","Panama|بنما","Jamaica|جامايكا","Honduras|هندوراس","El Salvador|السلفادور","Haiti|هايتي",
            "Cuba|كوبا","Guatemala|غواتيمالا","Curacao|كوراساو","Trinidad and Tobago|ترينيداد وتوباغو");
        // Saudi Pro League and other Saudi / Gulf clubs
        add("Al Hilal|الهلال","Al Nassr|النصر","Al Ittihad|الاتحاد","Al Ahli|الأهلي","Al Ahli Saudi|الأهلي","Al Shabab|الشباب","Al Ettifaq|الاتفاق",
            "Al Fateh|الفتح","Al Taawoun|التعاون","Al Raed|الرائد","Al Fayha|الفيحاء","Al Khaleej|الخليج","Damac|ضمك","Damac FC|ضمك",
            "Al Riyadh|الرياض","Al Qadsiah|القادسية","Al Okhdood|الأخدود","Al Kholood|الخلود","Al Orobah|العروبة","Al Wehda|الوحدة",
            "Neom|نيوم","Neom SC|نيوم","Al Hazem|الحزم","Al Tai|الطائي","Al Najma|النجمة","Abha|أبها","Al Batin|الباطن","Al Adalah|العدالة",
            "Al Akhdoud|الأخدود","Al Jabalain|الجبلين","Al Arabi|العربي","Al Sadd|السد","Al Duhail|الدحيل","Al Gharafa|الغرافة","Al Rayyan|الريان",
            "Al Wakrah|الوكرة","Al Ain|العين","Al Wahda|الوحدة الإماراتي","Al Wasl|الوصل","Shabab Al Ahli|شباب الأهلي","Al Jazira|الجزيرة",
            "Sharjah|الشارقة","Al Nasr|النصر الإماراتي","Al Kuwait|الكويت","Al Qadsia|القادسية الكويتي","Al Rafa|الرفاع","Al Zawraa|الزوراء",
            "Al Ahly|الأهلي المصري","Zamalek|الزمالك","Pyramids|بيراميدز","Wydad Casablanca|الوداد","Raja Casablanca|الرجاء","Esperance de Tunis|الترجي",
            "Espérance Tunis|الترجي","Mamelodi Sundowns|صن داونز");
        // England
        add("Manchester City|مانشستر سيتي","Manchester United|مانشستر يونايتد","Liverpool|ليفربول","Arsenal|آرسنال","Chelsea|تشيلسي",
            "Tottenham Hotspur|توتنهام","Tottenham|توتنهام","Newcastle United|نيوكاسل","Aston Villa|أستون فيلا","West Ham United|وست هام",
            "Brighton & Hove Albion|برايتون","Brighton|برايتون","Everton|إيفرتون","Leicester City|ليستر سيتي","Wolverhampton Wanderers|وولفرهامبتون",
            "Wolves|وولفرهامبتون","Crystal Palace|كريستال بالاس","Fulham|فولهام","Brentford|برينتفورد","Nottingham Forest|نوتنغهام فورست",
            "Bournemouth|بورنموث","AFC Bournemouth|بورنموث","Southampton|ساوثهامبتون","Leeds United|ليدز يونايتد","Burnley|بيرنلي",
            "Sunderland|سندرلاند","Ipswich Town|إيبسويتش","Sheffield United|شيفيلد يونايتد");
        // Spain
        add("Real Madrid|ريال مدريد","Barcelona|برشلونة","FC Barcelona|برشلونة","Atletico Madrid|أتلتيكو مدريد","Atlético Madrid|أتلتيكو مدريد",
            "Sevilla|إشبيلية","Real Sociedad|ريال سوسيداد","Real Betis|ريال بيتيس","Villarreal|فياريال","Athletic Club|أتلتيك بلباو",
            "Athletic Bilbao|أتلتيك بلباو","Valencia|فالنسيا","Girona|جيرونا","Osasuna|أوساسونا","Celta Vigo|سيلتا فيغو","Getafe|خيتافي",
            "Mallorca|مايوركا","Rayo Vallecano|رايو فاييكانو","Alaves|ألافيس","Las Palmas|لاس بالماس","Espanyol|إسبانيول","Elche|إلتشي",
            "Levante|ليفانتي","Real Oviedo|ريال أوفييدو");
        // Italy
        add("Juventus|يوفنتوس","Inter Milan|إنتر ميلان","Internazionale|إنتر ميلان","Inter|إنتر ميلان","AC Milan|ميلان","Milan|ميلان",
            "Napoli|نابولي","Roma|روما","AS Roma|روما","Lazio|لاتسيو","Atalanta|أتالانتا","Fiorentina|فيورنتينا","Bologna|بولونيا",
            "Torino|تورينو","Genoa|جنوى","Udinese|أودينيزي","Sassuolo|ساسولو","Lecce|ليتشي","Cagliari|كالياري","Verona|هيلاس فيرونا",
            "Hellas Verona|هيلاس فيرونا","Parma|بارما","Como|كومو","Cremonese|كريمونيزي","Pisa|بيزا");
        // Germany
        add("Bayern Munich|بايرن ميونخ","Bayern München|بايرن ميونخ","Borussia Dortmund|دورتموند","Bayer Leverkusen|ليفركوزن","RB Leipzig|لايبزيغ",
            "Eintracht Frankfurt|آينتراخت فرانكفورت","VfB Stuttgart|شتوتغارت","Borussia Mönchengladbach|مونشنغلادباخ","Borussia Monchengladbach|مونشنغلادباخ",
            "Wolfsburg|فولفسبورغ","Werder Bremen|فيردر بريمن","Union Berlin|يونيون برلين","Freiburg|فرايبورغ","Hoffenheim|هوفنهايم",
            "Mainz|ماينز","Augsburg|أوغسبورغ","Hamburg|هامبورغ","Hamburger SV|هامبورغ","Schalke 04|شالكه","FC Cologne|كولن","Köln|كولن");
        // France and others
        add("Paris Saint-Germain|باريس سان جيرمان","PSG|باريس سان جيرمان","Marseille|مارسيليا","Olympique Marseille|مارسيليا","Lyon|ليون",
            "Olympique Lyonnais|ليون","Monaco|موناكو","AS Monaco|موناكو","Lille|ليل","Nice|نيس","Lens|لانس","Rennes|رين","Strasbourg|ستراسبورغ",
            "Nantes|نانت","Toulouse|تولوز","Reims|ريمس","Brest|بريست","Benfica|بنفيكا","Porto|بورتو","FC Porto|بورتو","Sporting CP|سبورتينغ لشبونة",
            "Sporting Lisbon|سبورتينغ لشبونة","Braga|براغا","Ajax|أياكس","PSV Eindhoven|آيندهوفن","PSV|آيندهوفن","Feyenoord|فينورد","AZ Alkmaar|ألكمار",
            "Celtic|سلتيك","Rangers|رينجرز","Galatasaray|غلطة سراي","Fenerbahce|فنربخشة","Fenerbahçe|فنربخشة","Besiktas|بشكتاش","Beşiktaş|بشكتاش",
            "Trabzonspor|طرابزون سبور","Olympiacos|أولمبياكوس","Panathinaikos|باناثينايكوس","Shakhtar Donetsk|شاختار دونيتسك","Dynamo Kyiv|دينامو كييف",
            "Club Brugge|كلوب بروج","Anderlecht|أندرلخت","Red Bull Salzburg|ريد بول سالزبورغ","Young Boys|يانغ بويز","Copenhagen|كوبنهاغن",
            "Slavia Prague|سلافيا براغ","Sparta Prague|سبارتا براغ","Dinamo Zagreb|دينامو زغرب","Red Star Belgrade|النجم الأحمر","Qarabag|قره باغ");
        // short forms and women's sides as TV guides write them
        add("Lyonnes|ليون","OL Lyonnes|ليون","OL Lyon|ليون","Olympique Lyon|ليون","Man City|مانشستر سيتي","Man United|مانشستر يونايتد","Man Utd|مانشستر يونايتد",
            "Spurs|توتنهام","Bayern|بايرن ميونخ","Leverkusen|ليفركوزن","Dortmund|دورتموند","Leipzig|لايبزيغ","Frankfurt|آينتراخت فرانكفورت",
            "Stuttgart|شتوتغارت","Newcastle|نيوكاسل","West Ham|وست هام","Atletico|أتلتيكو مدريد","Athletic|أتلتيك بلباو","Sporting|سبورتينغ لشبونة",
            "Paris SG|باريس سان جيرمان","Paris Saint Germain|باريس سان جيرمان","Olympique de Marseille|مارسيليا","Inter Milano|إنتر ميلان",
            "Internazionale Milano|إنتر ميلان","Slavia Praha|سلافيا براغ","Sparta Praha|سبارتا براغ","Sturm Graz|شتورم غراتس","Bordeaux|بوردو",
            "Montpellier|مونبلييه","Saint-Etienne|سانت إتيان","Barca|برشلونة","Bayern Munchen|بايرن ميونخ");
        // South America and the US
        add("Flamengo|فلامنغو","Palmeiras|بالميراس","Corinthians|كورنثيانز","Sao Paulo|ساو باولو","Santos|سانتوس","Fluminense|فلومينينسي",
            "Boca Juniors|بوكا جونيورز","River Plate|ريفر بليت","Inter Miami|إنتر ميامي","LA Galaxy|لوس أنجلوس غالاكسي","Los Angeles FC|لوس أنجلوس إف سي",
            "Pachuca|باتشوكا","Monterrey|مونتيري","Club America|أمريكا المكسيكي","Seattle Sounders|سياتل ساوندرز");
    }
}

package io.ouswis.tv.standalone;

import android.content.Context;
import android.content.Intent;
import android.os.Process;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** Display-only, app-scoped events with a short-lived private record for returning to Home. */
final class IslandEvents {
    static final String ACTION="io.ouswis.tv.standalone.ISLAND_EVENT";
    private static final ExecutorService IO=Executors.newSingleThreadExecutor();
    static void post(Context c,String key,String title){
        if(title==null||title.trim().isEmpty())return;
        String clean=title.replaceAll("[\\p{Cntrl}]"," ").trim();
        if(clean.length()>120)clean=clean.substring(0,120);
        final String value=clean;final Context app=c.getApplicationContext();
        app.sendBroadcast(new Intent(ACTION).setPackage(app.getPackageName()).putExtra("key",key).putExtra("title",value));
        if(key.startsWith("notification:"))return; // notification titles stay transient; no notification history is stored
        IO.execute(()->{
            File temp=new File(app.getFilesDir(),"island-event."+Process.myPid()+".tmp");
            try{
                JSONObject record=new JSONObject().put("key",key).put("title",value).put("at",System.currentTimeMillis());
                try(FileOutputStream stream=new FileOutputStream(temp)){stream.write(record.toString().getBytes(StandardCharsets.UTF_8));}
                if(!temp.renameTo(new File(app.getFilesDir(),"island-event.json")))temp.delete();
            }catch(Exception ignored){temp.delete();}
        });
    }
    static String[] latest(Context c){
        try(FileInputStream in=new FileInputStream(new File(c.getFilesDir(),"island-event.json"))){
            byte[] bytes=new byte[4096];int count=in.read(bytes);if(count<=0||in.read()!=-1)return null;
            JSONObject record=new JSONObject(new String(bytes,0,count,StandardCharsets.UTF_8));
            long age=System.currentTimeMillis()-record.getLong("at");
            if(age<0||age>8000)return null;
            String text=record.getString("title");if(text.isEmpty()||text.length()>120)return null;
            return new String[]{record.getString("key"),text};
        }catch(Exception ignored){return null;}
    }
    static void clear(Context c,String key){
        c.sendBroadcast(new Intent(ACTION).setPackage(c.getPackageName()).putExtra("key",key).putExtra("clear",true));
        Context app=c.getApplicationContext();
        IO.execute(()->{String[] event=latest(app);if(event!=null&&key.equals(event[0]))new File(app.getFilesDir(),"island-event.json").delete();});
    }
}

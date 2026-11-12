package io.ouswis.tv.standalone;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.KeyStore;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Where the Xtream login lives: server, user and password as one AES-256-GCM blob in the app's private preferences
 * ("xtream-vault"), sealed with a key in the Android Keystore (it never leaves the secure hardware or the system's
 * key store, and a copy of the preferences file is useless without it). If a TV's Keystore refuses to make the key, a
 * random key kept in the app's private files is used instead ("f:" blobs); the log never says which, nor any value.
 * A login an older version saved in plain text (XtreamActivity's preferences) is moved here on first read and erased.
 */
final class AccountVault {
    private AccountVault(){}
    private static final String ALIAS="ouswis-xtream-account",FILE="xtream-vault",BLOB="blob";
    private static String[] cached;
    private static boolean cachedSet;

    /** {server, user, pass}, or null when nobody has signed in. */
    static synchronized String[] load(Context c){
        Context app=c.getApplicationContext()!=null?c.getApplicationContext():c;
        migrate(app);
        if(cachedSet)return cached;
        String blob=app.getSharedPreferences(FILE,Context.MODE_PRIVATE).getString(BLOB,null);
        String[] out=null;
        if(blob!=null){
            try{
                String[] parts=unseal(app,blob).split("\n",-1);
                if(parts.length==3&&!parts[0].isEmpty()&&!parts[1].isEmpty()&&!parts[2].isEmpty())out=parts;
            }catch(Exception ignored){}
        }
        cached=out;cachedSet=true;
        return out;
    }

    static synchronized void save(Context c,String server,String user,String pass){
        Context app=c.getApplicationContext()!=null?c.getApplicationContext():c;
        try{
            String blob=seal(app,server+"\n"+user+"\n"+pass);
            app.getSharedPreferences(FILE,Context.MODE_PRIVATE).edit().putString(BLOB,blob).apply();
            cached=new String[]{server,user,pass};cachedSet=true;
            wipePlain(app);
        }catch(Exception e){
            // Never fall back to writing the password in the clear: say it didn't save.
            android.util.Log.w("OUSWIS","Could not seal the Xtream login");
            cachedSet=false;
        }
    }

    /** Sign out: the blob and any plain copy go. */
    static synchronized void clear(Context c){
        Context app=c.getApplicationContext()!=null?c.getApplicationContext():c;
        app.getSharedPreferences(FILE,Context.MODE_PRIVATE).edit().clear().apply();
        wipePlain(app);cached=null;cachedSet=true;
    }

    private static void migrate(Context app){
        SharedPreferences p=app.getSharedPreferences("XtreamActivity",Context.MODE_PRIVATE);
        String s=p.getString("server",""),u=p.getString("user",""),w=p.getString("pass","");
        if(s.isEmpty()&&u.isEmpty()&&w.isEmpty())return;
        if(!s.isEmpty()&&!u.isEmpty()&&!w.isEmpty()){
            try{
                String blob=seal(app,s+"\n"+u+"\n"+w);
                app.getSharedPreferences(FILE,Context.MODE_PRIVATE).edit().putString(BLOB,blob).commit();
                cachedSet=false;
            }catch(Exception e){return;}   // couldn't seal: leave it (and the app working) rather than lose the login
        }
        wipePlain(app);
    }
    private static void wipePlain(Context app){
        app.getSharedPreferences("XtreamActivity",Context.MODE_PRIVATE).edit().remove("server").remove("user").remove("pass").commit();
    }

    // MARK: sealing

    private static SecretKey keystoreKey() throws Exception {
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
        if(ks.containsAlias(ALIAS))return (SecretKey)ks.getKey(ALIAS,null);
        KeyGenerator g=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
        return g.generateKey();
    }
    private static SecretKey fileKey(Context app,boolean create) throws Exception {
        File f=new File(app.getFilesDir(),".vault-key");
        if(!f.exists()){
            if(!create)throw new IllegalStateException("no key");
            byte[] k=new byte[32];new SecureRandom().nextBytes(k);
            try(FileOutputStream o=new FileOutputStream(f)){o.write(k);}
            f.setReadable(false,false);f.setReadable(true,true);f.setWritable(false,false);f.setWritable(true,true);
        }
        byte[] k=new byte[32];
        try(FileInputStream in=new FileInputStream(f)){int n=0;while(n<32){int r=in.read(k,n,32-n);if(r<0)break;n+=r;}}
        return new SecretKeySpec(k,"AES");
    }
    private static String seal(Context app,String plain) throws Exception {
        String tag="k";SecretKey key;
        try{key=keystoreKey();}catch(Exception e){key=fileKey(app,true);tag="f";}
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        try{cipher.init(Cipher.ENCRYPT_MODE,key);}
        catch(Exception e){if(!tag.equals("k"))throw e;key=fileKey(app,true);tag="f";cipher.init(Cipher.ENCRYPT_MODE,key);}
        byte[] ct=cipher.doFinal(plain.getBytes("UTF-8"));
        return tag+":"+Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)+":"+Base64.encodeToString(ct,Base64.NO_WRAP);
    }
    private static String unseal(Context app,String blob) throws Exception {
        String[] parts=blob.split(":");
        if(parts.length!=3)throw new IllegalArgumentException("bad blob");
        SecretKey key=parts[0].equals("k")?keystoreKey():fileKey(app,false);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,Base64.decode(parts[1],Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(parts[2],Base64.NO_WRAP)),"UTF-8");
    }
}

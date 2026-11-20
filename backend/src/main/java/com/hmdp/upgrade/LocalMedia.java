package com.hmdp.upgrade;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import javax.imageio.*;
import javax.imageio.stream.ImageInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class LocalMedia {
    final LocalData d;private final LocalLifeConfiguration config;private final LocalAccounts accounts;private final Path root;
    private final java.util.concurrent.Semaphore decodeSlots=new java.util.concurrent.Semaphore(2);
    public LocalMedia(JdbcTemplate db,Transactions tx,ObjectMapper json,LocalLifeConfiguration config,LocalAccounts accounts,@Value("${upgrade.local-life.media-directory:.local/local-life-media}") String directory){d=new LocalData(db,tx,json);this.config=config;this.accounts=accounts;root=Path.of(directory).toAbsolutePath().normalize();}
    private Path file(String key){if(!key.matches("[a-f0-9-]{36}(?:-thumb)?\\.jpg"))throw new Problem(400,"LOCAL_INVALID_IMAGE");Path result=root.resolve(key).normalize();if(!result.startsWith(root))throw new Problem(400,"LOCAL_INVALID_IMAGE");return result;}
    private BufferedImage resize(BufferedImage input,int bound){double scale=Math.min(1,(double)bound/Math.max(input.getWidth(),input.getHeight()));int w=Math.max(1,(int)(input.getWidth()*scale)),h=Math.max(1,(int)(input.getHeight()*scale));var out=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);var g=out.createGraphics();g.setColor(Color.WHITE);g.fillRect(0,0,w,h);g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);g.drawImage(input,0,0,w,h,null);g.dispose();return out;}
    public Object upload(long user,String purpose,MultipartFile source){config.require();if(!decodeSlots.tryAcquire())throw new Problem(429,"MEDIA_BUSY");try{return decode(user,purpose,source);}finally{decodeSlots.release();}}
    private Object decode(long user,String purpose,MultipartFile source){accounts.limit("media:"+user,15);if(!Set.of("AVATAR","REVIEW","CHECKIN","SHOP").contains(purpose))throw new Problem(400,"LOCAL_INVALID_IMAGE");if(purpose.equals("SHOP"))accounts.requireOperator(user);
        if(source.isEmpty()||source.getSize()>5*1024*1024)throw new Problem(413,"MEDIA_TOO_LARGE");BufferedImage image;
        try(InputStream in=source.getInputStream();ImageInputStream stream=ImageIO.createImageInputStream(in)){
            var readers=ImageIO.getImageReaders(stream);if(!readers.hasNext())throw new Problem(400,"LOCAL_INVALID_IMAGE");var reader=readers.next();
            try{String format=reader.getFormatName().toLowerCase(Locale.ROOT);if(!Set.of("jpeg","jpg","png","webp").contains(format))throw new Problem(400,"LOCAL_INVALID_IMAGE");reader.setInput(stream,true,true);int w=reader.getWidth(0),h=reader.getHeight(0);if(w<1||h<1||w>8000||h>8000||(long)w*h>20000000)throw new Problem(413,"MEDIA_TOO_LARGE");image=reader.read(0);}finally{reader.dispose();}
        }catch(Problem known){throw known;}catch(Exception e){throw new Problem(400,"LOCAL_INVALID_IMAGE");}
        String id=LocalData.id();Path original=file(id+".jpg"),thumb=file(id+"-thumb.jpg");
        try{Files.createDirectories(root);ImageIO.write(resize(image,1600),"jpg",original.toFile());ImageIO.write(resize(image,480),"jpg",thumb.toFile());long bytes=Files.size(original)+Files.size(thumb);
            return d.tx.run(()->{d.lockUser(user);Instant today=d.now().atZone(LocalData.ZONE).toLocalDate().atStartOfDay(LocalData.ZONE).toInstant();
                if(d.db.queryForObject("SELECT COUNT(*) FROM ux_media WHERE user_id=? AND created_at>=?",Integer.class,user,LocalData.at(today))>=50||d.db.queryForObject("SELECT COALESCE(SUM(size_bytes),0) FROM ux_media WHERE user_id=? AND deleted_at IS NULL",Long.class,user)+bytes>200L*1024*1024)throw new Problem(429,"MEDIA_QUOTA");
                d.db.update("INSERT INTO ux_media(id,user_id,purpose,content_type,storage_key,size_bytes,width,height,created_at) VALUES(?,?,?,'image/jpeg',?,?,?,?,?)",id,user,purpose,id+".jpg",bytes,image.getWidth(),image.getHeight(),LocalData.at(d.now()));return Map.of("id",id,"url","/v2/media/"+id,"thumbnailUrl","/v2/media/"+id+"?thumbnail=true","purpose",purpose);});
        }catch(RuntimeException e){try{Files.deleteIfExists(original);Files.deleteIfExists(thumb);}catch(IOException ignored){}throw e;}catch(IOException e){throw new Problem(503,"MEDIA_UNAVAILABLE");}
    }
    public void attach(long user,String purpose,String target,java.util.List<String> ids){
        if(ids.size()>12||new HashSet<>(ids).size()!=ids.size())throw new Problem(400,"LOCAL_INVALID_IMAGE");
        for(String id:ids){var rows=d.rows("SELECT * FROM ux_media WHERE id=? AND user_id=? AND purpose=? AND deleted_at IS NULL FOR UPDATE",id,user,purpose);if(rows.isEmpty())throw new Problem(400,"LOCAL_INVALID_IMAGE");var m=rows.get(0);if(m.get("targetId")!=null&&!target.equals(m.get("targetId")))throw new Problem(409,"MEDIA_ALREADY_USED");}
        d.db.update("UPDATE ux_media SET target_id=NULL WHERE user_id=? AND purpose=? AND target_id=?",user,purpose,target);
        for(String id:ids)d.db.update("UPDATE ux_media SET target_id=? WHERE id=?",target,id);
    }
    public Object avatar(long user,String id){config.require();return d.tx.run(()->{d.lockUser(user);attach(user,"AVATAR",Long.toString(user),java.util.List.of(id));
        d.db.update("INSERT INTO ux_local_profile(user_id,bio,avatar_id,role,updated_at) VALUES(?,'',?,'CUSTOMER',?) ON DUPLICATE KEY UPDATE avatar_id=VALUES(avatar_id),updated_at=VALUES(updated_at)",user,id,LocalData.at(d.now()));return accounts.profile(user);});}
    public byte[] read(String id,Long viewer,boolean thumbnail){config.require();var m=d.one("SELECT * FROM ux_media WHERE id=? AND deleted_at IS NULL",id);long owner=LocalData.number(m.get("userId"));boolean allowed=viewer!=null&&viewer==owner;Object target=m.get("targetId");
        if(!allowed&&target!=null)allowed=switch(m.get("purpose").toString()){
            case "AVATAR" -> d.db.queryForObject("SELECT COUNT(*) FROM ux_local_profile WHERE avatar_id=?",Integer.class,id)>0;
            case "REVIEW" -> d.db.queryForObject("SELECT COUNT(*) FROM ux_review WHERE id=? AND state='PUBLISHED'",Integer.class,target)>0;
            case "SHOP" -> d.db.queryForObject("SELECT COUNT(*) FROM ux_local_shop WHERE shop_id=? AND status='PUBLISHED'",Integer.class,target)>0;
            default -> false;
        };if(!allowed)throw new Problem(404,"LOCAL_NOT_FOUND");
        try{return Files.readAllBytes(file(id+(thumbnail?"-thumb":"")+".jpg"));}catch(IOException e){throw new Problem(404,"LOCAL_NOT_FOUND");}
    }
    @Scheduled(fixedDelay=3600000) public void cleanup(){if(!config.enabled)return;
        // Lock each row before deleting; an in-flight publication either binds it first or observes deletion.
        for(String id:d.db.queryForList("SELECT id FROM ux_media WHERE target_id IS NULL AND deleted_at IS NULL AND created_at<? LIMIT 100",String.class,LocalData.at(d.now().minusSeconds(86400)))){
            d.tx.run(()->{var rows=d.rows("SELECT * FROM ux_media WHERE id=? FOR UPDATE",id);if(rows.isEmpty()||rows.get(0).get("targetId")!=null)return null;
                try{Files.deleteIfExists(file(id+".jpg"));Files.deleteIfExists(file(id+"-thumb.jpg"));}catch(IOException e){return null;}
                d.db.update("UPDATE ux_media SET deleted_at=? WHERE id=?",LocalData.at(d.now()),id);return null;});}
    }
}

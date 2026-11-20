package com.hmdp.upgrade;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Shared explicit conversion for the small local-life domain; never accepts SQL from clients. */
final class LocalData {
    static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    final JdbcTemplate db;final Transactions tx;final ObjectMapper json;
    LocalData(JdbcTemplate db,Transactions tx,ObjectMapper json){this.db=db;this.tx=tx;this.json=json;}
    Instant now(){return db.queryForObject("SELECT CURRENT_TIMESTAMP(3)",Timestamp.class).toInstant();}
    static Timestamp at(Instant value){return Timestamp.from(value);}
    static String id(){return UUID.randomUUID().toString();}
    String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("Invalid data",e);}}
    List<String> strings(Object text){try{return text==null?List.of():json.readValue(text.toString(),json.getTypeFactory().constructCollectionType(List.class,String.class));}catch(Exception e){throw new Problem(500,"LOCAL_DATA_INVALID");}}
    List<Map<String,Object>> rows(String sql,Object... args){return db.query(sql,(r,n)->map(r),args);}
    Map<String,Object> one(String sql,Object...args){var result=rows(sql,args);if(result.isEmpty())throw new Problem(404,"LOCAL_NOT_FOUND");return result.get(0);}
    static Map<String,Object> map(ResultSet r)throws SQLException{
        Map<String,Object> out=new LinkedHashMap<>();var meta=r.getMetaData();
        for(int i=1;i<=meta.getColumnCount();i++){
            String raw=meta.getColumnLabel(i).toLowerCase(Locale.ROOT);StringBuilder key=new StringBuilder();boolean upper=false;
            for(char c:raw.toCharArray()){if(c=='_')upper=true;else{key.append(upper?Character.toUpperCase(c):c);upper=false;}}
            Object value=r.getObject(i);
            if(value instanceof Timestamp timestamp)value=timestamp.toInstant();
            else if(value instanceof java.sql.Date date)value=date.toLocalDate();
            else if(value instanceof Clob clob)value=clob.getSubString(1,(int)clob.length());
            else if(value instanceof Number && (raw.equals("id")||raw.endsWith("_id")))value=value.toString();
            out.put(key.toString(),value);
        }return out;
    }
    static String text(Object value,int min,int max){String s=value==null?"":value.toString().trim();if(s.length()<min||s.length()>max||s.chars().anyMatch(c->Character.isISOControl(c)&&c!='\n'&&c!='\t'))throw new Problem(400,"LOCAL_INVALID_INPUT");return s;}
    static int integer(Object value,int min,int max){try{int n=new java.math.BigDecimal(value.toString()).intValueExact();if(n<min||n>max)throw new Exception();return n;}catch(Exception e){throw new Problem(400,"LOCAL_INVALID_INPUT");}}
    static long number(Object value){try{long n=Long.parseLong(value.toString());if(n<=0)throw new Exception();return n;}catch(Exception e){throw new Problem(400,"LOCAL_INVALID_INPUT");}}
    static long version(Map<String,Object> body){return number(body.get("version"));}
    void lockUser(long user){db.queryForObject("SELECT id FROM ux_account WHERE id=? FOR UPDATE",Long.class,user);}
    void audit(long operator,String action,String target,String summary){db.update("INSERT INTO ux_local_audit VALUES(?,?,?,?,?,?)",id(),operator,action,target,text(summary,0,1000),at(now()));}
}

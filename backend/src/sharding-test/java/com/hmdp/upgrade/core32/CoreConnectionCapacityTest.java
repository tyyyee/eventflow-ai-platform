package com.hmdp.upgrade.core32;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.*;
import java.util.*;
import java.nio.file.*;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

/** Server admission, not application TPS: hold actual idle connections until the single instance is full. */
@EnabledIfEnvironmentVariable(named="CORE32_CAPACITY",matches="true")
class CoreConnectionCapacityTest {
    record Held(List<Connection> connections,Map<String,Object> result)implements AutoCloseable {
        public void close()throws SQLException{for(var c:connections)c.close();}
    }
    static Held hold(List<Integer> ports,int wanted)throws Exception{
        var connections=new ArrayList<Connection>();var failures=new ArrayList<Map<String,Object>>();var perPort=new TreeMap<Integer,Integer>();
        try{
            for(int i=0;i<wanted;i++){int port=ports.get(i%ports.size());try{var c=DriverManager.getConnection(CoreCapacityBenchmarkTest.url(port),"qupiao",System.getenv("CORE32_PASSWORD"));connections.add(c);perPort.merge(port,1,Integer::sum);}catch(SQLException exhausted){failures.add(Map.of("port",port,"errorCode",exhausted.getErrorCode(),"sqlState",Objects.toString(exhausted.getSQLState(),"")));}}
            var limits=new TreeMap<Integer,Integer>();for(var c:connections)try(var s=c.createStatement();var rows=s.executeQuery("SELECT @@port,@@max_connections")){rows.next();limits.put(rows.getInt(1),rows.getInt(2));}
            return new Held(connections,Map.of("wanted",wanted,"opened",connections.size(),"failures",failures,"perPort",perPort,"serverLimits",limits));
        }catch(Exception failure){for(var c:connections)c.close();throw failure;}
    }
    @Test void fourInstancesAdmit45ConnectionsWhileOneFortyConnectionInstanceRejectsOverflow()throws Exception{
        var result=new LinkedHashMap<String,Object>();result.put("scope","Actual database connection admission with application and benchmark pools stopped. This intentionally adds instances and aggregate server connection capacity, not an equal-total-resource TPS comparison.");
        try(var single=hold(List.of(33307),45)){result.put("single",single.result());assertTrue(single.connections().size()<45);@SuppressWarnings("unchecked")var failures=(List<Map<String,Object>>)single.result().get("failures");assertTrue(failures.stream().allMatch(f->((Number)f.get("errorCode")).intValue()==1040),failures.toString());}
        try(var four=hold(List.of(33400,33401,33402,33403),45)){result.put("four",four.result());assertEquals(45,four.connections().size());}
        result.put("verifiedAt",Instant.now().toString());var destination=Path.of("../docs/verification/2026-10-27-core32-capacity/connections.json");Files.createDirectories(destination.getParent());new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(destination.toFile(),result);
    }
}

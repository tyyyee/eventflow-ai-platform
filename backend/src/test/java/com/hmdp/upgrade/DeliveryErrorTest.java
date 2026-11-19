package com.hmdp.upgrade;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class DeliveryErrorTest {
    Trading trading=mock(Trading.class);Transactions tx=mock(Transactions.class);JdbcTemplate db=mock(JdbcTemplate.class);
    OrderMessages messages=mock(OrderMessages.class);
    Delivery delivery=new Delivery(db,tx,trading,messages,new ObjectMapper(),mock(Admission.class),mock(Reservations.class),"test",false);
    OrderMessages.Received message(String key,String body){return new OrderMessages.Received("test","broker-a",0,1,key,body,System.currentTimeMillis());}
    OrderMessages.Received valid(){return message("1","{\"schemaVersion\":1,\"eventId\":\"e\",\"requestId\":\"r\",\"activityId\":1}");}
    @Test void committedOrderReturnsSuccessWithoutCallingRedisRepair()throws Exception{
        var reservations=mock(Reservations.class);var admission=mock(Admission.class);
        var consumer=new Delivery(db,tx,trading,messages,new ObjectMapper(),admission,reservations,"test",false);
        when(trading.process(any())).thenReturn(new Trading.Request("r",1,1,"hash","REJECTED","SOLD_OUT",java.time.Instant.now()));
        doThrow(new DataAccessResourceFailureException("redis offline")).when(admission).soldOut(1);
        consumer.consume(valid());verifyNoInteractions(reservations);verifyNoInteractions(db);
    }
    @Test void databaseFailurePropagatesForBrokerRetry(){
        doThrow(new DataAccessResourceFailureException("offline")).when(trading).process(any());
        assertThrows(DataAccessResourceFailureException.class,()->delivery.consume(valid()));
    }
    @Test void poisonPersistenceFailurePropagatesForBrokerRetry(){
        when(tx.run(any())).thenThrow(new DataAccessResourceFailureException("offline"));
        assertThrows(DataAccessResourceFailureException.class,()->delivery.consume(message("1","bad")));verifyNoInteractions(trading);
    }
    @Test void unknownSchemaDoesNotReachTrading()throws Exception{
        delivery.consume(message("1",valid().body().replace("\"schemaVersion\":1","\"schemaVersion\":99")));
        verifyNoInteractions(trading);verify(tx).run(any());
    }
    @Test void messageKeyMismatchDoesNotReachTrading()throws Exception{
        delivery.consume(message("2",valid().body()));verifyNoInteractions(trading);verify(tx).run(any());
    }
}

package com.hmdp.upgrade.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.upgrade.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import static com.hmdp.upgrade.assistant.AssistantData.*;

class AssistantTestSupport {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final JdbcTemplate db;
    final Transactions tx;
    final CustomerRequests requests;
    final AssistantCatalog catalog;
    final AssistantTools tools;
    final AssistantService service;
    final LocalDate tomorrow=LocalDate.now(ZONE).plusDays(1);
    AssistantTestSupport(){this(null);}
    AssistantTestSupport(AssistantModel supplied){
        var source=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE","sa","");
        var scripts=List.of("V1__upgrade.sql","V2__customer_sessions_and_storefront.sql","V4__planning.sql","V17__ticket_assistant.sql");
        new ResourceDatabasePopulator(scripts.stream().map(s->new ClassPathResource("db/migration/"+s)).toArray(ClassPathResource[]::new)).execute(source);
        db=new JdbcTemplate(source);tx=new Transactions(new DataSourceTransactionManager(source));
        // H2 does not support the MySQL multi-column ALTER in V5/V6. Only V6's notice is used by these component tests.
        db.execute("ALTER TABLE ux_storefront ADD COLUMN service_notice VARCHAR(500) DEFAULT '' NOT NULL");
        db.update("INSERT INTO ux_account(id,username,display_name,password_hash,created_at) VALUES(101,'alice','Alice','unused',CURRENT_TIMESTAMP),(202,'bob','Bob','unused',CURRENT_TIMESTAMP)");
        requests=new CustomerRequests(db,org.mockito.Mockito.mock(Trading.class));catalog=new AssistantCatalog(db);
        var orderReads=new AssistantOrders(){public CustomerRequests.Listing list(long user,String filter){return requests.readOnlyList(user,filter);}public CustomerRequests.Detail detail(long user,String id){return requests.readOnlyDetail(user,id);}};
        tools=new AssistantTools(catalog,orderReads,json);
        service=new AssistantService(db,tx,json,new AssistantRuntime(supplied==null?new DemoAssistantModel(json):supplied,tools,json));
        seed(1,"主校区 · 礼堂","音乐演出",5000);seed(2,"主校区 · 草坪","音乐演出",8000);seed(3,"东校区 · 体育场","音乐演出",4000);seed(4,"主校区 · 小剧场","戏剧舞台",2000);
    }
    void seed(long id,String campus,String category,int price){
        Instant now=Instant.now();
        db.update("INSERT INTO ux_shop VALUES(?,?,?,1)",id,"校园活动"+id,"已发布活动介绍");
        db.update("INSERT INTO ux_storefront(shop_id,category,area,address,image_path,published) VALUES(?,?,?,?,?,TRUE)",id,category,campus,campus+"地址","/event-assets/stage.svg");
        db.update("INSERT INTO ux_activity VALUES(?,?,?,?,?,?,?)",id,100,100,price,Timestamp.from(now.minusSeconds(60)),Timestamp.from(now.plusSeconds(7200)),Timestamp.from(now.plusSeconds(7500)));
        db.update("INSERT INTO ux_offer VALUES(?,?,?,?,?)",id,id,"活动票",price,"同一用户同一活动限购一次；5分钟内支付。");
        db.update("INSERT INTO ux_experience_session(id,shop_id,title,tags,starts_at,ends_at,price_cents,capacity,available,published,activity_id) VALUES(?,?,?,?,?,?,?,?,?,TRUE,?)",
            id,id,"校园活动"+id+"晚间场",category,Timestamp.from(tomorrow.atTime(19,0).atZone(ZONE).toInstant()),Timestamp.from(tomorrow.atTime(20,0).atZone(ZONE).toInstant()),999999,20,0,id);
    }
    Run turn(String session,String message){return turn(service,session,message);}
    Run turn(AssistantService svc,String session,String message){
        var accepted=svc.submit(101,session,UUID.randomUUID().toString(),message);svc.workOne();return svc.get(101,session,accepted.id());
    }
    void request(long user,String id,String state,String orderState,Instant deadline){
        Instant now=Instant.now();
        db.update("INSERT INTO ux_request VALUES(?,?,?,?,?,?,NULL,?,?,NULL)",id,user,"key_"+id,1,"h",state,Timestamp.from(now),Timestamp.from(now.plusSeconds(100)));
        if(orderState!=null)db.update("INSERT INTO ux_order VALUES(?,?,?,?,?,?,?,?)","o_"+id,id,1,user,5000,orderState,Timestamp.from(deadline),Timestamp.from(now));
    }
}

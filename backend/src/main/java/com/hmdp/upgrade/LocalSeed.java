package com.hmdp.upgrade;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/** Explicit, insert-only fictional fixtures. Restarting never restores consumed coupons or edited shops. */
@Component
@ConditionalOnProperty(name="upgrade.local-life.demo-data",havingValue="true")
public class LocalSeed implements ApplicationRunner {
    final LocalData d;private final LocalLifeConfiguration config;private final ShopBloom bloom;private final LocalCoupons coupons;
    private final String password,operatorPassword;
    public LocalSeed(JdbcTemplate db,Transactions tx,ObjectMapper json,LocalLifeConfiguration config,ShopBloom bloom,LocalCoupons coupons,
        @Value("${LOCAL_LIFE_DEMO_PASSWORD:}") String password,@Value("${LOCAL_LIFE_OPERATOR_PASSWORD:}") String operatorPassword){d=new LocalData(db,tx,json);this.config=config;this.bloom=bloom;this.coupons=coupons;this.password=password;this.operatorPassword=operatorPassword;}
    @Override public void run(ApplicationArguments args){config.require();seed();}
    void seed(){
        if(!password.isEmpty())Accounts.password(password);if(!operatorPassword.isEmpty())Accounts.password(operatorPassword);
        var encoder=new BCryptPasswordEncoder(12);String userHash=password.isEmpty()?null:encoder.encode(password),opHash=operatorPassword.isEmpty()?null:encoder.encode(operatorPassword);
        String[] names={"饭团同学","小林不加糖","周末出走","南风","阿白","柚子","麦芽","小满","橘子汽水","木木","一颗豆子","蓝莓","夏日来信","陈小鱼","云朵","晚风","团子","小周","桃桃","阿寻"};
        for(int i=0;i<20;i++){long id=60001+i;if(d.db.queryForObject("SELECT COUNT(*) FROM ux_account WHERE id=?",Integer.class,id)==0)d.db.update("INSERT INTO ux_account(id,username,display_name,password_hash,created_at) VALUES(?,?,?,?,?)",id,"sample_"+(i+1),names[i],"!disabled-fictional-profile",LocalData.at(d.now()));}
        if(userHash!=null){seedAccount(60101,"campus_demo","同学你好",userHash,"CUSTOMER");seedAccount(60102,"campus_friend","周末搭子",userHash,"CUSTOMER");}
        if(opHash!=null)seedAccount(60103,"campus_operator","生活运营",opHash,"OPERATOR");
        String[][] shops={
            {"晚风小馆","美食","家常菜也可以很有仪式感，课后和朋友吃一顿热乎的饭。","steak","聚餐,家常菜,不辣选项","3800"},
            {"纸飞机咖啡","咖啡茶饮","图书馆旁的一处安静角落，手冲、拿铁与刚烤好的小点心。","coffee","适合自习,咖啡,安静","2800"},
            {"巷口火锅","火锅","放学后围坐一桌，鸳鸯锅底兼顾不同口味。","hotpot","聚餐,鸳鸯锅,不辣选项","5900"},
            {"白桃烘焙","甜品烘焙","每天小批量出炉的面包和甜点，路过就闻到黄油香。","family-craft","面包,甜点,外带","2600"},
            {"周末桌游","休闲娱乐","约上同学一起玩桌游，新手可查看入门说明。","gallery","桌游,朋友聚会,室内","4500"},
            {"橙子攀岩","运动体验","给下课后的身体一点挑战，基础体验含安全讲解。","climbing","运动,体验,室内","6900"},
            {"禾田小食堂","美食","轻食、米饭和当季蔬菜，每一份都是简单踏实的午餐。","light","轻食,蔬菜,午餐","3200"},
            {"慢慢来茶所","咖啡茶饮","茶香和新鲜水果的组合，适合午后的一小段休息。","coffee","茶饮,外带,午后","1800"},
            {"山野菌汤锅","火锅","以清淡菌汤为特色，喜欢不辣口味的同学可以看看。","hotpot","菌汤,不辣选项,聚餐","5500"},
            {"一口云朵","甜品烘焙","松软蛋糕与季节水果，提供小份甜品选择。","family-craft","蛋糕,水果,甜品","3500"},
            {"留白手作间","休闲娱乐","陶艺、绘画和手作小体验，把周末留给一件慢慢完成的作品。","gallery","手作,安静,体验","7800"},
            {"向上运动馆","运动体验","室内基础运动和轻量训练体验，课后也能动起来。","climbing","运动,室内,训练","4900"},
            {"青柠泰食","美食","东南亚风味简餐，酸甜清爽，午间有单人套餐。","thai","泰式,简餐,午餐","4800"},
            {"日落拿铁","咖啡茶饮","大窗边的下午和一杯拿铁，适合聊天或短暂阅读。","coffee","咖啡,阅读,窗边","3000"},
            {"围炉小火锅","火锅","单人小锅也能热气腾腾，清汤和麻辣两种选择。","hotpot","单人锅,清汤,晚餐","4200"},
            {"麦香日记","甜品烘焙","软欧包和早餐面包，小预算也能带走一袋香气。","family-craft","早餐,面包,外带","2200"},
            {"像素游戏社","休闲娱乐","桌面游戏与朋友聚会的空间，不同人数可选择不同游戏。","gallery","游戏,聚会,室内","3800"},
            {"湖畔羽球馆","运动体验","室内羽毛球体验，器材借用和场地规则请先查看说明。","climbing","羽毛球,室内,运动","3600"},
            {"芝士厨房","美食","热乎的焗饭与意面，适合忙完课程后犒劳自己。","steak","意面,焗饭,聚餐","4500"},
            {"林间手冲","咖啡茶饮","以手冲咖啡和豆子风味为主题的小店，座位不多。","coffee","手冲,安静,咖啡","3300"},
            {"热辣同学","火锅","麻辣锅底是招牌，也提供鸳鸯搭配；食材按菜单实际供应。","hotpot","麻辣,聚餐,鸳鸯锅","6500"},
            {"酥酥小屋","甜品烘焙","酥皮点心和小蛋挞，适合下午课间分享。","family-craft","蛋挞,点心,外带","1900"},
            {"光影自习室","休闲娱乐","带阅读角的学习空间，安静和明亮是这里的关键词。","gallery","自习,安静,阅读","2500"},
            {"轻盈瑜伽","运动体验","基础伸展和呼吸练习，适合初次体验的同学。","spa","伸展,入门,室内","7900"},
            {"落日烧鸟","美食","放学后的烤物与热饭，约朋友聊聊今天的新鲜事。","steak","烤物,聚餐,晚餐","5800"},
            {"青禾茶铺","咖啡茶饮","清爽茶饮和日常小食，门口设有外带取餐区。","coffee","茶饮,外带,小食","1600"},
            {"暖冬砂锅","火锅","砂锅慢炖与热汤，清淡口味也有多种选择。","hotpot","砂锅,不辣选项,热汤","3900"},
            {"半糖研究所","甜品烘焙","提供不同甜度风格的甜品，具体糖分请以店内信息为准。","family-craft","甜品,下午茶,分享","2900"},
            {"拾光展览角","休闲娱乐","小型作品展示和创意交流，适合周末慢慢逛。","gallery","艺术,展览,安静","2000"},
            {"追风运动站","运动体验","校园周边的运动集合点，周末体验规则请先查看。","climbing","运动,周末,体验","5200"}
        };
        Instant now=d.now();String batch=now.atZone(LocalData.ZONE).toLocalDate().toString();
        for(int i=0;i<shops.length;i++){
            long id=32001+i;var s=shops[i];String campus=List.of("主校区","东校区","南校区").get((i/6+i%3)%3);var landmark=LocalShops.LANDMARKS.get(((i/6+i%3)%3)*2);String seed="local-shop-v1-"+i;
            if(d.db.queryForObject("SELECT COUNT(*) FROM ux_local_shop WHERE seed_key=?",Integer.class,seed)==0){
                var hours=new ArrayList<Map<String,Object>>();for(int day=1;day<=7;day++)hours.add(Map.of("day",day,"start",s[1].equals("咖啡茶饮")?"08:00":"10:00","end",i%7==0?"02:00":"22:00"));
                final int index=i; d.tx.run(()->{bloom.beforeInsert(id);d.db.update("INSERT INTO ux_shop VALUES(?,?,?,1)",id,s[0],s[2]);
                d.db.update("INSERT INTO ux_local_shop(shop_id,category,campus,area,address,latitude,longitude,avg_price_cents,hours_json,closed_dates_json,tags_json,photos_json,cover_path,status,priority,seed_key,updated_at) VALUES(?,?,?,?,?,?,?,?,?,'[]',?,?,?,'PUBLISHED',?,?,?)",id,s[1],campus,List.of("大学路","书院街","青年广场","湖畔街区").get(index%4),campus+"周边·大学路 "+(18+index*3)+" 号（虚构地址）",((Number)landmark.get("latitude")).doubleValue()+(index%5-2)*0.002,((Number)landmark.get("longitude")).doubleValue()+(index%4-1)*0.003,Integer.parseInt(s[5]),d.encode(hours),d.encode(Arrays.asList(s[4].split(","))),d.encode(List.of("/local-assets/shop-"+(index+1)+".svg","/local-assets/interior-"+(index+1)+".svg","/local-assets/menu-"+(index%15+1)+".svg")),"/local-assets/shop-"+(index+1)+".svg",30-index,seed,LocalData.at(now));return null;});
            }
            for(int r=0;r<6;r++){String rseed="local-review-v1-"+i+"-"+r;if(d.db.queryForObject("SELECT COUNT(*) FROM ux_review WHERE seed_key=?",Integer.class,rseed)>0)continue;
                String[] texts={"和同学课后来了一次，整体体验不错。环境比较舒服，价格在学生预算内，下次想再试试别的选择。","位置离学校不远，介绍里的营业信息很清楚。今天人不多，适合和朋友一起放松一下。","整体符合预期，不过热门时间可能需要耐心一点。图片和文字是演示内容，可以试试发布自己的评价。","喜欢这里的氛围，细节还有提升空间。建议先查看店铺的适用规则再领取优惠券。","第一次来这种类型的店，体验比较新鲜。和朋友一起会更有意思，预算可以提前规划。","周末顺路发现的小店，整体比较方便。具体口味和服务每个人感受不同，大家可以自己体验。"};
                d.db.update("INSERT INTO ux_review(id,user_id,shop_id,rating,body,media_json,state,active_slot,is_seed,seed_key,created_at,updated_at) VALUES(?,?,?,?,?,'[]','PUBLISHED',1,TRUE,?,?,?)",LocalData.id(),60001+(i+r)%20,id,5-((i/4)%3==0?r%3:(i/4)%3==1?r%2:r%4),texts[r],rseed,LocalData.at(now.minusSeconds((i*6L+r+1)*7200)),LocalData.at(now));
            }
            if(i<20){String cseed="local-coupon-"+batch+"-"+i;if(d.db.queryForObject("SELECT COUNT(*) FROM ux_coupon_template WHERE seed_key=?",Integer.class,cseed)==0){
                int minimum=10000,discount=i%3==0?2000:1000;String state=i==16?"PAUSED":i==18?"ENDED":"PUBLISHED";int remaining=i==17?1:50;String templateId=LocalData.id();
                d.db.update("INSERT INTO ux_coupon_template(id,shop_id,title,minimum_cents,discount_cents,capacity,remaining,claim_from,claim_until,valid_from,valid_until,valid_days,terms,excluded_dates_json,state,seed_key,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,7,?,'[]',?,?,?)",templateId,id,"到店满100减"+(discount/100),minimum,discount,remaining,remaining,LocalData.at(i==19?now.plusSeconds(86400):now.minusSeconds(3600)),LocalData.at(now.plusSeconds(7*86400)),LocalData.at(now.minusSeconds(3600)),LocalData.at(now.plusSeconds(14*86400)),"免费领取，满100元可用；每人每个模板限领一张，不叠加、不找零。领取后7天有效且不超过券面截止时间，仅供本地模拟核销，不涉及真实资金。",state,cseed,LocalData.at(now));if(i==17)coupons.claim(60001,templateId);}
            }
        }
        String[][] faq={
            {"券","怎么领优惠券","领取,领券","登录后进入商铺详情，查看券的领取时间和条件，点击领取。重复领取会返回已有券，每个模板每人限一张。"},
            {"券","满减门槛怎么算","满减,门槛,金额","按实际输入的消费金额判断门槛。例如满100减20，消费120元时模拟实付100元。一次使用一张，不叠加，不找零。"},
            {"券","券过期后还能用吗","过期,有效期","券的有效时间为开始时刻包含、截止时刻不包含；达到截止时间即不可使用，不依赖后台扫描完成。"},
            {"券","为什么领不到券","领不到,领完,售罄","可能尚未开始、发放结束、暂停发券、库存已领完或店铺下架。请以当前模板状态和券包记录为准。"},
            {"券","已领券暂停后还能用吗","暂停,已领","暂停发放只影响新的领取，已领取的券按领取时规则快照使用；作废时另有明确原因。"},
            {"券","怎么到店核销","核销,使用券","本平台演示由运营输入券码和消费金额，预览后确认核销。成功后券包显示已使用，不发生真实扣款。"},
            {"券","券可以转赠吗","转赠,转让","首版不支持转赠，领取记录属于当前账号。不要向无关人员分享核销码。"},
            {"券","可以叠加两张券吗","叠加,两张券","首版每次消费只支持一张免费满减券，不叠加；不能抵扣校园活动门票。"},
            {"券","作废会退款吗","作废,免费券退款","首版优惠券免费领取，不存在购买款退款；作废原因会显示在本人券包。"},
            {"订单","抢票已受理是什么意思","受理,处理中","已受理表示请求已持久化、等待后台建单，不代表出票或支付成功。可到我的订单查询结果。"},
            {"订单","待支付还有多久","待支付,倒计时","活动订单创建后通常有5分钟模拟支付期限，具体以订单返回的截止时间为准，页面倒计时不是最终裁决。"},
            {"订单","网络断开怎么办","网络,超时,重试","请求响应丢失时先查询结果或使用原请求标识重试，不能仅凭网络报错判断建单失败。"},
            {"订单","取消后能重新抢吗","重新抢,限购,取消","现有票务规则是同一票档每人限购一单，订单取消或过期后仍不能再次购买同一票档。"},
            {"订单","候补一定成功吗","候补,补位","候补会在票额释放后尝试补位，不保证成功或严格顺序，补位成功后仍需在期限内支付。"},
            {"订单","库存数字能保证买到吗","库存,余票","页面余票是查询时快照，并发预占可能改变实际可售量。是否受理由提交时业务校验决定。"},
            {"支付","支付是真实扣款吗","真实支付,扣款,模拟","项目仅提供模拟支付和模拟核销，不连接真实资金渠道。页面明确标注模拟操作。"},
            {"支付","关闭后收到付款怎么办","迟到付款,关闭后付款","后台根据付款记录生成退款任务并重试，退款进度以本人付款和退款记录为准，不能只看订单已关闭就认定已退款。"},
            {"支付","退款是否已经完成","退款,退钱","需要查询本人付款和退款记录；无付款记录、退款处理中和已完成退款是不同情况，不能由助手推测。"},
            {"支付","重复点击会重复扣吗","重复支付,重复通知","模拟付款回执和退款执行具备幂等记录。同一业务重复通知不应产生重复业务效果，查询结果以服务端为准。"},
            {"账号","如何修改密码","改密,修改密码","在个人中心输入旧密码并设置新密码，成功后所有旧会话失效，需要重新登录。"},
            {"账号","忘记密码怎么办","忘记密码,恢复码","使用用户名和已保存的恢复码重置密码。恢复码只能使用一次，重置成功后旧会话和旧恢复码失效。"},
            {"账号","恢复码丢失怎么办","丢失恢复码,找回","已登录且记得当前密码时可以重新生成恢复码。没有当前密码或有效恢复码，不能仅凭用户名直接重置。"},
            {"账号","收藏会同步吗","收藏,换设备","登录后的商铺和活动收藏保存在账号下，换设备登录可查询。旧浏览器活动收藏需要本人主动导入。"},
            {"账号","账号切换后会串数据吗","切换账号,别人订单","订单、券包、收藏和助手会话按登录身份隔离。切换账号会清除前一账号页面状态。"},
            {"社区","签到和到店打卡有什么区别","签到,打卡","每日签到记录当天使用平台；探店打卡是自主记录，不代表通过GPS核验，也不会自动增加店铺评分。"},
            {"社区","如何写评价","评价,评论","登录后可写1到5分、10到1000字的体验，最多6张图。同用户同店只有一条有效评价，可以修改或删除。"},
            {"社区","评分从哪里来","评分,星级","店铺评分由当前公开评价计算，隐藏和删除不计入；样例评价带演示标识，不能代表真实商家口碑。"},
            {"社区","为什么评价被隐藏","隐藏,举报","运营可能根据举报隐藏评价并说明原因。作者修改后仍保持隐藏，需运营恢复，不能用编辑绕过处理。"},
            {"社区","点赞和收藏区别","点赞,爱心","点赞表达对评价的认可；收藏用于保存商铺或活动。自己不能给自己的评价点赞，重复操作不会重复计数。"},
            {"社区","需要帮助怎么反馈","客服,反馈,投诉","可在我的反馈提交问题，也可让助手生成草稿后由本人确认提交。反馈等待运营处理，不等于实时接通真人。"}
        };
        for(int i=0;i<faq.length;i++){String key="local-faq-v1-"+i;if(d.db.queryForObject("SELECT COUNT(*) FROM ux_policy WHERE seed_key=?",Integer.class,key)>0)continue;var f=faq[i];d.db.update("INSERT INTO ux_policy(id,title,category,keywords,body,state,effective_at,seed_key,created_at) VALUES(?,?,?,?,?,'PUBLISHED',?,?,?)",LocalData.id(),f[1],f[0],f[2],f[3],LocalData.at(now),key,LocalData.at(now));}
    }
    private void seedAccount(long id,String username,String name,String hash,String role){if(d.db.queryForObject("SELECT COUNT(*) FROM ux_account WHERE id=? OR username=?",Integer.class,id,username)>0)return;d.tx.run(()->{d.db.update("INSERT INTO ux_account(id,username,display_name,password_hash,created_at) VALUES(?,?,?,?,?)",id,username,name,hash,LocalData.at(d.now()));d.db.update("INSERT INTO ux_local_profile(user_id,role,updated_at) VALUES(?,?,?)",id,role,LocalData.at(d.now()));return null;});}
}

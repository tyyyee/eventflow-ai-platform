package com.hmdp.upgrade.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.upgrade.Problem;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static com.hmdp.upgrade.assistant.AssistantData.*;

/** Paid mode is opt-in. Each business case has its own synthetic H2 schema and conversation. */
class AssistantEvaluationTest {
    @TestFactory Stream<DynamicTest> deterministicCases(){
        EvaluationBatch batch=new EvaluationBatch("deterministic",null);
        return AssistantEvaluationCases.all().stream().map(c->DynamicTest.dynamicTest(c.id()+" "+c.name(),()->batch.execute(c)));
    }
}

@EnabledIfEnvironmentVariable(named="ASSISTANT_LIVE_EVAL",matches="true")
class AssistantLiveEvaluationTest {
    @TestFactory Stream<DynamicTest> liveCases(){
        String key=System.getenv().getOrDefault("DEEPSEEK_API_KEY","");
        assertFalse(key.isBlank(),"Set API key in environment; never in source");
        EvaluationBatch batch=new EvaluationBatch("deepseek",key);
        Set<String> selected=new HashSet<>(Arrays.asList(System.getenv().getOrDefault("ASSISTANT_EVAL_IDS","").split(",")));
        var cases=AssistantEvaluationCases.all().stream().filter(AssistantEvaluationCases.Case::liveEligible)
            .filter(c->selected.contains("")||selected.contains(c.id())).toList();
        assertFalse(cases.isEmpty(),"No matching live cases");
        assertTrue(selected.contains("")||cases.size()==selected.size(),"Unknown or ineligible case ID");
        return cases.stream().map(c->DynamicTest.dynamicTest(c.id()+" "+c.name(),()->batch.execute(c)));
    }
}

final class EvaluationBatch {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final String mode,key,model=System.getenv().getOrDefault("ASSISTANT_MODEL","deepseek-flash");
    final List<Map<String,Object>> results=new ArrayList<>(),calls=new ArrayList<>();
    final Instant started=Instant.now();
    final Path output;
    long tokens;boolean usageUnknown;int modelCalls;
    EvaluationBatch(String mode,String key){
        this.mode=mode;this.key=key;
        if(mode.equals("deepseek")&&!model.equals("deepseek-flash"))throw new IllegalArgumentException("Budgeted live evaluation requires deepseek-flash");
        Path root=Path.of("..").toAbsolutePath().normalize();
        Path evidence=root.resolve("docs/verification/2026-11-09-assistant-evaluation");
        String label=System.getenv().getOrDefault("ASSISTANT_EVAL_LABEL",mode+"-"+started.toEpochMilli());
        if(!label.matches("[a-zA-Z0-9_-]{1,80}"))throw new IllegalArgumentException("Invalid evaluation label");
        output=evidence.resolve(label+".json");
        if(Files.exists(output))throw new IllegalStateException("Use a new evaluation label; existing evidence is never overwritten");
    }
    AssistantModel liveModel(String caseId){
        // Only the official endpoint is used by this paid evaluator, regardless of developer shell overrides.
        AssistantModel delegate=new DeepSeekAssistantModel(json,key,"https://api.deepseek.com",model,false);
        return new AssistantModel(){
            public String mode(){return "deepseek";}
            public ModelAnswer next(Checkpoint cp,String request,Instant now,long remaining){
                // Sequential batch; reserve a conservative 50k-token equivalent before admitting another call.
                // This is an evaluation stop policy, not a production RMB billing guarantee.
                if(usageUnknown||modelCalls>=90||(tokens+50000)*8.0/1_000_000>2.5)
                    throw new Problem(503,"EVALUATION_BATCH_BUDGET");
                modelCalls++;long began=System.nanoTime();Map<String,Object> record=new LinkedHashMap<>();
                record.put("caseId",caseId);record.put("call",modelCalls);calls.add(record);
                try{
                    ModelAnswer answer=delegate.next(cp,request,now,remaining);tokens+=answer.tokens();
                    record.put("tokens",answer.tokens());record.put("status","RETURNED");return answer;
                }catch(RuntimeException failure){usageUnknown=true;record.put("status","UNKNOWN_USAGE");throw failure;}
                finally{record.put("elapsedMs",(System.nanoTime()-began)/1_000_000);}
            }
        };
    }
    void execute(AssistantEvaluationCases.Case scenario)throws Exception{
        Map<String,Object> item=new LinkedHashMap<>();item.put("id",scenario.id());item.put("name",scenario.name());
        item.put("liveEligible",scenario.liveEligible());long start=System.nanoTime();
        AssistantEvaluationCases.Fixture f=null;Throwable failure=null;
        try{
            if(mode.equals("deepseek")&&(usageUnknown||modelCalls>=90||(tokens+50000)*8.0/1_000_000>2.5)){
                item.put("status","NOT_RUN_BUDGET");throw new org.opentest4j.TestAbortedException("Stopped before new paid case; budget or unknown usage");
            }
            f=new AssistantEvaluationCases.Fixture(mode.equals("deepseek")?liveModel(scenario.id()):null);
            item.put("anchorTomorrow",f.tomorrow.toString());
            item.put("fixtureBefore",snapshot(f));
            scenario.action().run(f);item.put("status","PASSED");
        }catch(Throwable error){failure=error;if(!item.containsKey("status"))item.put("status","FAILED");item.put("failureType",error.getClass().getSimpleName());item.put("failure",Objects.toString(error.getMessage(),""));}
        finally{
            item.put("elapsedMs",(System.nanoTime()-start)/1_000_000);
            if(f!=null){
                item.put("fixtureAfter",snapshot(f));
                item.put("session",f.service.session(101,f.session));
                item.put("runs",f.db.queryForList("SELECT id,message,status,mode,checkpoint_json,result_json,error_code,created_at,completed_at FROM ux_assistant_run WHERE session_id=? ORDER BY created_at,id",f.session));
                item.put("steps",f.db.queryForList("SELECT s.step_no,s.tool_name,s.arguments_json,s.result_json,s.outcome,s.elapsed_ms FROM ux_assistant_step s JOIN ux_assistant_run r ON r.id=s.run_id WHERE r.session_id=? ORDER BY r.created_at,s.step_no",f.session));
            }
            results.add(item);save();System.out.println("EVAL "+scenario.id()+" "+item.get("status"));
        }
        if(failure instanceof Exception e)throw e;if(failure instanceof Error e)throw e;
    }
    Object snapshot(AssistantEvaluationCases.Fixture f){return f.db.queryForList("SELECT a.id,a.price_cents,a.available,e.starts_at,e.ends_at,p.category,p.area,o.terms FROM ux_activity a JOIN ux_experience_session e ON e.activity_id=a.id JOIN ux_storefront p ON p.shop_id=e.shop_id JOIN ux_offer o ON o.activity_id=a.id ORDER BY a.id");}
    String sha(String file)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(file))));}
    void save()throws Exception{
        Map<String,Object> report=new LinkedHashMap<>();report.put("suiteVersion","qupiao-agent-v1");report.put("mode",mode);
        report.put("modelAlias",mode.equals("deepseek")?model:null);report.put("providerBase",mode.equals("deepseek")?"https://api.deepseek.com":null);
        report.put("startedAt",started);report.put("updatedAt",Instant.now());report.put("timezone","Asia/Shanghai");
        report.put("testData","Independent synthetic H2 schema per case; no live app MySQL/Redis/MQ data modified");
        report.put("caseSourceSha256",sha("src/test/java/com/hmdp/upgrade/assistant/AssistantEvaluationCases.java"));
        report.put("runtimeSourceSha256",sha("src/main/java/com/hmdp/upgrade/assistant/AssistantRuntime.java"));
        report.put("toolSchemaSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(DeepSeekAssistantModel.schemas(json)))));
        report.put("casesRecorded",results.size());report.put("passed",results.stream().filter(c->c.get("status").equals("PASSED")).count());
        report.put("failed",results.stream().filter(c->c.get("status").equals("FAILED")).count());report.put("notRunBudget",results.stream().filter(c->c.get("status").equals("NOT_RUN_BUDGET")).count());
        report.put("providerCalls",modelCalls);report.put("reportedTotalTokens",tokens);report.put("usageUnknown",usageUnknown);
        report.put("conservativeReportedTokenCostYuan",tokens*8.0/1_000_000);report.put("costNote","Reported-token estimate using 8 CNY/million for every token, not actual billing. Unknown-usage calls excluded and stop further paid calls.");
        report.put("providerCallTrace",calls);report.put("cases",results);
        Files.createDirectories(output.getParent());Path temporary=output.resolveSibling(output.getFileName()+".tmp");
        Files.writeString(temporary,json.writerWithDefaultPrettyPrinter().writeValueAsString(report),StandardCharsets.UTF_8);Files.move(temporary,output,StandardCopyOption.REPLACE_EXISTING);
    }
}

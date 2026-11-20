package com.hmdp.upgrade.assistant;

import com.hmdp.upgrade.Auth;
import com.hmdp.upgrade.Problem;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v2/assistant")
public class AssistantApi {
    public record Turn(String requestKey,String message){}
    private final AssistantService service;private final AssistantCatalog catalog;
    public AssistantApi(AssistantService service,AssistantCatalog catalog){this.service=service;this.catalog=catalog;}
    private long user(HttpServletRequest request){return ((Auth.Identity)request.getAttribute("identity")).user();}
    @GetMapping("/status") public Object status(){return service.status();}
    @GetMapping("/sessions") public Object list(HttpServletRequest r){return service.list(user(r));}
    @PostMapping("/sessions") public Object create(HttpServletRequest r){return service.create(user(r));}
    @GetMapping("/sessions/{id}") public Object session(HttpServletRequest r,@PathVariable String id){return service.session(user(r),id);}
    @DeleteMapping("/sessions/{id}") public ResponseEntity<?> delete(HttpServletRequest r,@PathVariable String id){service.delete(user(r),id);return ResponseEntity.noContent().build();}
    @PostMapping("/sessions/{id}/turns") public ResponseEntity<?> turn(HttpServletRequest r,@PathVariable String id,@RequestBody Turn turn){
        if(turn==null)throw new Problem(400,"ASSISTANT_INVALID_REQUEST");
        return ResponseEntity.accepted().body(service.submit(user(r),id,turn.requestKey(),turn.message()));
    }
    @GetMapping("/sessions/{id}/turns/{run}") public Object run(HttpServletRequest r,@PathVariable String id,@PathVariable String run){return service.get(user(r),id,run);}
    @GetMapping("/sessions/{id}/turns/{run}/steps") public Object steps(HttpServletRequest r,@PathVariable String id,@PathVariable String run){return service.steps(user(r),id,run);}
    @GetMapping("/policy") public Object policy(){return catalog.rules(null);}
}

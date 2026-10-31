package cn.edu.fudan.campus.m2;

import cn.edu.fudan.campus.common.Identity;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;

@Component
public class ApplicationWorker {
  private final JdbcTemplate db;
  private final RoomApplications applications;
  private final Identity identity;
  private final RestClient client;

  public ApplicationWorker(JdbcTemplate db,RoomApplications applications,Identity identity,
      @Value("${campus.resource-url}")String resourceUrl){
    this.db=db;this.applications=applications;this.identity=identity;
    this.client=client(resourceUrl);
  }

  private static RestClient client(String url){
    var factory=new org.springframework.http.client.SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(3000);factory.setReadTimeout(5000);
    return RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }

  @Scheduled(fixedDelay=2000)
  public void sync(){
    for(var task:db.queryForList("SELECT t.*,a.start_at,a.end_at FROM application_sync_tasks t JOIN room_applications a ON a.id=t.application_id WHERE t.status='PENDING' AND t.next_attempt<=CURRENT_TIMESTAMP ORDER BY t.created_at LIMIT 20")){
      String id=(String)task.get("id");
      try{
        var body=new LinkedHashMap<String,Object>();
        body.put("operationId",id);body.put("sourceVersion",task.get("source_version"));body.put("roomId",task.get("room_id"));
        body.put("desiredState",task.get("desired_state"));body.put("startAt",((Timestamp)task.get("start_at")).toInstant().toString());
        body.put("endAt",((Timestamp)task.get("end_at")).toInstant().toString());body.put("reason","整教室申请 "+task.get("application_id"));
        if(task.get("expected_availability_version")!=null)body.put("expectedAvailabilityVersion",task.get("expected_availability_version"));
        var response=client.put().uri("/internal/v1/resource-effects/M2_ROOM_APPLICATION/"+task.get("application_id"))
            .header("X-Service-Key",identity.key()).header("X-Request-Id",(String)task.get("request_id"))
            .body(body).retrieve().body(Map.class);
        if(response==null||!(response.get("data") instanceof Map<?,?> data)
            ||!"APPLIED".equals(data.get("status"))||!(data.get("currentSourceState") instanceof String state))
          throw new RestClientException("Invalid resource operation response");
        applications.applied(id,state);
      }catch(HttpClientErrorException e){applications.failed(id,"RESOURCE_CONFLICT","资源冲突，申请未生效");}
      catch(RestClientException|IllegalStateException e){retry("application_sync_tasks",task,"资源服务暂不可用，将继续重试");}
    }
  }

  private void retry(String table,Map<String,Object> task,String message){
    int n=((Number)task.get("attempts")).intValue()+1;
    db.update("UPDATE "+table+" SET attempts=?,last_error=?,next_attempt=? WHERE id=? AND status='PENDING'",n,message,Timestamp.from(Instant.now().plusSeconds(Math.min(60,n*5L))),task.get("id"));
  }

  @Scheduled(fixedDelay=60000)
  public void expire(){applications.expireDue();}
}

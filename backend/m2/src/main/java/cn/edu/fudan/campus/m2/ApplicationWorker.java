package cn.edu.fudan.campus.m2;

import cn.edu.fudan.campus.common.Identity;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;

@Component
public class ApplicationWorker {
  private final JdbcTemplate db; private final RoomApplications applications; private final Identity identity; private final RestClient client;
  public ApplicationWorker(JdbcTemplate db,RoomApplications applications,Identity identity,@Value("${campus.resource-url}")String url){this.db=db;this.applications=applications;this.identity=identity;this.client=RestClient.builder().baseUrl(url).build();}
  @Scheduled(fixedDelay=2000) public void sync(){
    for(var t:db.queryForList("SELECT t.*,a.start_at,a.end_at,a.purpose FROM application_sync_tasks t JOIN room_applications a ON a.id=t.application_id WHERE t.status='PENDING' AND t.next_attempt<=CURRENT_TIMESTAMP ORDER BY t.created_at LIMIT 20")){
      String id=(String)t.get("id");
      try{
        var body=new java.util.LinkedHashMap<String,Object>();body.put("operationId",id);body.put("sourceVersion",t.get("source_version"));body.put("roomId",t.get("room_id"));body.put("desiredState",t.get("desired_state"));body.put("startAt",((Timestamp)t.get("start_at")).toInstant().toString());body.put("endAt",((Timestamp)t.get("end_at")).toInstant().toString());body.put("reason","整教室申请 "+t.get("application_id"));if(t.get("expected_availability_version")!=null)body.put("expectedAvailabilityVersion",t.get("expected_availability_version"));
        client.put().uri("/internal/v1/resource-effects/M2_ROOM_APPLICATION/"+t.get("application_id")).header("X-Service-Key",identity.key()).header("X-Request-Id",(String)t.get("request_id")).body(body).retrieve().toBodilessEntity();applications.applied(id);
      }catch(HttpClientErrorException e){applications.failed(id,"RESOURCE_CONFLICT","资源冲突，申请未生效");}
      catch(RestClientException e){int n=((Number)t.get("attempts")).intValue()+1;db.update("UPDATE application_sync_tasks SET attempts=?,last_error=?,next_attempt=? WHERE id=? AND status='PENDING'",n,"资源服务暂不可用，将继续重试",Timestamp.from(Instant.now().plusSeconds(Math.min(60,n*5L))),id);}
    }
  }
  @Scheduled(fixedDelay=60000) public void expire(){applications.expireDue();}
}

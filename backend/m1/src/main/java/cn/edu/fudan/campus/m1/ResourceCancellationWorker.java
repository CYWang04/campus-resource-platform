package cn.edu.fudan.campus.m1;

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
public class ResourceCancellationWorker {
  private final JdbcTemplate db;
  private final Identity identity;
  private final RestClient applications;

  public ResourceCancellationWorker(JdbcTemplate db,Identity identity,@Value("${campus.identity-url}")String url){
    this.db=db;this.identity=identity;
    var factory=new org.springframework.http.client.SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(3000);factory.setReadTimeout(5000);
    applications=RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }

  @Scheduled(fixedDelay=2000)
  public void deliver(){
    for(var task:db.queryForList("SELECT * FROM resource_cancellation_tasks WHERE status='PENDING' AND next_attempt<=CURRENT_TIMESTAMP ORDER BY created_at LIMIT 20")){
      try{
        applications.post().uri("/internal/v1/room-applications/"+task.get("application_id")+"/maintenance-cancellation")
            .header("X-Service-Key",identity.key()).header("X-Request-Id",(String)task.get("request_id"))
            .body(Map.of("eventId",task.get("id"),"roomId",task.get("room_id"),"sourceVersion",task.get("source_version"),"maintenanceSourceId",task.get("maintenance_source_id"),"reason","教室紧急维修，原整教室申请已取消"))
            .retrieve().toBodilessEntity();
        db.update("UPDATE resource_cancellation_tasks SET status='SENT',last_error=NULL WHERE id=?",task.get("id"));
      }catch(RestClientException e){
        int n=((Number)task.get("attempts")).intValue()+1;
        db.update("UPDATE resource_cancellation_tasks SET attempts=?,next_attempt=?,last_error=? WHERE id=?",n,Timestamp.from(Instant.now().plusSeconds(Math.min(60,n*5L))),"M2 取消同步暂未完成，将使用相同事件编号重试",task.get("id"));
      }
    }
  }
}

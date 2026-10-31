package cn.edu.fudan.campus.m1;

import cn.edu.fudan.campus.common.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.*;

@Component
public class ReservationWorker {
  private final JdbcTemplate db;private final Identity identity;private final RestClient notices;private final ReservationPolicy policy;private final ResourceAudit audit;
  public ReservationWorker(JdbcTemplate db,Identity identity,@Value("${campus.notification-url}")String url,ReservationPolicy policy,ResourceAudit audit){this.db=db;this.identity=identity;this.policy=policy;this.audit=audit;var factory=new org.springframework.http.client.SimpleClientHttpRequestFactory();factory.setConnectTimeout(3000);factory.setReadTimeout(5000);this.notices=RestClient.builder().baseUrl(url).requestFactory(factory).build();}

  @Scheduled(fixedDelay=30000) @Transactional public void deadlines(){
    for(var r:db.queryForList("SELECT * FROM reservations WHERE status='CONFIRMED' AND start_at>CURRENT_TIMESTAMP AND start_at<=CURRENT_TIMESTAMP+INTERVAL 15 MINUTE"))queue(r,"PRE_REMINDER","预约将在15分钟内开始，请到教室输入当日签到码");
    for(var r:db.queryForList("SELECT * FROM reservations WHERE status='CONFIRMED' AND start_at<=CURRENT_TIMESTAMP-INTERVAL 10 MINUTE AND start_at>CURRENT_TIMESTAMP-INTERVAL 15 MINUTE"))queue(r,"LATE_REMINDER","预约已开始10分钟，请尽快签到，超过15分钟将自动取消并记录违约");
    for(var r:db.queryForList("SELECT * FROM reservations WHERE status='CONFIRMED' AND start_at<=CURRENT_TIMESTAMP-INTERVAL 15 MINUTE FOR UPDATE")){
      int changed=db.update("UPDATE reservations SET status='NO_SHOW',cancelled_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status='CONFIRMED'",r.get("id"));
      if(changed==1){db.update("INSERT IGNORE INTO violation_records(id,reservation_id,user_id,reason) VALUES(?,?,?,'NO_SHOW')",Ids.uuid(),r.get("id"),r.get("user_id"));queue(r,"NO_SHOW","预约因开始15分钟后仍未签到而取消，座位已释放并记录一次违约");audit.record("SYSTEM","NO_SHOW","M1_RESERVATION",(String)r.get("id"),"到期未签到，记录一次违约");}
    }
  }
  void queue(Map<String,Object>r,String type,String message){db.update("INSERT IGNORE INTO reservation_notification_tasks(id,event_key,reservation_id,user_id,email,message) VALUES(?,?,?,?,?,?)",Ids.uuid(),type+":"+r.get("id"),r.get("id"),r.get("user_id"),r.get("user_email"),message);}

  @Scheduled(fixedDelay=3000) public void deliver(){for(var t:db.queryForList("SELECT t.*,r.status AS reservation_status,r.start_at FROM reservation_notification_tasks t JOIN reservations r ON r.id=t.reservation_id WHERE t.status='PENDING' AND t.next_attempt<=CURRENT_TIMESTAMP ORDER BY t.created_at LIMIT 20")){String event=(String)t.get("event_key");Instant now=policy.now(),start=((Timestamp)t.get("start_at")).toInstant();boolean stale=(event.startsWith("PRE_REMINDER:")&&(!"CONFIRMED".equals(t.get("reservation_status"))||!now.isBefore(start)))||(event.startsWith("LATE_REMINDER:")&&(!"CONFIRMED".equals(t.get("reservation_status"))||!now.isBefore(start.plusSeconds(900))));if(stale){db.update("UPDATE reservation_notification_tasks SET status='SKIPPED' WHERE id=?",t.get("id"));continue;}try{notices.post().uri("/internal/v1/notifications").header("X-Service-Key",identity.key()).body(Map.of("eventId",t.get("event_key"),"userId",t.get("user_id"),"email",t.get("email"),"message",t.get("message"))).retrieve().toBodilessEntity();db.update("UPDATE reservation_notification_tasks SET status='SENT',last_error=NULL WHERE id=?",t.get("id"));}catch(RestClientException e){int n=((Number)t.get("attempts")).intValue()+1;db.update("UPDATE reservation_notification_tasks SET attempts=?,last_error=?,next_attempt=? WHERE id=?",n,"通知服务暂不可用，将继续重试",Timestamp.from(Instant.now().plusSeconds(Math.min(60,n*5L))),t.get("id"));}}}
}

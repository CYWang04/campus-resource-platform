package cn.edu.fudan.campus.m2;

import cn.edu.fudan.campus.common.Identity;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class ApplicationNotificationWorker {
  private final JdbcTemplate db;
  private final Identity identity;
  private final RestClient client;

  public ApplicationNotificationWorker(
      JdbcTemplate db, Identity identity,
      @Value("${campus.notification-url:http://localhost:8083}") String url) {
    this.db = db;
    this.identity = identity;
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(3000);
    factory.setReadTimeout(5000);
    this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }

  @Scheduled(fixedDelay = 3000)
  public void deliver() {
    for (var task : db.queryForList(
        "SELECT * FROM application_notification_tasks WHERE status='PENDING'"
            + " AND next_attempt<=CURRENT_TIMESTAMP ORDER BY created_at LIMIT 20")) {
      try {
        client.post().uri("/internal/v1/notifications")
            .header("X-Service-Key", identity.key())
            .body(Map.of("eventId", task.get("event_key"), "userId", task.get("user_id"),
                "email", task.get("email"), "message", task.get("message")))
            .retrieve().toBodilessEntity();
        db.update("UPDATE application_notification_tasks SET status='SENT',last_error=NULL"
            + " WHERE id=? AND status='PENDING'", task.get("id"));
      } catch (RestClientException e) {
        int attempts = ((Number) task.get("attempts")).intValue() + 1;
        db.update("UPDATE application_notification_tasks SET attempts=?,last_error=?,next_attempt=?"
                + " WHERE id=? AND status='PENDING'", attempts, "通知服务暂不可用，将继续重试",
            Timestamp.from(Instant.now().plusSeconds(Math.min(60, attempts * 5L))), task.get("id"));
      }
    }
  }
}

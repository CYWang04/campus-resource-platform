package cn.edu.fudan.campus.m3;

import cn.edu.fudan.campus.common.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class DeliveryWorker {
  private final JdbcTemplate db;
  private final Orders orders;
  private final Identity identity;
  private final RestClient client;
  private final JavaMailSender mail;

  public DeliveryWorker(
      JdbcTemplate db,
      Orders orders,
      Identity identity,
      JavaMailSender mail,
      @Value("${campus.resource-url}") String url) {
    this.db = db;
    this.orders = orders;
    this.identity = identity;
    this.mail = mail;
    client =
        RestClient.builder()
            .baseUrl(url)
            .requestFactory(
                new org.springframework.http.client.SimpleClientHttpRequestFactory() {
                  {
                    setConnectTimeout(3000);
                    setReadTimeout(5000);
                  }
                })
            .build();
  }

  @Scheduled(fixedDelay = 2000)
  public void resources() {
    for (var task :
        db.queryForList(
            "SELECT * FROM sync_tasks WHERE status='PENDING' AND next_attempt<=CURRENT_TIMESTAMP"
                + " ORDER BY created_at LIMIT 20")) {
      String id = (String) task.get("id");
      try {
        client
            .put()
            .uri("/internal/v1/resource-effects/M3_MAINTENANCE/" + task.get("order_id"))
            .header("X-Service-Key", identity.key())
            .header("X-Request-Id", (String) task.get("request_id"))
            .body(
                Map.of(
                    "operationId",
                    id,
                    "sourceVersion",
                    task.get("source_version"),
                    "roomId",
                    task.get("room_id"),
                    "desiredState",
                    task.get("desired_state"),
                    "reason",
                    "维修工单 " + task.get("order_id")))
            .retrieve()
            .toBodilessEntity();
        orders.applied(id);
      } catch (Exception e) {
        int attempts = ((Number) task.get("attempts")).intValue() + 1;
        boolean terminal = e instanceof org.springframework.web.client.HttpClientErrorException;
        db.update(
            "UPDATE sync_tasks SET attempts=?,status=?,last_error=?,next_attempt=? WHERE id=? AND"
                + " status='PENDING'",
            attempts,
            terminal ? "FAILED" : "PENDING",
            terminal ? "资源服务拒绝，请人工检查" : "依赖暂不可用，将继续重试",
            Timestamp.from(Instant.now().plusSeconds(Math.min(60, attempts * 5L))),
            id);
      }
    }
  }

  @Scheduled(fixedDelay = 3000)
  public void emails() {
    for (var n :
        db.queryForList(
            "SELECT * FROM notifications WHERE mail_state='PENDING' AND"
                + " next_attempt<=CURRENT_TIMESTAMP LIMIT 20")) {
      try {
        var message = new SimpleMailMessage();
        message.setFrom("noreply@campus.test");
        message.setTo((String) n.get("email"));
        message.setSubject("校园资源平台工单通知");
        message.setText((String) n.get("message"));
        mail.send(message);
        db.update("UPDATE notifications SET mail_state='SENT' WHERE id=?", n.get("id"));
      } catch (Exception e) {
        int tries = ((Number) n.get("mail_attempts")).intValue() + 1;
        db.update(
            "UPDATE notifications SET mail_attempts=?,mail_state=?,next_attempt=? WHERE id=?",
            tries,
            tries >= 10 ? "FAILED" : "PENDING",
            Timestamp.from(Instant.now().plusSeconds(30)),
            n.get("id"));
      }
    }
  }
}

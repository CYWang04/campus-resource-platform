package cn.edu.fudan.campus.m3;

import cn.edu.fudan.campus.common.*;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
public class WorkOrderController {
  private final org.springframework.web.client.RestClient resources;
  private final Orders orders;
  private final Identity identity;
  private final JdbcTemplate db;

  public WorkOrderController(
      Orders orders,
      Identity identity,
      JdbcTemplate db,
      @org.springframework.beans.factory.annotation.Value("${campus.resource-url}") String url) {
    resources =
        org.springframework.web.client.RestClient.builder()
            .baseUrl(url)
            .requestFactory(
                new org.springframework.http.client.SimpleClientHttpRequestFactory() {
                  {
                    setConnectTimeout(3000);
                    setReadTimeout(5000);
                  }
                })
            .build();
    this.orders = orders;
    this.identity = identity;
    this.db = db;
  }

  @GetMapping("/api/v1/work-orders")
  public Object list(@RequestHeader(value = "Authorization", required = false) String auth) {
    return Api.ok(orders.list(identity.require(auth, null)));
  }

  @PostMapping("/api/v1/work-orders")
  public Object create(
      @RequestHeader(value = "Authorization", required = false) String auth,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @Valid @RequestBody Orders.Report input) {
    var actor = identity.require(auth, "workorders:create");
    Ids.uuid(input.roomId());
    try {
      resources
          .get()
          .uri("/api/v1/rooms/" + input.roomId() + "/availability")
          .header("Authorization", auth)
          .retrieve()
          .toBodilessEntity();
    } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
      throw Api.fail(404, "ROOM_NOT_FOUND", "教室不存在");
    } catch (org.springframework.web.client.RestClientException e) {
      throw Api.fail(503, "RESOURCE_UNAVAILABLE", "资源服务暂不可用");
    }
    return Api.ok(orders.create(actor, key, input));
  }

  @GetMapping("/api/v1/work-orders/{id}")
  public Object get(
      @RequestHeader(value = "Authorization", required = false) String auth,
      @PathVariable String id) {
    return Api.ok(orders.get(id, identity.require(auth, null)));
  }

  @PostMapping("/api/v1/work-orders/{id}/{action}")
  public Object action(
      @RequestHeader(value = "Authorization", required = false) String auth,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @PathVariable String id,
      @PathVariable String action) {
    return Api.ok(orders.action(identity.require(auth, null), key, id, action));
  }

  @GetMapping("/api/v1/notifications")
  public Object notifications(
      @RequestHeader(value = "Authorization", required = false) String auth) {
    var a = identity.require(auth, null);
    return Api.ok(
        db.queryForList(
            "SELECT id,message,is_read,mail_state,created_at FROM notifications WHERE user_id=?"
                + " ORDER BY created_at DESC LIMIT 100",
            a.id()));
  }

  @GetMapping("/api/v1/admin/audit-events")
  public Object audit(@RequestHeader(value = "Authorization", required = false) String auth) {
    identity.require(auth, "audit:read");
    return Api.ok(db.queryForList("SELECT * FROM audit_events ORDER BY created_at DESC LIMIT 100"));
  }
}

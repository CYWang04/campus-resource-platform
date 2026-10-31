package cn.edu.fudan.campus.m3;

import cn.edu.fudan.campus.common.*;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
public class WorkOrderController {
  public record NotificationEvent(@jakarta.validation.constraints.NotBlank String eventId,@jakarta.validation.constraints.NotBlank String userId,@jakarta.validation.constraints.Email String email,@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=500) String message){}
  private final org.springframework.web.client.RestClient resources;
  private final Orders orders;
  private final WorkOrderAssignments assignments;
  private final Identity identity;
  private final JdbcTemplate db;

  public WorkOrderController(
      Orders orders,
      Identity identity,
      JdbcTemplate db,
      @org.springframework.beans.factory.annotation.Value("${campus.resource-url}") String url, WorkOrderAssignments assignments) {
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
    this.assignments = assignments;
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

  @PostMapping("/api/v1/work-orders/{id}/assign") public Object assign(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="Idempotency-Key",required=false)String key,@PathVariable String id,@Valid @RequestBody Orders.Assignment input){return Api.ok(assignments.assign(auth,key,id,input));}
  @PostMapping("/api/v1/work-orders/{id}/complete-with-note") public Object complete(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="Idempotency-Key",required=false)String key,@PathVariable String id,@Valid @RequestBody Orders.Note input){return Api.ok(orders.complete(identity.require(auth,null),key,id,input));}
  @PostMapping("/api/v1/work-orders/{id}/reject-acceptance") public Object reject(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="Idempotency-Key",required=false)String key,@PathVariable String id,@Valid @RequestBody Orders.Note input){return Api.ok(orders.rejectAcceptance(identity.require(auth,null),key,id,input));}
  @PostMapping("/api/v1/work-orders/{id}/cancel") public Object cancel(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="Idempotency-Key",required=false)String key,@PathVariable String id){return Api.ok(orders.cancel(identity.require(auth,null),key,id));}
  @PostMapping("/api/v1/work-orders/{id}/rating") public Object rate(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="Idempotency-Key",required=false)String key,@PathVariable String id,@Valid @RequestBody Orders.Rating input){return Api.ok(orders.rate(identity.require(auth,null),key,id,input));}

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

  @PostMapping("/internal/v1/notifications")
  public Object receiveNotification(@RequestHeader(value="X-Service-Key",required=false)String key,@Valid @RequestBody NotificationEvent input){identity.internal(key);db.update("INSERT IGNORE INTO notifications(id,user_id,email,message,external_event_key) VALUES(?,?,?,?,?)",Ids.uuid(),input.userId(),input.email(),input.message(),input.eventId());return Api.ok(Map.of("accepted",true,"eventId",input.eventId()));}
}

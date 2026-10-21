package cn.edu.fudan.campus.m3;

import cn.edu.fudan.campus.common.*;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Orders {
  private final JdbcTemplate db;

  public Orders(JdbcTemplate db) {
    this.db = db;
  }

  public record Report(
      @NotBlank String roomId,
      @NotBlank @Size(max = 200) String title,
      @NotBlank @Size(max = 2000) String description) {}

  @Transactional
  public Map<String, Object> create(Identity.Actor actor, String key, Report input) {
    Ids.key(key);
    Ids.uuid(input.roomId());
    String fp = Ids.hash("create|" + input.toString());
    var prior = prior(actor.id(), key, fp);
    if (prior != null) return get(prior, actor);
    String id = Ids.uuid();
    db.update(
        "INSERT INTO work_orders(id,room_id,title,description,reporter_id,reporter_email,state)"
            + " VALUES(?,?,?,?,?,?,'REPORTED')",
        id,
        input.roomId(),
        input.title(),
        input.description(),
        actor.id(),
        actor.email());
    remember(actor.id(), key, fp, id);
    audit(actor.id(), "REPORTED", id);
    return get(id, actor);
  }

  @Transactional
  public Map<String, Object> action(Identity.Actor actor, String key, String id, String action) {
    Ids.key(key);
    Ids.uuid(id);
    String fp = Ids.hash(action + "|" + id);
    var prior = prior(actor.id(), key, fp);
    if (prior != null) return get(prior, actor);
    var rows = db.queryForList("SELECT * FROM work_orders WHERE id=? FOR UPDATE", id);
    if (rows.isEmpty()) throw Api.fail(404, "ORDER_NOT_FOUND", "工单不存在");
    var order = rows.getFirst();
    String state = (String) order.get("state");
    String next;
    switch (action) {
      case "start" -> {
        require(actor, "workorders:repair");
        expect(state, "REPORTED");
        next = "START_PENDING";
        db.update("UPDATE work_orders SET repairer_id=? WHERE id=?", actor.id(), id);
      }
      case "complete" -> {
        require(actor, "workorders:repair");
        expect(state, "REPAIRING");
        if (!actor.id().equals(order.get("repairer_id")))
          throw Api.fail(403, "NOT_ASSIGNED", "仅本单维修人员可提交完工");
        next = "AWAITING_ACCEPTANCE";
      }
      case "accept" -> {
        require(actor, "workorders:accept");
        expect(state, "AWAITING_ACCEPTANCE");
        if (actor.id().equals(order.get("repairer_id")))
          throw Api.fail(403, "SELF_ACCEPTANCE", "维修人员不能自验");
        next = "RELEASE_PENDING";
      }
      default -> throw Api.fail(404, "ACTION_NOT_FOUND", "操作不存在");
    }
    db.update("UPDATE work_orders SET state=? WHERE id=?", next, id);
    if (action.equals("start") || action.equals("accept")) {
      long version = ((Number) order.get("source_version")).longValue() + 1;
      db.update("UPDATE work_orders SET source_version=? WHERE id=?", version, id);
      db.update(
          "INSERT INTO sync_tasks(id,order_id,room_id,source_version,desired_state,request_id)"
              + " VALUES(?,?,?,?,?,?)",
          Ids.uuid(),
          id,
          order.get("room_id"),
          version,
          action.equals("start") ? "ACTIVE" : "RELEASED",
          Trace.id());
    }
    remember(actor.id(), key, fp, id);
    audit(actor.id(), action.toUpperCase(Locale.ROOT), id);
    return get(id, actor);
  }

  public List<Map<String, Object>> list(Identity.Actor a) {
    return a.permissions().contains("workorders:read")
        ? db.queryForList("SELECT * FROM work_orders ORDER BY created_at DESC LIMIT 100")
        : db.queryForList(
            "SELECT * FROM work_orders WHERE reporter_id=? ORDER BY created_at DESC LIMIT 100",
            a.id());
  }

  public Map<String, Object> get(String id, Identity.Actor a) {
    Ids.uuid(id);
    var rows = db.queryForList("SELECT * FROM work_orders WHERE id=?", id);
    if (rows.isEmpty()) throw Api.fail(404, "ORDER_NOT_FOUND", "工单不存在");
    var row = rows.getFirst();
    if (!a.permissions().contains("workorders:read") && !a.id().equals(row.get("reporter_id")))
      throw Api.fail(403, "FORBIDDEN", "只能查看本人报修");
    row.put(
        "operations",
        db.queryForList(
            "SELECT id,status,attempts,last_error FROM sync_tasks WHERE order_id=? ORDER BY"
                + " created_at",
            id));
    return row;
  }

  @Transactional
  public void applied(String taskId) {
    var tasks =
        db.queryForList(
            "SELECT * FROM sync_tasks WHERE id=? AND status='PENDING' FOR UPDATE", taskId);
    if (tasks.isEmpty()) return;
    var task = tasks.getFirst();
    String id = (String) task.get("order_id");
    String expected =
        task.get("desired_state").equals("ACTIVE") ? "START_PENDING" : "RELEASE_PENDING";
    String next = task.get("desired_state").equals("ACTIVE") ? "REPAIRING" : "CLOSED";
    int changed =
        db.update(
            "UPDATE work_orders SET state=? WHERE id=? AND state=? AND source_version=?",
            next,
            id,
            expected,
            task.get("source_version"));
    db.update("UPDATE sync_tasks SET status='APPLIED',last_error=NULL WHERE id=?", taskId);
    if (changed == 1) {
      var order = db.queryForMap("SELECT * FROM work_orders WHERE id=?", id);
      db.update(
          "INSERT INTO notifications(id,user_id,email,message) VALUES(?,?,?,?)",
          Ids.uuid(),
          order.get("reporter_id"),
          order.get("reporter_email"),
          "工单「"
              + order.get("title")
              + "」："
              + (next.equals("REPAIRING") ? "维修限制已生效" : "验收通过，维修来源已解除"));
      audit("SYSTEM", next, id);
    }
  }

  private String prior(String actor, String key, String fp) {
    var rows =
        db.queryForList(
            "SELECT * FROM commands WHERE actor_id=? AND idempotency_key=?", actor, key);
    if (rows.isEmpty()) return null;
    if (!fp.equals(rows.getFirst().get("fingerprint")))
      throw Api.fail(409, "IDEMPOTENCY_CONFLICT", "幂等键已经用于其他内容");
    return (String) rows.getFirst().get("order_id");
  }

  private void remember(String a, String k, String f, String id) {
    db.update(
        "INSERT INTO commands(actor_id,idempotency_key,fingerprint,order_id) VALUES(?,?,?,?)",
        a,
        k,
        f,
        id);
  }

  private void require(Identity.Actor a, String permission) {
    if (!a.permissions().contains(permission)) throw Api.fail(403, "FORBIDDEN", "没有此操作权限");
  }

  private void expect(String actual, String expected) {
    if (!actual.equals(expected)) throw Api.fail(409, "INVALID_STATE", "当前工单状态不允许此操作");
  }

  private void audit(String actor, String action, String id) {
    db.update(
        "INSERT INTO audit_events(id,actor_id,action,order_id,request_id) VALUES(?,?,?,?,?)",
        Ids.uuid(),
        actor,
        action,
        id,
        Trace.id());
  }
}

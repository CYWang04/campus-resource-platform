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
  public record Assignment(@NotBlank String repairerId,@NotBlank @Size(max=100) String repairerName,@Email String repairerEmail) {}
  public record Note(@Size(max=1000) String note) {}
  public record Rating(@Min(1) @Max(5) int score,@Size(max=1000) String comment) {}

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
        expect(state, "ASSIGNED");
        if (!actor.id().equals(order.get("repairer_id"))) throw Api.fail(403,"NOT_ASSIGNED","仅本单维修人员可以开工");
        next = "START_PENDING";
        db.update("UPDATE work_orders SET started_at=CURRENT_TIMESTAMP(6) WHERE id=?", id);
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

  public Map<String,Object> assignmentReplay(Identity.Actor actor, String key, String id, Assignment input) {
    require(actor, "workorders:assign");
    String previous = prior(actor.id(), key, Ids.hash("assign|" + id + "|" + input.toString()));
    return previous == null ? null : get(previous, actor);
  }

  @Transactional
  public Map<String,Object> assign(Identity.Actor actor, String key, String id, Assignment input,
      WorkerDirectory.Worker worker) {
    require(actor, "workorders:assign"); Ids.key(key); Ids.uuid(id); Ids.uuid(input.repairerId());
    if (!input.repairerId().equals(worker.id()))
      throw Api.fail(409, "REPAIRER_NOT_AVAILABLE", "维修人员校验结果与请求不一致");
    var order = locked(id);
    var replay = assignmentReplay(actor, key, id, input);
    if (replay != null) return replay;
    expect((String) order.get("state"), "REPORTED");
    db.update("UPDATE work_orders SET state='ASSIGNED',repairer_id=?,repairer_name=?,repairer_email=?,"
        + "assigned_at=CURRENT_TIMESTAMP(6) WHERE id=?", worker.id(), worker.displayName(), worker.email(), id);
    remember(actor.id(), key, Ids.hash("assign|" + id + "|" + input.toString()), id);
    audit(actor.id(), "ASSIGNED", id);
    notify((String) order.get("reporter_id"), (String) order.get("reporter_email"),
        "工单「" + order.get("title") + "」已派给 " + worker.displayName());
    notify(worker.id(), worker.email(), "新工单「" + order.get("title") + "」已分配给你");
    return get(id, actor);
  }
  @Transactional
  public Map<String, Object> complete(Identity.Actor actor, String key, String id, Note input) {
    require(actor, "workorders:repair");
    Ids.key(key);
    Ids.uuid(id);
    String fingerprint = Ids.hash("complete-with-note|" + id + "|" + input.toString());
    // Lock before the first command read so concurrent retries also see committed
    // commands with MySQL's default REPEATABLE READ isolation.
    var order = locked(id);
    var replay = prior(actor.id(), key, fingerprint);
    if (replay != null) return get(replay, actor);
    expect((String) order.get("state"), "REPAIRING");
    if (!actor.id().equals(order.get("repairer_id")))
      throw Api.fail(403, "NOT_ASSIGNED", "仅本单维修人员可提交完工");
    db.update("UPDATE work_orders SET state='AWAITING_ACCEPTANCE',completion_note=?,"
        + "completed_at=CURRENT_TIMESTAMP(6) WHERE id=?", input.note(), id);
    remember(actor.id(), key, fingerprint, id);
    audit(actor.id(), "COMPLETE", id);
    return get(id, actor);
  }
  @Transactional public Map<String,Object> rejectAcceptance(Identity.Actor actor,String key,String id,Note input){require(actor,"workorders:accept");Ids.key(key);Ids.uuid(id);String fp=Ids.hash("reject-acceptance|"+id+"|"+input.toString()),prior=prior(actor.id(),key,fp);if(prior!=null)return get(prior,actor);var o=locked(id);expect((String)o.get("state"),"AWAITING_ACCEPTANCE");if(actor.id().equals(o.get("repairer_id")))throw Api.fail(403,"SELF_ACCEPTANCE","维修人员不能自验");db.update("UPDATE work_orders SET state='REPAIRING',acceptance_note=?,completed_at=NULL WHERE id=?",input.note(),id);remember(actor.id(),key,fp,id);audit(actor.id(),"ACCEPTANCE_REJECTED",id);notify((String)o.get("repairer_id"),(String)o.get("repairer_email"),"工单「"+o.get("title")+"」验收未通过，请继续处理");return get(id,actor);}
  @Transactional public Map<String,Object> cancel(Identity.Actor actor,String key,String id){Ids.key(key);Ids.uuid(id);String fp=Ids.hash("cancel|"+id),prior=prior(actor.id(),key,fp);if(prior!=null)return get(prior,actor);var o=locked(id);if(!actor.id().equals(o.get("reporter_id")))throw Api.fail(403,"FORBIDDEN","只能取消自己的报修");if(!List.of("REPORTED","ASSIGNED").contains(o.get("state")))throw Api.fail(409,"INVALID_STATE","当前工单不能取消");db.update("UPDATE work_orders SET state='CANCELLED' WHERE id=?",id);remember(actor.id(),key,fp,id);audit(actor.id(),"CANCELLED",id);return get(id,actor);}
  @Transactional public Map<String,Object> rate(Identity.Actor actor,String key,String id,Rating input){Ids.key(key);Ids.uuid(id);String fp=Ids.hash("rate|"+id+"|"+input.toString()),prior=prior(actor.id(),key,fp);if(prior!=null)return get(prior,actor);var o=locked(id);if(!actor.id().equals(o.get("reporter_id")))throw Api.fail(403,"FORBIDDEN","只能评价自己的报修");expect((String)o.get("state"),"CLOSED");if(o.get("rating")!=null)throw Api.fail(409,"ALREADY_RATED","工单已经评价");db.update("UPDATE work_orders SET rating=?,rating_comment=?,rated_at=CURRENT_TIMESTAMP(6) WHERE id=?",input.score(),input.comment(),id);remember(actor.id(),key,fp,id);audit(actor.id(),"RATED",id);return get(id,actor);}

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
      if(next.equals("CLOSED")) db.update("UPDATE work_orders SET accepted_at=CURRENT_TIMESTAMP(6) WHERE id=?",id);
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
  private Map<String,Object> locked(String id){var r=db.queryForList("SELECT * FROM work_orders WHERE id=? FOR UPDATE",id);if(r.isEmpty())throw Api.fail(404,"ORDER_NOT_FOUND","工单不存在");return r.getFirst();}
  private void notify(String user,String email,String message){if(user!=null&&email!=null)db.update("INSERT INTO notifications(id,user_id,email,message) VALUES(?,?,?,?)",Ids.uuid(),user,email,message);}

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

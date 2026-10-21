package cn.edu.fudan.campus.m2;

import cn.edu.fudan.campus.common.*;
import jakarta.validation.constraints.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoomApplications {
  private final JdbcTemplate db;

  public RoomApplications(JdbcTemplate db) { this.db = db; }

  public record Create(@NotBlank String roomId, @NotBlank @Size(max=100) String roomName,
      @NotNull Instant startAt, @NotNull Instant endAt, @Min(1) @Max(300) int attendeeCount,
      @NotBlank @Size(max=500) String purpose, @Min(0) long expectedAvailabilityVersion) {}
  public record Review(@NotBlank @Pattern(regexp="APPROVE|REJECT") String decision,
      @Size(max=500) String comment) {}

  @Transactional
  public Map<String,Object> create(Identity.Actor actor, String key, Create input) {
    require(actor, "roomapplications:create"); Ids.key(key); Ids.uuid(input.roomId());
    validateWindow(input.startAt(), input.endAt());
    String fp=Ids.hash("create|"+input.toString()), prior=prior(actor.id(),key,fp);
    if(prior!=null) return get(prior,actor);
    String id=Ids.uuid();
    db.update("INSERT INTO room_applications(id,applicant_id,applicant_email,room_id,room_name,purpose,attendee_count,start_at,end_at,state,expected_availability_version) VALUES(?,?,?,?,?,?,?,?,?,'DEPARTMENT_PENDING',?)",
      id,actor.id(),actor.email(),input.roomId(),input.roomName().trim(),input.purpose().trim(),input.attendeeCount(),Timestamp.from(input.startAt()),Timestamp.from(input.endAt()),input.expectedAvailabilityVersion());
    remember(actor.id(),key,fp,id); audit(actor.id(),"CREATED",id); return get(id,actor);
  }

  public List<Map<String,Object>> list(Identity.Actor actor) {
    if(actor.permissions().contains("roomapplications:approve:department") || actor.permissions().contains("roomapplications:approve:resource"))
      return db.queryForList("SELECT * FROM room_applications ORDER BY created_at DESC LIMIT 100");
    require(actor,"roomapplications:read");
    return db.queryForList("SELECT * FROM room_applications WHERE applicant_id=? ORDER BY created_at DESC LIMIT 100",actor.id());
  }

  public Map<String,Object> get(String id, Identity.Actor actor) {
    Ids.uuid(id); var rows=db.queryForList("SELECT * FROM room_applications WHERE id=?",id);
    if(rows.isEmpty()) throw Api.fail(404,"APPLICATION_NOT_FOUND","教室申请不存在");
    var row=rows.getFirst();
    boolean reviewer=actor.permissions().contains("roomapplications:approve:department") || actor.permissions().contains("roomapplications:approve:resource");
    if(!reviewer && !actor.id().equals(row.get("applicant_id"))) throw Api.fail(403,"FORBIDDEN","只能查看自己的申请");
    row.put("operations",db.queryForList("SELECT id,status,attempts,last_error FROM application_sync_tasks WHERE application_id=? ORDER BY created_at",id));
    return row;
  }

  @Transactional
  public Map<String,Object> review(Identity.Actor actor,String key,String id,Review input) {
    Ids.key(key); Ids.uuid(id); String fp=Ids.hash("review|"+id+"|"+input.toString()), prior=prior(actor.id(),key,fp);
    if(prior!=null) return get(prior,actor);
    var app=locked(id); String state=(String)app.get("state");
    if(actor.id().equals(app.get("applicant_id"))) throw Api.fail(403,"SELF_REVIEW","申请人不能审批自己的申请");
    if(state.equals("DEPARTMENT_PENDING")) require(actor,"roomapplications:approve:department");
    else if(state.equals("RESOURCE_PENDING")) require(actor,"roomapplications:approve:resource");
    else throw Api.fail(409,"INVALID_STATE","当前申请状态不能审批");
    String action;
    if(input.decision().equals("REJECT")) { db.update("UPDATE room_applications SET state='REJECTED',review_comment=?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",input.comment(),id); action="REJECTED"; }
    else if(state.equals("DEPARTMENT_PENDING")) { db.update("UPDATE room_applications SET state='RESOURCE_PENDING',department_reviewer_id=?,review_comment=?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",actor.id(),input.comment(),id); action="DEPARTMENT_APPROVED"; }
    else { long version=((Number)app.get("source_version")).longValue()+1; db.update("UPDATE room_applications SET state='RESOURCE_SYNC_PENDING',resource_reviewer_id=?,review_comment=?,source_version=?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",actor.id(),input.comment(),version,id); task(app,id,version,"ACTIVE",(Number)app.get("expected_availability_version")); action="RESOURCE_APPROVED"; }
    remember(actor.id(),key,fp,id); audit(actor.id(),action,id); return get(id,actor);
  }

  @Transactional
  public Map<String,Object> withdraw(Identity.Actor actor,String key,String id) {
    Ids.key(key); Ids.uuid(id); String fp=Ids.hash("withdraw|"+id), prior=prior(actor.id(),key,fp); if(prior!=null)return get(prior,actor);
    var app=locked(id); if(!actor.id().equals(app.get("applicant_id"))) throw Api.fail(403,"FORBIDDEN","只能撤销自己的申请");
    String state=(String)app.get("state");
    if(List.of("DEPARTMENT_PENDING","RESOURCE_PENDING").contains(state)) db.update("UPDATE room_applications SET state='WITHDRAWN',updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",id);
    else if(state.equals("APPROVED")) { if(!Instant.now().isBefore(((Timestamp)app.get("start_at")).toInstant())) throw Api.fail(409,"APPLICATION_STARTED","使用开始后不能撤销"); long v=((Number)app.get("source_version")).longValue()+1; db.update("UPDATE room_applications SET state='WITHDRAW_SYNC_PENDING',source_version=?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",v,id); task(app,id,v,"RELEASED",null); }
    else throw Api.fail(409,"INVALID_STATE","当前申请状态不能撤销");
    remember(actor.id(),key,fp,id); audit(actor.id(),"WITHDRAW_REQUESTED",id); return get(id,actor);
  }

  @Transactional
  public void applied(String taskId) {
    var tasks=db.queryForList("SELECT * FROM application_sync_tasks WHERE id=? AND status='PENDING' FOR UPDATE",taskId); if(tasks.isEmpty())return;
    var t=tasks.getFirst(); String expected=t.get("desired_state").equals("ACTIVE")?"RESOURCE_SYNC_PENDING":"WITHDRAW_SYNC_PENDING";
    String next=t.get("desired_state").equals("ACTIVE")?"APPROVED":"WITHDRAWN";
    int changed=db.update("UPDATE room_applications SET state=?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND state=? AND source_version=?",next,t.get("application_id"),expected,t.get("source_version"));
    db.update("UPDATE application_sync_tasks SET status='APPLIED',last_error=NULL WHERE id=?",taskId); if(changed==1)audit("SYSTEM",next,(String)t.get("application_id"));
  }
  @Transactional public void failed(String taskId,String code,String message){ var tasks=db.queryForList("SELECT * FROM application_sync_tasks WHERE id=? AND status='PENDING' FOR UPDATE",taskId); if(tasks.isEmpty())return; var t=tasks.getFirst(); db.update("UPDATE application_sync_tasks SET status='FAILED',last_error=? WHERE id=?",message,taskId); if(t.get("desired_state").equals("ACTIVE")) db.update("UPDATE room_applications SET state='CONFLICT',review_comment=?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND state='RESOURCE_SYNC_PENDING'",message,t.get("application_id")); audit("SYSTEM",code,(String)t.get("application_id")); }
  @Transactional public void expireDue(){ for(var a:db.queryForList("SELECT * FROM room_applications WHERE state IN ('DEPARTMENT_PENDING','RESOURCE_PENDING') AND (updated_at < CURRENT_TIMESTAMP - INTERVAL 24 HOUR OR start_at<=CURRENT_TIMESTAMP) FOR UPDATE")){ db.update("UPDATE room_applications SET state='EXPIRED',updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",a.get("id")); audit("SYSTEM","EXPIRED",(String)a.get("id")); } }

  private Map<String,Object> locked(String id){var r=db.queryForList("SELECT * FROM room_applications WHERE id=? FOR UPDATE",id);if(r.isEmpty())throw Api.fail(404,"APPLICATION_NOT_FOUND","教室申请不存在");return r.getFirst();}
  private void task(Map<String,Object>a,String id,long v,String desired,Number expected){db.update("INSERT INTO application_sync_tasks(id,application_id,room_id,source_version,desired_state,expected_availability_version,request_id) VALUES(?,?,?,?,?,?,?)",Ids.uuid(),id,a.get("room_id"),v,desired,expected,Trace.id());}
  private void validateWindow(Instant s,Instant e){if(s.getEpochSecond()%3600!=0||e.getEpochSecond()%3600!=0)throw Api.fail(400,"WHOLE_HOUR_REQUIRED","使用时间必须为整点");if(!s.isAfter(Instant.now())||!e.isAfter(s))throw Api.fail(409,"INVALID_TIME","使用时段必须在未来");if(s.isAfter(Instant.now().plus(Duration.ofDays(30))))throw Api.fail(409,"START_TOO_LATE","整教室申请只能提交未来30天内的时段");}
  private String prior(String a,String k,String f){var r=db.queryForList("SELECT * FROM application_commands WHERE actor_id=? AND idempotency_key=?",a,k);if(r.isEmpty())return null;if(!f.equals(r.getFirst().get("fingerprint")))throw Api.fail(409,"IDEMPOTENCY_CONFLICT","幂等键已经用于其他内容");return(String)r.getFirst().get("application_id");}
  private void remember(String a,String k,String f,String id){db.update("INSERT INTO application_commands(actor_id,idempotency_key,fingerprint,application_id) VALUES(?,?,?,?)",a,k,f,id);}
  private void audit(String a,String action,String id){db.update("INSERT INTO application_audit_events(id,actor_id,action,application_id,request_id) VALUES(?,?,?,?,?)",Ids.uuid(),a,action,id,Trace.id());}
  private void require(Identity.Actor a,String p){if(!a.permissions().contains(p))throw Api.fail(403,"FORBIDDEN","没有此操作权限");}
}

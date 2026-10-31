package cn.edu.fudan.campus.m1;

import cn.edu.fudan.campus.common.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
public class ResourceAudit {
  private final JdbcTemplate db;
  private final Identity identity;
  public ResourceAudit(JdbcTemplate db,Identity identity){this.db=db;this.identity=identity;}

  void record(String actor,String action,String type,String id,String reason) {
    db.update("INSERT INTO resource_audit_events(id,actor_id,action,entity_type,entity_id,reason,request_id) VALUES(?,?,?,?,?,?,?)",
        Ids.uuid(),actor,action,type,id,reason,Trace.id());
  }

  @GetMapping("/api/v1/admin/resource-audit-events")
  public Object list(@RequestHeader(value="Authorization",required=false)String auth) {
    identity.require(auth,"audit:read");
    return Api.ok(db.queryForList("SELECT * FROM resource_audit_events ORDER BY created_at DESC LIMIT 100"));
  }

  @GetMapping("/api/v1/admin/resource-sync-tasks")
  public Object tasks(@RequestHeader(value="Authorization",required=false)String auth) {
    identity.require(auth,"resources:manage");
    return Api.ok(db.queryForList("SELECT id,application_id,room_id,status,attempts,last_error,next_attempt FROM resource_cancellation_tasks ORDER BY created_at DESC LIMIT 100"));
  }
}

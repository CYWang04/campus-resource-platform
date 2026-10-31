package cn.edu.fudan.campus.m3;

import cn.edu.fudan.campus.common.Ids;
import cn.edu.fudan.campus.common.Trace;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkOrderEscalations {
  private final JdbcTemplate db;
  private final Clock clock;

  @Autowired
  public WorkOrderEscalations(JdbcTemplate db) { this(db, Clock.systemUTC()); }

  WorkOrderEscalations(JdbcTemplate db, Clock clock) { this.db = db; this.clock = clock; }

  public List<String> due() {
    var now = clock.instant();
    return db.queryForList("SELECT id FROM work_orders WHERE"
        + " (state='ASSIGNED' AND assigned_at<=? AND escalation_level<1)"
        + " OR (state='REPAIRING' AND started_at<=? AND escalation_level<2) ORDER BY created_at LIMIT 100",
        String.class, Timestamp.from(now.minus(Duration.ofHours(24))),
        Timestamp.from(now.minus(Duration.ofHours(48))));
  }

  /** A separate proxied call makes each order an independent retryable transaction. */
  @Transactional
  public boolean escalate(String id) {
    var rows = db.queryForList("SELECT * FROM work_orders WHERE id=? FOR UPDATE", id);
    if (rows.isEmpty()) return false;
    var order = rows.getFirst();
    String state = (String) order.get("state");
    int level;
    Timestamp since;
    Duration limit;
    if (state.equals("ASSIGNED")) {
      level = 1; since = (Timestamp) order.get("assigned_at"); limit = Duration.ofHours(24);
    } else if (state.equals("REPAIRING")) {
      level = 2; since = (Timestamp) order.get("started_at"); limit = Duration.ofHours(48);
    } else return false;
    int previous = ((Number) order.get("escalation_level")).intValue();
    var now = clock.instant();
    if (previous >= level || since == null || since.toInstant().plus(limit).isAfter(now)) return false;
    if (db.update("UPDATE work_orders SET escalation_level=?,escalated_at=?"
        + " WHERE id=? AND state=? AND escalation_level=?", level, Timestamp.from(now), id, state, previous) != 1)
      return false;
    db.update("INSERT INTO notifications(id,user_id,email,message) VALUES(?,?,?,?)",
        Ids.uuid(), order.get("reporter_id"), order.get("reporter_email"),
        "工单「" + order.get("title") + "」已超时升级，管理员将跟进处理");
    db.update("INSERT INTO audit_events(id,actor_id,action,order_id,request_id) VALUES(?,?,?,?,?)",
        Ids.uuid(), "SYSTEM", "ESCALATED_LEVEL_" + level, id, Trace.id());
    return true;
  }
}

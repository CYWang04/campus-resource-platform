package cn.edu.fudan.campus.m1;

import cn.edu.fudan.campus.common.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
public class ReservationPolicy {
  private final JdbcTemplate db;
  private final Identity identity;
  private final Clock clock;

  public ReservationPolicy(JdbcTemplate db, Identity identity, Clock clock) {
    this.db = db;
    this.identity = identity;
    this.clock = clock;
  }

  public record Settings(@Min(1) @Max(12) int maxDurationHours,
      @Min(1) @Max(30) int maxAdvanceDays, @Min(1) long version) {}

  public Instant now() { return clock.instant(); }

  public Settings current() {
    return db.queryForObject("SELECT * FROM reservation_settings WHERE id=1",
        (row, n) -> new Settings(row.getInt("max_duration_hours"), row.getInt("max_advance_days"), row.getLong("version")));
  }

  @GetMapping("/api/v1/reservation-policy")
  public Object read(@RequestHeader(value="Authorization", required=false) String auth) {
    identity.require(auth, "resources:read");
    return Api.ok(current());
  }

  @PutMapping("/api/v1/admin/reservation-policy")
  @Transactional
  public Object update(@RequestHeader(value="Authorization", required=false) String auth,
      @Valid @RequestBody Settings input) {
    var actor = identity.require(auth, "resources:manage");
    int changed = db.update("UPDATE reservation_settings SET max_duration_hours=?,max_advance_days=?,version=version+1 WHERE id=1 AND version=?",
        input.maxDurationHours(), input.maxAdvanceDays(), input.version());
    if (changed != 1) throw Api.fail(409, "POLICY_VERSION_CONFLICT", "预约配置已变化，请刷新后重试");
    db.update("INSERT INTO resource_audit_events(id,actor_id,action,entity_type,entity_id,reason,request_id) VALUES(?,?,'POLICY_UPDATED','POLICY','1',?,?)",
        Ids.uuid(), actor.id(), "单次上限="+input.maxDurationHours()+"小时，预约窗口="+input.maxAdvanceDays()+"天", Trace.id());
    return Api.ok(current());
  }

  public void validate(Instant start, Instant end, boolean advance) {
    validate(start, end, advance, current(), now());
  }

  static void validate(Instant start, Instant end, boolean advance, Settings settings, Instant now) {
    if (start == null || end == null || start.getNano()!=0 || end.getNano()!=0
        || start.getEpochSecond()%3600!=0 || end.getEpochSecond()%3600!=0)
      throw Api.fail(400, "WHOLE_HOUR_REQUIRED", "预约时间必须为整点");
    Duration duration=Duration.between(start,end);
    if (duration.isNegative() || duration.isZero() || duration.compareTo(Duration.ofHours(settings.maxDurationHours()))>0)
      throw Api.fail(400, "INVALID_DURATION", "预约时长必须为1至"+settings.maxDurationHours()+"小时");
    if (advance && !start.isAfter(now)) throw Api.fail(409,"START_IN_PAST","预约开始时间必须晚于当前时间");
    if (advance && start.isAfter(now.plus(Duration.ofDays(settings.maxAdvanceDays()))))
      throw Api.fail(409,"START_TOO_LATE","只能预约未来"+settings.maxAdvanceDays()+"天内的时段");
  }

  static boolean checkInOpen(Instant now, Instant start) {
    return !now.isBefore(start) && now.isBefore(start.plus(Duration.ofMinutes(15)));
  }
}

package cn.edu.fudan.campus.m1;

import cn.edu.fudan.campus.common.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class Resources {
  private final JdbcTemplate db;
  private final Identity identity;
  private final ObjectMapper json;
  private final Reservations reservations;
  private final ReservationWorker reservationWorker;
  private final ReservationPolicy policy;
  private final ResourceAudit audit;

  public Resources(JdbcTemplate db, Identity identity, ObjectMapper json, Reservations reservations,
      ReservationWorker reservationWorker, ReservationPolicy policy, ResourceAudit audit) {
    this.db=db; this.identity=identity; this.json=json; this.reservations=reservations;
    this.reservationWorker=reservationWorker; this.policy=policy; this.audit=audit;
  }

  @GetMapping("/api/v1/rooms")
  public Object rooms(@RequestHeader(value="Authorization",required=false) String auth,
      @RequestParam(required=false) String building, @RequestParam(required=false) String powerType,
      @RequestParam(required=false) Instant startAt, @RequestParam(required=false) Instant endAt) {
    var actor=identity.require(auth,"resources:read");
    window(startAt,endAt);
    power(powerType);
    StringBuilder sql=new StringBuilder("SELECT DISTINCT r.* FROM rooms r LEFT JOIN seats s ON s.room_id=r.id WHERE r.active=TRUE AND (r.department_code IS NULL OR r.department_code=? OR ?)");
    List<Object> args=new ArrayList<>(Arrays.asList(actor.departmentCode(),actor.permissions().contains("resources:manage")));
    if(building!=null&&!building.isBlank()){sql.append(" AND r.building=?");args.add(building.trim());}
    if(powerType!=null&&!powerType.isBlank()){sql.append(" AND s.power_type=? AND s.active=TRUE");args.add(powerType);}
    sql.append(" ORDER BY r.name,r.id");
    var items=db.queryForList(sql.toString(),args.toArray());
    for(var r:items){
      Instant from=startAt==null?policy.now():startAt, to=endAt==null?from:endAt;
      var effects=effects((String)r.get("id"),from,to);
      String reason=RoomRules.unavailable(r,from,to);
      if(reason==null&&!effects.isEmpty())reason=effectReason(effects);
      String seatsSql="SELECT COUNT(*) FROM seats s WHERE s.room_id=? AND s.active=TRUE";
      List<Object> seatArgs=new ArrayList<>(List.of(r.get("id")));
      if(powerType!=null&&!powerType.isBlank()){seatsSql+=" AND s.power_type=?";seatArgs.add(powerType);}
      int capacity=db.queryForObject(seatsSql,Integer.class,seatArgs.toArray());
      String overlap=from.equals(to)?"r.start_at<=?":"r.start_at<?";
      var availableArgs=new ArrayList<>(seatArgs);availableArgs.add(Timestamp.from(to));availableArgs.add(Timestamp.from(from));
      int available=db.queryForObject(seatsSql+" AND NOT EXISTS(SELECT 1 FROM reservations r WHERE r.seat_id=s.id AND r.status IN ('CONFIRMED','CHECKED_IN') AND "+overlap+" AND r.end_at>?)",Integer.class,availableArgs.toArray());
      if(reason==null&&available==0)reason="NO_AVAILABLE_SEATS";
      r.put("seatCount",capacity);r.put("availableSeats",reason==null?available:0);
      r.put("available",reason==null);
      r.put("availabilityReason",reason==null?"AVAILABLE":reason);
      r.put("effects",effects);
    }
    return Api.ok(Map.of("items",items,"total",items.size(),"page",1,"pageSize",Math.max(1,items.size())));
  }

  @GetMapping("/api/v1/rooms/{id}/seats")
  public Object seats(@RequestHeader(value="Authorization",required=false) String auth,
      @PathVariable String id, @RequestParam(required=false) Instant startAt,
      @RequestParam(required=false) Instant endAt, @RequestParam(required=false) String powerType) {
    var actor=identity.require(auth,"resources:read");
    var room=room(id,false); RoomRules.access(room,actor); window(startAt,endAt);power(powerType);
    Instant from=startAt==null?policy.now():startAt, to=endAt==null?from:endAt;
    String roomReason=RoomRules.unavailable(room,from,to);
    var limits=effects(id,from,to);
    if(roomReason==null&&!limits.isEmpty())roomReason=effectReason(limits);
    String overlap=from.equals(to)?"r.start_at<=?":"r.start_at<?";
    String sql="SELECT s.id,s.code,s.power_type AS powerType,EXISTS(SELECT 1 FROM reservations r WHERE r.seat_id=s.id AND r.status IN ('CONFIRMED','CHECKED_IN') AND "+overlap+" AND r.end_at>?) AS occupied FROM seats s WHERE s.room_id=? AND s.active=TRUE";
    List<Object> args=new ArrayList<>(Arrays.asList(Timestamp.from(to),Timestamp.from(from),id));
    if(powerType!=null&&!powerType.isBlank()){sql+=" AND s.power_type=?";args.add(powerType);}
    var rows=db.queryForList(sql+" ORDER BY s.code",args.toArray());
    for(var seat:rows){
      Object occupiedValue=seat.remove("occupied");
      boolean occupied=Boolean.TRUE.equals(occupiedValue)||(occupiedValue instanceof Number n && n.intValue()!=0);
      String reason=roomReason!=null?roomReason:occupied?"RESERVATION_CONFLICT":null;
      seat.put("available",reason==null);seat.put("unavailableReason",reason==null?"AVAILABLE":reason);
    }
    return Api.ok(rows);
  }

  @GetMapping("/api/v1/rooms/{id}/check-in-code")
  public Object checkinCode(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable String id){
    identity.require(auth,"resources:manage");room(id,false);
    var date=LocalDate.ofInstant(policy.now(),RoomRules.ZONE);
    return Api.ok(Map.of("roomId",id,"date",date.toString(),"code",reservations.checkinCode(id,date)));
  }

  @GetMapping("/api/v1/admin/rooms")
  public Object adminRooms(@RequestHeader(value="Authorization",required=false)String auth){
    identity.require(auth,"resources:manage");
    var rows=db.queryForList("SELECT * FROM rooms ORDER BY name,id");
    return Api.ok(Map.of("items",rows,"total",rows.size()));
  }

  @GetMapping("/api/v1/admin/rooms/{id}/seats")
  public Object adminSeats(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable String id){
    identity.require(auth,"resources:manage");room(id,false);
    return Api.ok(db.queryForList("SELECT * FROM seats WHERE room_id=? ORDER BY code",id));
  }

  public record RoomInput(@NotBlank @Size(max=100)String name,@NotBlank @Size(max=100)String building,
      @Size(max=40)String departmentCode,@NotNull LocalTime openTime,@NotNull LocalTime closeTime,boolean active){}
  public record SeatInput(@NotBlank String roomId,@NotBlank @Size(max=32)String code,
      @NotBlank @Pattern(regexp="FIXED|RAIL|NONE")String powerType,boolean active){}

  @PostMapping("/api/v1/admin/rooms")
  @Transactional(isolation=Isolation.READ_COMMITTED)
  public Object createRoom(@RequestHeader(value="Authorization",required=false)String auth,@Valid @RequestBody RoomInput input){
    var actor=identity.require(auth,"resources:manage");hours(input);
    String id=Ids.uuid();
    db.update("INSERT INTO rooms(id,name,building,department_code,open_time,close_time,active) VALUES(?,?,?,?,?,?,?)",
        id,input.name().trim(),input.building().trim(),RoomRules.department(input.departmentCode()),input.openTime(),input.closeTime(),input.active());
    audit.record(actor.id(),"ROOM_CREATED","ROOM",id,"登记教室");
    return Api.ok(room(id,false));
  }

  @PutMapping("/api/v1/admin/rooms/{id}")
  @Transactional(isolation=Isolation.READ_COMMITTED)
  public Object updateRoom(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable String id,@Valid @RequestBody RoomInput input){
    var actor=identity.require(auth,"resources:manage");var old=room(id,true);hours(input);
    var proposed=new HashMap<>(old);
    proposed.put("active",input.active());proposed.put("open_time",java.sql.Time.valueOf(input.openTime()));
    proposed.put("close_time",java.sql.Time.valueOf(input.closeTime()));
    String department=RoomRules.department(input.departmentCode());
    boolean restrictDepartment=department!=null&&!Objects.equals(old.get("department_code"),department);
    var bookings=db.queryForList("SELECT start_at,end_at FROM reservations WHERE room_id=? AND status IN ('CONFIRMED','CHECKED_IN') AND end_at>CURRENT_TIMESTAMP FOR UPDATE",id);
    var occupancies=db.queryForList("SELECT start_at,end_at FROM resource_effects WHERE room_id=? AND source_type='M2_ROOM_APPLICATION' AND state='ACTIVE' AND end_at>CURRENT_TIMESTAMP FOR UPDATE",id);
    for(var booking:bookings) protectChange(proposed,booking,restrictDepartment);
    for(var occupation:occupancies) protectChange(proposed,occupation,restrictDepartment);
    boolean changed=restrictDepartment||!Objects.equals(old.get("department_code"),department)
        ||!Objects.equals(old.get("open_time"),java.sql.Time.valueOf(input.openTime()))
        ||!Objects.equals(old.get("close_time"),java.sql.Time.valueOf(input.closeTime()))
        ||!Objects.equals(old.get("active"),input.active());
    db.update("UPDATE rooms SET name=?,building=?,department_code=?,open_time=?,close_time=?,active=?,availability_version=availability_version+? WHERE id=?",
        input.name().trim(),input.building().trim(),department,input.openTime(),input.closeTime(),input.active(),changed?1:0,id);
    audit.record(actor.id(),"ROOM_UPDATED","ROOM",id,"更新教室规则或启用状态");
    return Api.ok(room(id,false));
  }

  @PostMapping("/api/v1/admin/seats")
  @Transactional(isolation=Isolation.READ_COMMITTED)
  public Object createSeat(@RequestHeader(value="Authorization",required=false)String auth,@Valid @RequestBody SeatInput input){
    var actor=identity.require(auth,"resources:manage");room(input.roomId(),true);
    String id=Ids.uuid();
    db.update("INSERT INTO seats(id,room_id,code,power_type,active) VALUES(?,?,?,?,?)",id,input.roomId(),input.code().trim(),input.powerType(),input.active());
    bump(input.roomId());audit.record(actor.id(),"SEAT_CREATED","SEAT",id,"登记座位");
    return Api.ok(seat(id));
  }

  @PutMapping("/api/v1/admin/seats/{id}")
  @Transactional(isolation=Isolation.READ_COMMITTED)
  public Object updateSeat(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable String id,@Valid @RequestBody SeatInput input){
    var actor=identity.require(auth,"resources:manage");Ids.uuid(id);
    var old=seat(id);
    if(!Objects.equals(old.get("room_id"),input.roomId())) throw Api.fail(409,"SEAT_ROOM_IMMUTABLE","不能直接移动座位所属教室，请新建座位并停用原座位");
    room(input.roomId(),true);
    db.queryForList("SELECT id FROM seats WHERE id=? FOR UPDATE",id);
    if(!input.active()){
      boolean bookings=!db.queryForList("SELECT id FROM reservations WHERE seat_id=? AND status IN ('CONFIRMED','CHECKED_IN') AND end_at>CURRENT_TIMESTAMP FOR UPDATE",id).isEmpty();
      boolean occupied=!db.queryForList("SELECT source_id FROM resource_effects WHERE room_id=? AND source_type='M2_ROOM_APPLICATION' AND state='ACTIVE' AND end_at>CURRENT_TIMESTAMP FOR UPDATE",input.roomId()).isEmpty();
      if(bookings||occupied)throw Api.fail(409,"ACTIVE_RESERVATIONS","存在有效预约或整室占用，不能停用座位");
    }
    db.update("UPDATE seats SET code=?,power_type=?,active=? WHERE id=?",input.code().trim(),input.powerType(),input.active(),id);
    bump(input.roomId());audit.record(actor.id(),"SEAT_UPDATED","SEAT",id,"更新座位或启用状态");
    return Api.ok(seat(id));
  }

  @GetMapping("/api/v1/rooms/{id}/availability")
  public Object availability(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable String id){
    var actor=identity.require(auth,"resources:read");var room=room(id,false);RoomRules.access(room,actor);
    Instant now=policy.now();var effects=effects(id,now,now);
    String reason=RoomRules.unavailable(room,now,now);
    if(reason==null&&!effects.isEmpty())reason=effectReason(effects);
    return Api.ok(Map.of("roomId",id,"availabilityVersion",room.get("availability_version"),"available",reason==null,"availabilityReason",reason==null?"AVAILABLE":reason,"effects",effects));
  }

  public record Effect(@NotBlank String operationId,@Min(1)long sourceVersion,@NotBlank String roomId,
      @Pattern(regexp="ACTIVE|RELEASED") @NotNull String desiredState,Instant startAt,Instant endAt,
      Long expectedAvailabilityVersion,@NotBlank @Size(max=500)String reason){}

  @PutMapping("/internal/v1/resource-effects/{sourceType}/{sourceId}")
  @Transactional(isolation=Isolation.READ_COMMITTED)
  public Object effect(@RequestHeader(value="X-Service-Key",required=false)String key,
      @PathVariable String sourceType,@PathVariable String sourceId,@Valid @RequestBody Effect e) throws Exception {
    identity.internal(key);
    if(!Set.of("M3_MAINTENANCE","M2_ROOM_APPLICATION").contains(sourceType))throw Api.fail(403,"SOURCE_FORBIDDEN","来源类型不允许");
    Ids.uuid(sourceId);Ids.uuid(e.roomId());Ids.uuid(e.operationId());
    var lockedRoom=room(e.roomId(),true);
    String fingerprint=Ids.hash(sourceType+sourceId+json.writeValueAsString(e));
    var previous=db.queryForList("SELECT * FROM operations WHERE id=?",e.operationId());
    if(!previous.isEmpty()){
      var old=previous.getFirst();
      if(!fingerprint.equals(old.get("fingerprint")))throw Api.fail(409,"IDEMPOTENCY_CONFLICT","操作编号已经用于其他内容");
      Map<String,Object> result=json.readValue((String)old.get("result_json"),Map.class);
      var current=db.queryForMap("SELECT state,invalidated_by FROM resource_effects WHERE source_type=? AND source_id=?",sourceType,sourceId);
      result.put("currentSourceState",current.get("state"));result.put("invalidatedBy",current.get("invalidated_by"));
      return Api.ok(result);
    }
    var sources=db.queryForList("SELECT * FROM resource_effects WHERE source_type=? AND source_id=? FOR UPDATE",sourceType,sourceId);
    boolean terminal=false;
    if(!sources.isEmpty()){
      var old=sources.getFirst();
      if(!e.roomId().equals(old.get("room_id")))throw Api.fail(409,"SOURCE_ROOM_CONFLICT","来源不能更换教室");
      if(e.sourceVersion()<=((Number)old.get("source_version")).longValue())throw Api.fail(409,"STALE_SOURCE_VERSION","来源版本过期");
      terminal="CANCELLED".equals(old.get("state"));
      if(terminal&&e.desiredState().equals("ACTIVE"))throw Api.fail(409,"SOURCE_TERMINAL","该申请已因维修终止，必须重新申请");
    }
    Instant start=e.startAt()==null?policy.now():e.startAt();
    if(e.endAt()!=null&&!e.endAt().isAfter(start))throw Api.fail(400,"INVALID_TIME","结束时间必须晚于开始时间");
    if(sourceType.equals("M2_ROOM_APPLICATION")&&e.desiredState().equals("ACTIVE")){
      if(e.startAt()==null||e.endAt()==null||e.startAt().getNano()!=0||e.endAt().getNano()!=0||e.startAt().getEpochSecond()%3600!=0||e.endAt().getEpochSecond()%3600!=0)
        throw Api.fail(400,"TIME_RANGE_REQUIRED","整教室占用必须提供整点时段");
      if(!e.endAt().isAfter(policy.now()))throw Api.fail(409,"APPLICATION_ENDED","使用时段已经结束");
      RoomRules.requireOpen(lockedRoom,e.startAt(),e.endAt());
      if(e.expectedAvailabilityVersion()==null||e.expectedAvailabilityVersion().longValue()!=((Number)lockedRoom.get("availability_version")).longValue())
        throw Api.fail(409,"RESOURCE_VERSION_CONFLICT","教室资源版本已变化，请重新提交申请");
      if(!db.queryForList("SELECT id FROM reservations WHERE room_id=? AND status IN ('CONFIRMED','CHECKED_IN') AND start_at<? AND end_at>? FOR UPDATE",e.roomId(),Timestamp.from(e.endAt()),Timestamp.from(start)).isEmpty())
        throw Api.fail(409,"PERSONAL_RESERVATION_CONFLICT","该时段已有个人座位预约，不能批准整教室申请");
      if(!db.queryForList("SELECT source_id FROM resource_effects WHERE room_id=? AND state='ACTIVE' AND NOT(source_type=? AND source_id=?) AND start_at<? AND (end_at IS NULL OR end_at>?) FOR UPDATE",e.roomId(),sourceType,sourceId,Timestamp.from(e.endAt()),Timestamp.from(start)).isEmpty())
        throw Api.fail(409,"ROOM_EFFECT_CONFLICT","该时段已有整教室占用或维修限制");
    }
    String state=terminal?"CANCELLED":e.desiredState();
    db.update("INSERT INTO resource_effects(source_type,source_id,source_version,room_id,state,start_at,end_at,reason) VALUES(?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE source_version=VALUES(source_version),state=VALUES(state),start_at=VALUES(start_at),end_at=VALUES(end_at),reason=VALUES(reason)",
        sourceType,sourceId,e.sourceVersion(),e.roomId(),state,Timestamp.from(start),e.endAt()==null?null:Timestamp.from(e.endAt()),e.reason());
    if(sourceType.equals("M3_MAINTENANCE")&&state.equals("ACTIVE"))cancelForMaintenance(e.roomId(),sourceId,start,e.endAt());
    bump(e.roomId());
    var result=Map.of("operationId",e.operationId(),"status","APPLIED","sourceState",state,"currentSourceState",state,"roomId",e.roomId());
    db.update("INSERT INTO operations(id,fingerprint,result_json) VALUES(?,?,?)",e.operationId(),fingerprint,json.writeValueAsString(result));
    audit.record(sourceType,state,sourceType,sourceId,e.reason());
    return Api.ok(result);
  }

  private void cancelForMaintenance(String roomId,String maintenance,Instant start,Instant end){
    Instant from=start.isBefore(policy.now())?policy.now():start;
    String overlap=" AND end_at>?"+(end==null?"":" AND start_at<?");
    List<Object> args=new ArrayList<>(Arrays.asList(roomId,Timestamp.from(from)));
    if(end!=null)args.add(Timestamp.from(end));
    for(var booking:db.queryForList("SELECT * FROM reservations WHERE room_id=? AND status IN ('CONFIRMED','CHECKED_IN')"+overlap+" FOR UPDATE",args.toArray())){
      db.update("UPDATE reservations SET status='CANCELLED_MAINTENANCE',cancelled_at=CURRENT_TIMESTAMP(6) WHERE id=?",booking.get("id"));
      reservationWorker.queue(booking,"MAINTENANCE_CANCELLED","预约因教室紧急维修而取消，座位已释放且不记录违约");
      audit.record("M3_MAINTENANCE","MAINTENANCE_CANCELLED","M1_RESERVATION",(String)booking.get("id"),"维修来源 "+maintenance);
    }
    for(var source:db.queryForList("SELECT * FROM resource_effects WHERE room_id=? AND source_type='M2_ROOM_APPLICATION' AND state='ACTIVE'"+overlap+" FOR UPDATE",args.toArray())){
      db.update("UPDATE resource_effects SET state='CANCELLED',invalidated_by=? WHERE source_type='M2_ROOM_APPLICATION' AND source_id=?",maintenance,source.get("source_id"));
      db.update("INSERT IGNORE INTO resource_cancellation_tasks(id,application_id,room_id,source_version,maintenance_source_id,request_id) VALUES(?,?,?,?,?,?)",Ids.uuid(),source.get("source_id"),roomId,source.get("source_version"),maintenance,Trace.id());
      audit.record("M3_MAINTENANCE","MAINTENANCE_CANCELLED","M2_ROOM_APPLICATION",(String)source.get("source_id"),"维修来源 "+maintenance);
    }
  }

  private List<Map<String,Object>> effects(String id,Instant start,Instant end){
    // A point query includes effects starting exactly now; interval queries are half-open.
    if(start.equals(end))return db.queryForList("SELECT source_type,source_id,reason,start_at,end_at FROM resource_effects WHERE room_id=? AND state='ACTIVE' AND start_at<=? AND (end_at IS NULL OR end_at>?)",id,Timestamp.from(start),Timestamp.from(start));
    return db.queryForList("SELECT source_type,source_id,reason,start_at,end_at FROM resource_effects WHERE room_id=? AND state='ACTIVE' AND start_at<? AND (end_at IS NULL OR end_at>?)",id,Timestamp.from(end),Timestamp.from(start));
  }
  private String effectReason(List<Map<String,Object>> effects){return effects.stream().anyMatch(e->e.get("source_type").equals("M3_MAINTENANCE"))?"MAINTENANCE":"ROOM_APPLICATION";}
  private void window(Instant start,Instant end){if((start==null)!=(end==null))throw Api.fail(400,"TIME_RANGE_REQUIRED","开始和结束时间必须同时提供");if(start!=null)policy.validate(start,end,true);}
  private void power(String value){if(value!=null&&!value.isBlank()&&!Set.of("FIXED","RAIL","NONE").contains(value))throw Api.fail(400,"INVALID_POWER_TYPE","插座类型不正确");}
  private void hours(RoomInput i){if(!i.closeTime().isAfter(i.openTime()))throw Api.fail(400,"INVALID_OPEN_HOURS","关闭时间必须晚于开放时间");}
  private void protectChange(Map<String,Object> room,Map<String,Object> booking,boolean department){if(department||RoomRules.unavailable(room,((Timestamp)booking.get("start_at")).toInstant(),((Timestamp)booking.get("end_at")).toInstant())!=null)throw Api.fail(409,"ACTIVE_RESERVATIONS","调整将影响有效预约或整室占用，请先处理相关业务");}
  private void bump(String id){db.update("UPDATE rooms SET availability_version=availability_version+1 WHERE id=?",id);}
  private Map<String,Object> seat(String id){Ids.uuid(id);var rows=db.queryForList("SELECT * FROM seats WHERE id=?",id);if(rows.isEmpty())throw Api.fail(404,"SEAT_NOT_FOUND","座位不存在");return rows.getFirst();}
  private Map<String,Object> room(String id,boolean lock){Ids.uuid(id);var rows=db.queryForList("SELECT * FROM rooms WHERE id=?"+(lock?" FOR UPDATE":""),id);if(rows.isEmpty())throw Api.fail(404,"ROOM_NOT_FOUND","教室不存在");return rows.getFirst();}
}

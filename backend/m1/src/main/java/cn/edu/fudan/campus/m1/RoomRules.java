package cn.edu.fudan.campus.m1;

import cn.edu.fudan.campus.common.*;
import java.sql.Time;
import java.time.*;
import java.util.*;

final class RoomRules {
  static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
  private RoomRules() {}

  static void access(Map<String,Object> room, Identity.Actor actor) {
    if (room.get("department_code")!=null
        && !Objects.equals(room.get("department_code"),actor.departmentCode())
        && !actor.permissions().contains("resources:manage"))
      throw Api.fail(403,"DEPARTMENT_FORBIDDEN","该自习室仅向所属院系开放");
  }

  static String unavailable(Map<String,Object> room, Instant start, Instant end) {
    if (!Boolean.TRUE.equals(room.get("active"))) return "RESOURCE_INACTIVE";
    var from=LocalDateTime.ofInstant(start,ZONE);
    var to=LocalDateTime.ofInstant(end,ZONE);
    if ((start.equals(end) && !from.toLocalTime().isBefore(((Time)room.get("close_time")).toLocalTime()))
        || !from.toLocalDate().equals(to.toLocalDate())
        || from.toLocalTime().isBefore(((Time)room.get("open_time")).toLocalTime())
        || to.toLocalTime().isAfter(((Time)room.get("close_time")).toLocalTime())) return "OUTSIDE_OPEN_HOURS";
    return null;
  }

  static void requireOpen(Map<String,Object> room, Instant start, Instant end) {
    String reason=unavailable(room,start,end);
    if (reason!=null) throw Api.fail(409,reason,reason.equals("RESOURCE_INACTIVE")?"教室已停用":"预约时段不在教室开放时间内");
  }

  static String department(String value) {
    return value==null || value.isBlank()?null:value.trim();
  }
}

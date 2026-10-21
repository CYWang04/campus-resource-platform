package cn.edu.fudan.campus.m3;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.edu.fudan.campus.common.Api;
import cn.edu.fudan.campus.common.Identity;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class OrdersTest {
  private static final String ID = "10000000-0000-0000-0000-000000000001";

  @Test
  void repairerCannotAcceptOwnWorkEvenWithAcceptancePermission() {
    JdbcTemplate db = mock(JdbcTemplate.class);
    when(db.queryForList(contains("FROM commands"), anyString(), anyString()))
        .thenReturn(List.of());
    when(db.queryForList(contains("FOR UPDATE"), anyString()))
        .thenReturn(List.of(Map.of("state", "AWAITING_ACCEPTANCE", "repairer_id", ID)));
    var actor =
        new Identity.Actor(
            ID, "repairer", "维修人员", "test@campus.test", List.of("workorders:accept"));
    var error =
        assertThrows(
            Api.Failure.class, () -> new Orders(db).action(actor, "test-key-123", ID, "accept"));
    assertEquals(403, error.status);
    assertEquals("SELF_ACCEPTANCE", error.code);
    verify(db, never()).update(anyString(), any(Object[].class));
  }

  @Test
  void acceptanceBeforeCompletionCannotReleaseResource() {
    JdbcTemplate db = mock(JdbcTemplate.class);
    when(db.queryForList(contains("FROM commands"), anyString(), anyString()))
        .thenReturn(List.of());
    when(db.queryForList(contains("FOR UPDATE"), anyString()))
        .thenReturn(List.of(Map.of("state", "REPAIRING", "repairer_id", "someone-else")));
    var actor =
        new Identity.Actor(ID, "manager", "管理员", "test@campus.test", List.of("workorders:accept"));
    var error =
        assertThrows(
            Api.Failure.class, () -> new Orders(db).action(actor, "test-key-456", ID, "accept"));
    assertEquals(409, error.status);
    assertEquals("INVALID_STATE", error.code);
    verify(db, never()).update(anyString(), any(Object[].class));
  }
}

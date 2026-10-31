package cn.edu.fudan.campus.m3;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

@SpringJUnitConfig(OrdersDatabaseTest.Database.class)
class EscalationsDatabaseTest {
  static final Instant NOW = Instant.parse("2026-10-30T08:00:00Z");
  @Autowired JdbcTemplate db;
  @Autowired Orders orders;
  @Autowired WorkOrderEscalations escalations;
  OrdersDatabaseTest fixture;

  @BeforeEach void prepare() {
    fixture = new OrdersDatabaseTest(); fixture.db = db; fixture.orders = orders; fixture.reset();
  }
  String aged(String state, long hours) {
    String id = state.equals("ASSIGNED") ? fixture.assigned() : fixture.repairing();
    String column = state.equals("ASSIGNED") ? "assigned_at" : "started_at";
    db.update("UPDATE work_orders SET " + column + "=? WHERE id=?", Timestamp.from(NOW.minus(Duration.ofHours(hours))), id);
    return id;
  }
  int level(String id) {
    return db.queryForObject("SELECT escalation_level FROM work_orders WHERE id=?", Integer.class, id);
  }

  @ParameterizedTest
  @CsvSource({"ASSIGNED,24,1", "REPAIRING,48,2"})
  void exactDeadlineEscalatesOnce(String state, long hours, int expected) {
    String id = aged(state, hours); int notices = fixture.count("notifications"), audits = fixture.count("audit_events");
    assertEquals(List.of(id), escalations.due());
    assertTrue(escalations.escalate(id)); assertFalse(escalations.escalate(id));
    assertEquals(expected, level(id));
    assertEquals(fixture.count("notifications"), notices + 1);
    assertEquals(fixture.count("audit_events"), audits + 1);
    assertEquals(Timestamp.from(NOW), fixture.row(id).get("escalated_at"));
    assertTrue(escalations.due().isEmpty());
  }

  @ParameterizedTest
  @CsvSource({"ASSIGNED,24", "REPAIRING,48"})
  void oneSecondBeforeDeadlineDoesNotEscalate(String state, long hours) {
    String id = aged(state, hours);
    String column = state.equals("ASSIGNED") ? "assigned_at" : "started_at";
    db.update("UPDATE work_orders SET " + column + "=? WHERE id=?",
        Timestamp.from(NOW.minus(Duration.ofHours(hours)).plusSeconds(1)), id);
    assertTrue(escalations.due().isEmpty()); assertFalse(escalations.escalate(id)); assertEquals(0, level(id));
  }

  @ParameterizedTest
  @ValueSource(strings = {"CLOSED", "CANCELLED", "AWAITING_ACCEPTANCE", "START_PENDING", "RELEASE_PENDING"})
  void nonRepairStatesAreNotEscalated(String state) {
    String id = aged("REPAIRING", 100);
    db.update("UPDATE work_orders SET state=? WHERE id=?", state, id);
    int notices = fixture.count("notifications"), audits = fixture.count("audit_events");
    assertTrue(escalations.due().isEmpty()); assertFalse(escalations.escalate(id)); assertEquals(0, level(id));
    assertEquals(notices, fixture.count("notifications")); assertEquals(audits, fixture.count("audit_events"));
  }

  @Test void rechecksOrderThatWasCancelledAfterScan() {
    String id = aged("ASSIGNED", 30); assertTrue(escalations.due().contains(id));
    orders.cancel(OrdersDatabaseTest.STUDENT, fixture.key(), id);
    assertFalse(escalations.escalate(id)); assertEquals(0, level(id));
  }

  @Test void repairDeadlineCanAdvanceAnAlreadyEscalatedAssignment() {
    String id = aged("ASSIGNED", 24); assertTrue(escalations.escalate(id));
    orders.action(OrdersDatabaseTest.TECHNICIAN, fixture.key(), id, "start"); fixture.apply(id, "ACTIVE");
    db.update("UPDATE work_orders SET started_at=? WHERE id=?", Timestamp.from(NOW.minus(Duration.ofHours(48))), id);
    assertTrue(escalations.escalate(id)); assertEquals(2, level(id));
    assertFalse(escalations.escalate(id));
  }

  @Test void concurrentScansProduceOnlyOneNoticeAndAudit() throws Exception {
    String id = aged("ASSIGNED", 24); int notices = fixture.count("notifications"), audits = fixture.count("audit_events");
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      Callable<Boolean> action = () -> { start.await(); return escalations.escalate(id); };
      var first = pool.submit(action); var second = pool.submit(action); start.countDown();
      int changed = (first.get(10, TimeUnit.SECONDS) ? 1 : 0) + (second.get(10, TimeUnit.SECONDS) ? 1 : 0);
      assertEquals(1, changed);
    }
    assertEquals(1, level(id)); assertEquals(notices + 1, fixture.count("notifications"));
    assertEquals(audits + 1, fixture.count("audit_events"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"notifications", "audit_events"})
  void insertFailureRollsBackEntireEscalationAndCanBeRetried(String table) {
    String id = aged("ASSIGNED", 24); int notices = fixture.count("notifications"), audits = fixture.count("audit_events");
    String condition = table.equals("notifications") ? "message NOT LIKE '%已超时升级%'" : "action <> 'ESCALATED_LEVEL_1'";
    db.execute("ALTER TABLE " + table + " ADD CONSTRAINT fail_escalation CHECK (" + condition + ")");
    try {
      assertThrows(DataAccessException.class, () -> escalations.escalate(id));
      assertEquals(0, level(id)); assertNull(fixture.row(id).get("escalated_at"));
      assertEquals(notices, fixture.count("notifications")); assertEquals(audits, fixture.count("audit_events"));
      assertTrue(escalations.due().contains(id));
    } finally { db.execute("ALTER TABLE " + table + " DROP CONSTRAINT fail_escalation"); }
    assertTrue(escalations.escalate(id)); assertEquals(1, level(id));
  }

  @Test void scanContinuesAfterOneOrderFails() {
    var service = mock(WorkOrderEscalations.class);
    when(service.due()).thenReturn(List.of("first", "second"));
    when(service.escalate("first")).thenThrow(new DataAccessResourceFailureException("Test database failure"));
    when(service.escalate("second")).thenReturn(true);
    new EscalationWorker(service).run();
    verify(service).escalate("first"); verify(service).escalate("second");
  }
}

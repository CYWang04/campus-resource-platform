package cn.edu.fudan.campus.m3;

import static org.junit.jupiter.api.Assertions.*;

import cn.edu.fudan.campus.common.Api;
import cn.edu.fudan.campus.common.Identity;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringJUnitConfig(OrdersDatabaseTest.Database.class)
class OrdersDatabaseTest {
  static final String ROOM = "10000000-0000-0000-0000-000000000001";
  static final Identity.Actor STUDENT = actor("student", "workorders:create");
  static final Identity.Actor MANAGER = actor("manager", "workorders:assign", "workorders:accept", "workorders:read");
  static final Identity.Actor TECHNICIAN = actor("technician", "workorders:repair", "workorders:read");
  @Autowired JdbcTemplate db;
  @Autowired Orders orders;

  @Configuration
  @EnableTransactionManagement
  static class Database {
    @Bean DataSource dataSource() throws java.io.IOException {
      var source = new DriverManagerDataSource(
          "jdbc:h2:mem:m3-orders;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
      // H2 does not support MySQL's multi-column ALTER syntax. Translate only DDL
      // layout; this fixture does not validate MySQL/Flyway migration compatibility.
      String assignmentSchema = new ClassPathResource("db/migration/V2__assignment_acceptance_rating.sql")
          .getContentAsString(java.nio.charset.StandardCharsets.UTF_8)
          .replaceAll(" AFTER [a-z_]+", "")
          .replaceAll(",\\s*ADD COLUMN", "; ALTER TABLE work_orders ADD COLUMN");
      new ResourceDatabasePopulator(
          new ClassPathResource("db/migration/V1__workorders.sql"),
          new org.springframework.core.io.ByteArrayResource(assignmentSchema.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
          new ClassPathResource("db/migration/V3__external_notifications.sql")).execute(source);
      return source;
    }
    @Bean JdbcTemplate jdbcTemplate(DataSource source) { return new JdbcTemplate(source); }
    @Bean PlatformTransactionManager transactionManager(DataSource source) {
      return new DataSourceTransactionManager(source);
    }
    @Bean Orders orders(JdbcTemplate db) { return new Orders(db); }
  }

  @BeforeEach void reset() {
    for (String table : List.of("commands", "sync_tasks", "notifications", "audit_events", "work_orders"))
      db.update("DELETE FROM " + table);
  }

  static Identity.Actor actor(String name, String... permissions) {
    return new Identity.Actor(UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(),
        name, name, name + "@campus.test", "CS", List.of(permissions));
  }
  String key() { return UUID.randomUUID().toString(); }
  String report() {
    return (String) orders.create(STUDENT, key(), new Orders.Report(ROOM, "Test repair", "Broken light")).get("id");
  }
  String assigned() {
    String id = report();
    orders.assign(MANAGER, key(), id,
        new Orders.Assignment(TECHNICIAN.id(), TECHNICIAN.displayName(), TECHNICIAN.email()));
    return id;
  }
  String repairing() {
    String id = assigned(); orders.action(TECHNICIAN, key(), id, "start"); apply(id, "ACTIVE"); return id;
  }
  void apply(String id, String desired) {
    orders.applied(db.queryForObject("SELECT id FROM sync_tasks WHERE order_id=? AND desired_state=?",
        String.class, id, desired));
  }
  Map<String,Object> row(String id) { return db.queryForMap("SELECT * FROM work_orders WHERE id=?", id); }
  int count(String table) { return db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }

  @Test void fullLifecycleWaitsForResourceConfirmationAndAllowsReporterRating() {
    String id = assigned();
    assertEquals("START_PENDING", orders.action(TECHNICIAN, key(), id, "start").get("state"));
    apply(id, "ACTIVE"); assertEquals("REPAIRING", row(id).get("state"));
    orders.complete(TECHNICIAN, key(), id, new Orders.Note("Replaced light"));
    assertEquals("AWAITING_ACCEPTANCE", row(id).get("state"));
    assertEquals("RELEASE_PENDING", orders.action(MANAGER, key(), id, "accept").get("state"));
    apply(id, "RELEASED"); assertEquals("CLOSED", row(id).get("state"));
    orders.rate(STUDENT, key(), id, new Orders.Rating(5, "Fixed"));
    assertEquals(5, ((Number) row(id).get("rating")).intValue());
    assertEquals(2, count("sync_tasks"));
  }

  @Test void createReplayIsDeduplicatedAndChangedBodyIsRejected() {
    String key = key(); var input = new Orders.Report(ROOM, "Test repair", "Broken light");
    var first = orders.create(STUDENT, key, input);
    assertEquals(first.get("id"), orders.create(STUDENT, key, input).get("id"));
    var error = assertThrows(Api.Failure.class,
        () -> orders.create(STUDENT, key, new Orders.Report(ROOM, "Changed", "Broken light")));
    assertEquals(409, error.status); assertEquals(1, count("work_orders")); assertEquals(1, count("audit_events"));
  }

  @Test void onlyAssignedTechnicianCanStartOrComplete() {
    String id = assigned(); var other = actor("other", "workorders:repair", "workorders:read");
    assertEquals("NOT_ASSIGNED", assertThrows(Api.Failure.class,
        () -> orders.action(other, key(), id, "start")).code);
    assertEquals("ASSIGNED", row(id).get("state"));
    orders.action(TECHNICIAN, key(), id, "start"); apply(id, "ACTIVE");
    assertEquals("NOT_ASSIGNED", assertThrows(Api.Failure.class,
        () -> orders.complete(other, key(), id, new Orders.Note("Invalid"))).code);
    assertNull(row(id).get("completed_at"));
  }

  @Test void reporterCanCancelBeforeStartButCannotCancelRepairingOrder() {
    String id = assigned(); orders.cancel(STUDENT, key(), id); assertEquals("CANCELLED", row(id).get("state"));
    String active = repairing();
    assertEquals("INVALID_STATE", assertThrows(Api.Failure.class,
        () -> orders.cancel(STUDENT, key(), active)).code);
    assertEquals("REPAIRING", row(active).get("state"));
  }

  @Test void unprivilegedActorCannotStartOrAccept() {
    String id = assigned();
    assertEquals(403, assertThrows(Api.Failure.class,
        () -> orders.action(STUDENT, key(), id, "start")).status);
    assertEquals("ASSIGNED", row(id).get("state"));
    assertEquals(403, assertThrows(Api.Failure.class,
        () -> orders.action(STUDENT, key(), id, "accept")).status);
    assertEquals(0, count("sync_tasks"));
  }

  @Test void acceptanceReturnKeepsResourceAndAllowsRepairCompletionAgain() {
    String id = repairing(); orders.complete(TECHNICIAN, key(), id, new Orders.Note("First repair"));
    orders.rejectAcceptance(MANAGER, key(), id, new Orders.Note("Still broken"));
    assertEquals("REPAIRING", row(id).get("state")); assertNull(row(id).get("completed_at"));
    assertEquals(1, count("sync_tasks"));
    orders.complete(TECHNICIAN, key(), id, new Orders.Note("Second repair"));
    assertEquals("Second repair", row(id).get("completion_note"));
  }

  @Test void onlyReporterCanReadPrivateOrderOrRateClosedOrder() {
    String id = repairing(); var other = actor("another-student", "workorders:create");
    assertEquals(403, assertThrows(Api.Failure.class, () -> orders.get(id, other)).status);
    orders.complete(TECHNICIAN, key(), id, new Orders.Note("Fixed"));
    orders.action(MANAGER, key(), id, "accept"); apply(id, "RELEASED");
    assertEquals(403, assertThrows(Api.Failure.class,
        () -> orders.rate(other, key(), id, new Orders.Rating(5, "Invalid"))).status);
    orders.rate(STUDENT, key(), id, new Orders.Rating(5, "Good"));
    assertEquals("ALREADY_RATED", assertThrows(Api.Failure.class,
        () -> orders.rate(STUDENT, key(), id, new Orders.Rating(4, "Again"))).code);
  }

  @Test void auditFailureRollsBackStateSyncTaskAndCommandTogether() {
    String id = assigned(); int commands = count("commands"); int audits = count("audit_events");
    db.execute("ALTER TABLE audit_events ADD CONSTRAINT reject_start CHECK (action <> 'START')");
    try {
      assertThrows(org.springframework.dao.DataAccessException.class,
          () -> orders.action(TECHNICIAN, key(), id, "start"));
      assertEquals("ASSIGNED", row(id).get("state")); assertNull(row(id).get("started_at"));
      assertEquals(0, count("sync_tasks")); assertEquals(commands, count("commands"));
      assertEquals(audits, count("audit_events"));
    } finally { db.execute("ALTER TABLE audit_events DROP CONSTRAINT reject_start"); }
  }
}

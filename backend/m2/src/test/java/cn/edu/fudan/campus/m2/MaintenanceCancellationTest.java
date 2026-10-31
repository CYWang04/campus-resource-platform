package cn.edu.fudan.campus.m2;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.edu.fudan.campus.common.Api;
import cn.edu.fudan.campus.common.Identity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringJUnitConfig(MaintenanceCancellationTest.Database.class)
class MaintenanceCancellationTest {
  static final String APP = "20000000-0000-0000-0000-000000000001";
  static final String ROOM = "10000000-0000-0000-0000-000000000001";
  static final String USER = "30000000-0000-0000-0000-000000000001";
  static final String TASK = "40000000-0000-0000-0000-000000000001";
  static final String KEY = "m2-contract-test-service-key-32-characters";
  @Autowired JdbcTemplate db;
  @Autowired RoomApplications applications;
  MockMvc http;
  final ObjectMapper json = new ObjectMapper();

  @Configuration
  @EnableTransactionManagement
  static class Database {
    @Bean DataSource dataSource() {
      var source = new DriverManagerDataSource(
          "jdbc:h2:mem:m2-cancellation;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
      new ResourceDatabasePopulator(
          new ClassPathResource("db/migration/V1__identity.sql"),
          new ClassPathResource("db/migration/V2__room_applications.sql"),
          new ClassPathResource("db/migration/V3__maintenance_cancellations.sql")).execute(source);
      return source;
    }
    @Bean JdbcTemplate jdbcTemplate(DataSource source) { return new JdbcTemplate(source); }
    @Bean PlatformTransactionManager transactionManager(DataSource source) {
      return new DataSourceTransactionManager(source);
    }
    @Bean RoomApplications applications(JdbcTemplate db) { return new RoomApplications(db); }
  }

  @BeforeEach
  void prepare() {
    for (String table : List.of("application_notification_tasks", "application_cancellation_events",
        "application_audit_events", "application_sync_tasks", "application_commands",
        "room_applications", "sessions", "permissions", "users")) db.update("DELETE FROM " + table);
    db.update("INSERT INTO users(id,username,display_name,email,password_hash) VALUES(?,?,?,?,?)",
        USER, "student", "Test student", "student@campus.test", "not-a-login-fixture");
    db.update("INSERT INTO room_applications(id,applicant_id,applicant_email,room_id,room_name,purpose,"
            + "attendee_count,start_at,end_at,state,source_version,expected_availability_version)"
            + " VALUES(?,?,?,?,?,?,?,TIMESTAMP '2027-01-01 10:00:00',TIMESTAMP '2027-01-01 12:00:00',?,1,0)",
        APP, USER, "student@campus.test", ROOM, "Test room", "Meeting", 20, "APPROVED");
    http = MockMvcBuilders.standaloneSetup(new MaintenanceCancellationController(
        new Identity(KEY, "http://unused.invalid"), applications))
        .setControllerAdvice(new Api.Errors()).build();
  }

  RoomApplications.MaintenanceCancellation event() {
    return new RoomApplications.MaintenanceCancellation(UUID.randomUUID().toString(), ROOM, 1,
        "50000000-0000-0000-0000-000000000001", "教室紧急维修");
  }
  String state() { return db.queryForObject("SELECT state FROM room_applications WHERE id=?", String.class, APP); }
  int count(String table) { return db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
  void task(String desired, long version) {
    db.update("UPDATE room_applications SET source_version=? WHERE id=?", version, APP);
    db.update("INSERT INTO application_sync_tasks(id,application_id,room_id,source_version,desired_state,request_id)"
        + " VALUES(?,?,?,?,?,?)", TASK, APP, ROOM, version, desired, "test-request");
  }

  @ParameterizedTest
  @ValueSource(strings = {"APPROVED", "RESOURCE_SYNC_PENDING", "WITHDRAW_SYNC_PENDING"})
  void cancelsEveryResourceOwningStateAndPersistsOneNotice(String initial) {
    db.update("UPDATE room_applications SET state=? WHERE id=?", initial, APP);
    task(initial.equals("WITHDRAW_SYNC_PENDING") ? "RELEASED" : "ACTIVE",
        initial.equals("WITHDRAW_SYNC_PENDING") ? 2 : 1);
    applications.cancelForMaintenance(APP, event());
    assertEquals("CANCELLED_MAINTENANCE", state());
    assertEquals("SUPERSEDED", db.queryForObject("SELECT status FROM application_sync_tasks WHERE id=?", String.class, TASK));
    assertEquals(1, count("application_cancellation_events"));
    assertEquals(1, count("application_audit_events"));
    assertEquals(1, count("application_notification_tasks"));
    assertEquals("PENDING", db.queryForObject("SELECT status FROM application_notification_tasks", String.class));
  }

  @Test
  void identicalReplayAndAnotherMaintenanceSourceDoNotDuplicateNotice() {
    var first = event();
    applications.cancelForMaintenance(APP, first);
    applications.cancelForMaintenance(APP, first);
    applications.cancelForMaintenance(APP, new RoomApplications.MaintenanceCancellation(
        UUID.randomUUID().toString(), ROOM, 1, UUID.randomUUID().toString(), "另一项维修"));
    assertEquals(2, count("application_cancellation_events"));
    assertEquals(1, count("application_audit_events"));
    assertEquals(1, count("application_notification_tasks"));
  }

  @Test
  void eventIdentifierCannotBeReusedWithChangedContent() {
    var first = event(); applications.cancelForMaintenance(APP, first);
    var changed = new RoomApplications.MaintenanceCancellation(first.eventId(), ROOM, 1,
        first.maintenanceSourceId(), "不同内容");
    var error = assertThrows(Api.Failure.class, () -> applications.cancelForMaintenance(APP, changed));
    assertEquals("CANCELLATION_EVENT_CONFLICT", error.code);
    assertEquals(1, count("application_cancellation_events"));
  }

  @Test
  void mismatchedRoomAndUnknownVersionLeaveApplicationUntouched() {
    var first = event();
    var roomError = assertThrows(Api.Failure.class, () -> applications.cancelForMaintenance(APP,
        new RoomApplications.MaintenanceCancellation(first.eventId(), UUID.randomUUID().toString(),
            1, first.maintenanceSourceId(), first.reason())));
    assertEquals("SOURCE_ROOM_CONFLICT", roomError.code);
    var versionError = assertThrows(Api.Failure.class, () -> applications.cancelForMaintenance(APP,
        new RoomApplications.MaintenanceCancellation(first.eventId(), ROOM, 2,
            first.maintenanceSourceId(), first.reason())));
    assertEquals("SOURCE_VERSION_CONFLICT", versionError.code);
    assertEquals("APPROVED", state());
    assertEquals(0, count("application_cancellation_events"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"REJECTED", "EXPIRED", "WITHDRAWN", "CONFLICT"})
  void existingNonResourceTerminalStatesArePreserved(String initial) {
    db.update("UPDATE room_applications SET state=? WHERE id=?", initial, APP);
    applications.cancelForMaintenance(APP, event());
    assertEquals(initial, state());
    assertEquals(0, count("application_notification_tasks"));
    assertEquals(0, count("application_audit_events"));
  }

  @Test
  void lateSuccessAndFailureCannotOverwriteCancellation() {
    db.update("UPDATE room_applications SET state='RESOURCE_SYNC_PENDING' WHERE id=?", APP);
    task("ACTIVE", 1);
    applications.cancelForMaintenance(APP, event());
    applications.applied(TASK);
    applications.failed(TASK, "RESOURCE_CONFLICT", "迟到失败");
    assertEquals("CANCELLED_MAINTENANCE", state());
    assertEquals(1, count("application_audit_events"));
    assertEquals("SUPERSEDED", db.queryForObject("SELECT status FROM application_sync_tasks WHERE id=?", String.class, TASK));
  }

  @Test
  void databaseFailureRollsBackCancellationEventStateAndAuditTogether() {
    db.update("INSERT INTO application_notification_tasks(id,event_key,application_id,user_id,email,message)"
        + " VALUES(?,?,?,?,?,?)", UUID.randomUUID().toString(), "M2_MAINTENANCE_CANCELLED:" + APP,
        APP, USER, "student@campus.test", "Pre-existing test collision");
    assertThrows(org.springframework.dao.DuplicateKeyException.class,
        () -> applications.cancelForMaintenance(APP, event()));
    assertEquals("APPROVED", state());
    assertEquals(0, count("application_cancellation_events"));
    assertEquals(0, count("application_audit_events"));
  }

  @Test
  void callbackAndApprovalResponseCanRaceWithoutRestoringApproval() throws Exception {
    db.update("UPDATE room_applications SET state='RESOURCE_SYNC_PENDING' WHERE id=?", APP);
    task("ACTIVE", 1);
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var approval = pool.submit(() -> { start.await(); applications.applied(TASK); return null; });
      var cancellation = pool.submit(() -> { start.await(); applications.cancelForMaintenance(APP, event()); return null; });
      start.countDown();
      approval.get(10, TimeUnit.SECONDS);
      cancellation.get(10, TimeUnit.SECONDS);
    }
    assertEquals("CANCELLED_MAINTENANCE", state());
    assertEquals(1, count("application_notification_tasks"));
  }

  @Test
  void internalHttpContractEnforcesCredentialsAndBeanValidation() throws Exception {
    String url = "/internal/v1/room-applications/" + APP + "/maintenance-cancellation";
    String body = json.writeValueAsString(event());
    http.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
    http.perform(post(url).header("X-Service-Key", "wrong").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isForbidden());
    http.perform(post(url).header("X-Service-Key", KEY).contentType(MediaType.APPLICATION_JSON)
        .content(body.replace("\"sourceVersion\":1", "\"sourceVersion\":0"))).andExpect(status().isBadRequest());
    assertEquals("APPROVED", state());
    http.perform(post(url).header("X-Service-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk());
    assertEquals("CANCELLED_MAINTENANCE", state());
  }

  @Test
  void normalApprovalAndWithdrawalResultsStillApply() {
    db.update("UPDATE room_applications SET state='RESOURCE_SYNC_PENDING' WHERE id=?", APP);
    task("ACTIVE", 1); applications.applied(TASK);
    assertEquals("APPROVED", state());
    var applicant = new Identity.Actor(USER, "student", "Student", "student@campus.test", "CS",
        List.of("roomapplications:read", "roomapplications:withdraw"));
    applications.withdraw(applicant, "withdraw-regression", APP);
    String release = db.queryForObject("SELECT id FROM application_sync_tasks WHERE desired_state='RELEASED'", String.class);
    applications.applied(release);
    assertEquals("WITHDRAWN", state());
  }

  @Test
  void notificationFailureIsRetriedWithTheSameEventIdentifier() throws Exception {
    applications.cancelForMaintenance(APP, event());
    var bodies = new CopyOnWriteArrayList<String>();
    var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext("/internal/v1/notifications", exchange -> {
      bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      int response = KEY.equals(exchange.getRequestHeaders().getFirst("X-Service-Key"))
          ? (bodies.size() == 1 ? 503 : 200) : 403;
      exchange.sendResponseHeaders(response, -1);
      exchange.close();
    });
    server.start();
    try {
      var worker = new ApplicationNotificationWorker(db, new Identity(KEY, "http://unused.invalid"),
          "http://localhost:" + server.getAddress().getPort());
      worker.deliver();
      assertEquals("PENDING", db.queryForObject("SELECT status FROM application_notification_tasks", String.class));
      assertEquals(1, db.queryForObject("SELECT attempts FROM application_notification_tasks", Integer.class));
      db.update("UPDATE application_notification_tasks SET next_attempt=CURRENT_TIMESTAMP");
      worker.deliver();
      assertEquals("SENT", db.queryForObject("SELECT status FROM application_notification_tasks", String.class));
      assertEquals(2, bodies.size());
      assertEquals(json.readTree(bodies.get(0)), json.readTree(bodies.get(1)));
      assertEquals("M2_MAINTENANCE_CANCELLED:" + APP, json.readTree(bodies.get(1)).get("eventId").asText());
    } finally {
      server.stop(0);
    }
  }
}

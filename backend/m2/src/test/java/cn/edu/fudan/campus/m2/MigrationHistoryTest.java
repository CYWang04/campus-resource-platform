package cn.edu.fudan.campus.m2;

import static org.junit.jupiter.api.Assertions.*;

import cn.edu.fudan.campus.common.Ids;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.HexFormat;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class MigrationHistoryTest {
  private static final String APP = "20000000-0000-0000-0000-000000000001";
  private static final String USER = "30000000-0000-0000-0000-000000000001";
  private static final String ROOM = "10000000-0000-0000-0000-000000000001";
  private static final String MAINTENANCE = "50000000-0000-0000-0000-000000000001";

  static final class Database implements AutoCloseable {
    final DriverManagerDataSource source;
    final JdbcTemplate db;
    final Connection admin;
    final String name;

    Database() throws Exception {
      name = "m2_history_test_" + UUID.randomUUID().toString().replace("-", "");
      String mysql = System.getenv("M2_TEST_MYSQL_URL");
      if (mysql == null) {
        admin = null;
        source = new DriverManagerDataSource("jdbc:h2:mem:" + name
            + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
      } else {
        String user = System.getenv("M2_TEST_MYSQL_USER");
        String password = System.getenv("M2_TEST_MYSQL_PASSWORD");
        admin = DriverManager.getConnection(mysql, user, password);
        try (var statement = admin.createStatement()) { statement.execute("CREATE DATABASE " + name); }
        int query = mysql.indexOf('?');
        String base = query < 0 ? mysql : mysql.substring(0, query);
        String params = query < 0 ? "" : mysql.substring(query);
        source = new DriverManagerDataSource(base.replaceAll("/+$", "") + "/" + name + params, user, password);
      }
      db = new JdbcTemplate(source);
    }

    Flyway flyway(String lineage, String target) {
      var configuration = Flyway.configure().dataSource(source)
          .locations("classpath:db/migration/common", "classpath:db/migration/" + lineage);
      if (target != null) configuration.target(MigrationVersion.fromVersion(target));
      return configuration.load();
    }

    @Override public void close() throws Exception {
      if (admin != null) {
        try (var statement = admin.createStatement()) { statement.execute("DROP DATABASE " + name); }
        admin.close();
      } else db.execute("SHUTDOWN");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"m1", "m2"})
  void eitherHistoryBootstrapsWithoutDuplicateMigrationVersions(String lineage) throws Exception {
    try (var database = new Database()) {
      var flyway = database.flyway(lineage, null);
      flyway.migrate();
      flyway.validate();
      assertEquals("4", flyway.info().current().getVersion().toString());
      assertEquals(0, flyway.migrate().migrationsExecuted);
      assertEquals(1, database.db.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='3'", Integer.class));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"m1", "m2"})
  void existingV3ReceiptsAndNotificationsSurviveForwardUpgrade(String lineage) throws Exception {
    try (var database = new Database()) {
      database.flyway(lineage, "3").migrate();
      var db = database.db;
      db.update("INSERT INTO users(id,username,display_name,email,password_hash) VALUES(?,?,?,?,?)",
          USER, "legacy-student", "Student", "student@campus.test", "test-only");
      db.update("INSERT INTO room_applications(id,applicant_id,applicant_email,room_id,room_name,purpose,"
              + "attendee_count,start_at,end_at,state,source_version,expected_availability_version)"
              + " VALUES(?,?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,?,1,0)",
          APP, USER, "student@campus.test", ROOM, "Legacy room", "Test", 1, "CANCELLED_MAINTENANCE");
      var event = new RoomApplications.MaintenanceCancellation(UUID.randomUUID().toString(), ROOM, 1, MAINTENANCE, null);
      if (lineage.equals("m1")) {
        String fingerprint = Ids.hash(APP + "|MaintenanceCancellation[eventId=" + event.eventId()
            + ", roomId=" + ROOM + ", sourceVersion=1, maintenanceSourceId=" + MAINTENANCE + "]");
        db.update("INSERT INTO application_resource_events(event_id,application_id,fingerprint) VALUES(?,?,?)",
            event.eventId(), APP, fingerprint);
        db.update("INSERT INTO application_notification_tasks(id,event_key,user_id,email,message) VALUES(?,?,?,?,?)",
            UUID.randomUUID().toString(), "MAINTENANCE_CANCELLED:" + APP, USER, "student@campus.test", "Original notice");
      } else {
        db.update("INSERT INTO application_cancellation_events(event_id,application_id,fingerprint,maintenance_source_id,source_version)"
                + " VALUES(?,?,?,?,1)", event.eventId(), APP, Ids.hash(APP + "|" + event), MAINTENANCE);
        db.update("INSERT INTO application_notification_tasks(id,event_key,application_id,user_id,email,message) VALUES(?,?,?,?,?,?)",
            UUID.randomUUID().toString(), "M2_MAINTENANCE_CANCELLED:" + APP, APP, USER, "student@campus.test", "Original notice");
      }
      var flyway = database.flyway(lineage, null);
      flyway.migrate(); flyway.validate();
      var applications = new RoomApplications(db);
      applications.cancelForMaintenance(APP, event);
      applications.cancelForMaintenance(APP, event);
      assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM application_notification_tasks", Integer.class));
      assertEquals("Original notice", db.queryForObject("SELECT message FROM application_notification_tasks", String.class));
      assertEquals(APP, db.queryForObject("SELECT application_id FROM application_notification_tasks", String.class));
      assertNotNull(db.queryForObject("SELECT created_at FROM application_notification_tasks", java.sql.Timestamp.class));
      assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM application_cancellation_events", Integer.class));
      assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM application_audit_events", Integer.class));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"m1", "m2"})
  void selectingWrongHistoryFailsWithoutRepairingTheDatabase(String lineage) throws Exception {
    try (var database = new Database()) {
      database.flyway(lineage, "3").migrate();
      assertThrows(FlywayException.class,
          () -> database.flyway(lineage.equals("m1") ? "m2" : "m1", null).migrate());
      database.flyway(lineage, null).migrate();
      database.flyway(lineage, null).validate();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"m1", "m2"})
  void originalV3BytesRemainUnchanged(String lineage) throws Exception {
    String filename = lineage.equals("m1") ? "V3__maintenance_cancellation.sql" : "V3__maintenance_cancellations.sql";
    String expected = lineage.equals("m1") ? "1f946243b92dc265f74bad3f61023a01dcb017d0" : "87cb8c44b9ec5aa6ea33468d8178baf98092f237";
    try (var stream = getClass().getResourceAsStream("/db/migration/" + lineage + "/" + filename)) {
      assertNotNull(stream);
      byte[] data = stream.readAllBytes();
      var hash = MessageDigest.getInstance("SHA-1");
      hash.update(("blob " + data.length + "\0").getBytes(StandardCharsets.UTF_8));
      assertEquals(expected, HexFormat.of().formatHex(hash.digest(data)));
    }
  }
}

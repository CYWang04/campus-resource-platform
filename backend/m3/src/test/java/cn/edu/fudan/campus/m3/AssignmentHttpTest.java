package cn.edu.fudan.campus.m3;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.edu.fudan.campus.common.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@SpringJUnitConfig(OrdersDatabaseTest.Database.class)
class AssignmentHttpTest {
  @Autowired Orders orders;
  @Autowired JdbcTemplate db;
  final ObjectMapper json = new ObjectMapper();
  HttpServer server;
  MockMvc http;
  String id;
  volatile int directoryStatus;
  volatile String directoryBody;
  final AtomicInteger directoryCalls = new AtomicInteger();
  final AtomicInteger forwardedAuthorization = new AtomicInteger();
  volatile Identity.Actor actor;

  @BeforeEach void prepare() throws Exception {
    var fixture = new OrdersDatabaseTest(); fixture.db = db; fixture.orders = orders; fixture.reset();
    id = fixture.report(); actor = OrdersDatabaseTest.MANAGER;
    directoryStatus = 200;
    directoryBody = json.writeValueAsString(Map.of("data", new Object[]{Map.of(
        "id", OrdersDatabaseTest.TECHNICIAN.id(), "displayName", "Verified technician", "email", "verified@campus.test")}));
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/internal/v1/auth/introspect", exchange -> {
      exchange.getRequestBody().readAllBytes();
      byte[] body = json.writeValueAsBytes(actor);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
    });
    server.createContext("/api/v1/directory/maintenance-workers", exchange -> {
      directoryCalls.incrementAndGet();
      if ("Bearer test-session".equals(exchange.getRequestHeaders().getFirst("Authorization")))
        forwardedAuthorization.incrementAndGet();
      byte[] body = directoryBody.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(directoryStatus, body.length); exchange.getResponseBody().write(body); exchange.close();
    });
    server.start();
    String url = "http://127.0.0.1:" + server.getAddress().getPort();
    var identity = new Identity("m3-test-service-key-with-32-characters", url);
    http = MockMvcBuilders.standaloneSetup(new WorkOrderController(orders, identity, db, url,
        new WorkOrderAssignments(orders, identity, new WorkerDirectory(url))))
        .setControllerAdvice(new Api.Errors()).build();
  }
  @AfterEach void stop() { if (server != null) server.stop(0); }

  String body(String worker) throws Exception {
    return json.writeValueAsString(new Orders.Assignment(worker, "Forged name", "forged@campus.test"));
  }
  org.springframework.test.web.servlet.ResultActions assign(String key, String body) throws Exception {
    return http.perform(post("/api/v1/work-orders/" + id + "/assign")
        .header("Authorization", "Bearer test-session").header("Idempotency-Key", key)
        .contentType(MediaType.APPLICATION_JSON).content(body));
  }
  Map<String,Object> row() { return db.queryForMap("SELECT * FROM work_orders WHERE id=?", id); }

  @Test void persistsDirectoryIdentityInsteadOfClientSuppliedIdentity() throws Exception {
    assign("assign-verified", body(OrdersDatabaseTest.TECHNICIAN.id())).andExpect(status().isOk());
    assertEquals("Verified technician", row().get("repairer_name"));
    assertEquals("verified@campus.test", row().get("repairer_email"));
    assertEquals(1, forwardedAuthorization.get());
    assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM notifications WHERE email='verified@campus.test'", Integer.class));
  }
  @Test void unknownOrDisabledWorkerCannotBeAssigned() throws Exception {
    directoryBody = "{\"data\":[]}";
    assign("assign-unknown", body(OrdersDatabaseTest.TECHNICIAN.id())).andExpect(status().isConflict());
    assertEquals("REPORTED", row().get("state"));
    assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM commands WHERE idempotency_key='assign-unknown'", Integer.class));
  }
  @Test void directoryFailureDoesNotSaveAnAssignment() throws Exception {
    directoryStatus = 503; directoryBody = "{}";
    assign("assign-unavailable", body(OrdersDatabaseTest.TECHNICIAN.id())).andExpect(status().isServiceUnavailable());
    assertEquals("REPORTED", row().get("state"));
  }
  @Test void successfulReplaySurvivesDirectoryFailureWithoutDuplicateNotifications() throws Exception {
    String body = body(OrdersDatabaseTest.TECHNICIAN.id());
    assign("assign-replay", body).andExpect(status().isOk());
    directoryStatus = 503;
    assign("assign-replay", body).andExpect(status().isOk());
    assertEquals(1, directoryCalls.get());
    assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM notifications", Integer.class));
  }
  @Test void changedRequestWithSameKeyIsRejected() throws Exception {
    String body = body(OrdersDatabaseTest.TECHNICIAN.id());
    assign("assign-conflict", body).andExpect(status().isOk());
    assign("assign-conflict", body.replace("Forged name", "Different name")).andExpect(status().isConflict());
  }
  @Test void unauthorizedUserNeverQueriesDirectory() throws Exception {
    actor = OrdersDatabaseTest.STUDENT;
    assign("assign-forbidden", body(OrdersDatabaseTest.TECHNICIAN.id())).andExpect(status().isForbidden());
    assertEquals(0, directoryCalls.get()); assertEquals("REPORTED", row().get("state"));
  }
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"{}", "{\"data\":null}", "invalid-json"})
  void malformedDirectoryResponseFailsClosed(String response) throws Exception {
    directoryBody = response;
    assign("assign-malformed", body(OrdersDatabaseTest.TECHNICIAN.id())).andExpect(status().isServiceUnavailable());
    assertEquals("REPORTED", row().get("state"));
    assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM notifications", Integer.class));
  }

}

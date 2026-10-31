package cn.edu.fudan.campus.m3;

import cn.edu.fudan.campus.common.Api;
import cn.edu.fudan.campus.common.Trace;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;

/** M3 adapter for the existing M2 public directory; never reads identity tables. */
@Component
public class WorkerDirectory {
  public record Worker(String id, String displayName, String email) {}
  private record Response(List<Worker> data) {}
  private final RestClient client;

  public WorkerDirectory(@Value("${campus.identity-url}") String url) {
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(3000); factory.setReadTimeout(5000);
    client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }

  public Worker find(String authorization, String id) {
    Response response;
    try {
      response = client.get().uri("/api/v1/directory/maintenance-workers")
          .header("Authorization", authorization).header("X-Request-Id", Trace.id())
          .retrieve().body(Response.class);
    } catch (HttpClientErrorException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403)
        throw Api.fail(e.getStatusCode().value(), "DIRECTORY_REJECTED", "当前身份不能查询维修人员");
      throw unavailable();
    } catch (RestClientException e) { throw unavailable(); }
    if (response == null || response.data() == null) throw unavailable();
    for (Worker worker : response.data()) {
      if (worker == null || worker.id() == null) throw unavailable();
      if (!id.equals(worker.id())) continue;
      if (worker.displayName() == null || worker.displayName().isBlank()
          || worker.displayName().length() > 100 || worker.email() == null || worker.email().length() > 200)
        throw unavailable();
      try { new jakarta.mail.internet.InternetAddress(worker.email(), true).validate(); }
      catch (jakarta.mail.internet.AddressException e) { throw unavailable(); }
      return worker;
    }
    throw Api.fail(409, "REPAIRER_NOT_AVAILABLE", "所选维修人员不存在、已停用或不具备维修资格");
  }

  private Api.Failure unavailable() {
    return Api.fail(503, "DIRECTORY_UNAVAILABLE", "维修人员目录暂不可用，请稍后重试");
  }
}

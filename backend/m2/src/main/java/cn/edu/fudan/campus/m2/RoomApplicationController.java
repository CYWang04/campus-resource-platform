package cn.edu.fudan.campus.m2;

import cn.edu.fudan.campus.common.*;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

@RestController
public class RoomApplicationController {
  private final RoomApplications applications;
  private final Identity identity;
  private final RestClient resources;
  public RoomApplicationController(RoomApplications applications,Identity identity,
      @org.springframework.beans.factory.annotation.Value("${campus.resource-url}") String url){
    this.applications=applications;this.identity=identity;this.resources=RestClient.builder().baseUrl(url).build();
  }
  @PostMapping("/internal/v1/room-applications/{id}/maintenance-cancellations")
  public Object maintenance(@RequestHeader(value="X-Service-Key",required=false)String key,
      @PathVariable String id,@Valid @RequestBody RoomApplications.MaintenanceCancellation input){
    identity.internal(key);
    return Api.ok(applications.maintenanceCancellation(id,input));
  }
  @GetMapping("/api/v1/room-applications") public Object list(@RequestHeader(value="Authorization",required=false)String auth){return Api.ok(applications.list(identity.require(auth,null)));}
  @GetMapping("/api/v1/room-applications/{id}") public Object get(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable String id){return Api.ok(applications.get(id,identity.require(auth,null)));}
  @PostMapping("/api/v1/room-applications") public Object create(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="Idempotency-Key",required=false)String key,@Valid @RequestBody RoomApplications.Create input){
    var actor=identity.require(auth,"roomapplications:create");
    try{var response=resources.get().uri("/api/v1/rooms/"+input.roomId()+"/availability").header("Authorization",auth).retrieve().body(Map.class);if(response==null)throw new IllegalStateException();}
    catch(org.springframework.web.client.HttpClientErrorException.NotFound e){throw Api.fail(404,"ROOM_NOT_FOUND","教室不存在");}
    catch(org.springframework.web.client.RestClientException|IllegalStateException e){throw Api.fail(503,"RESOURCE_UNAVAILABLE","资源服务暂不可用");}
    return Api.ok(applications.create(actor,key,input));
  }
  @PostMapping("/api/v1/room-applications/{id}/review") public Object review(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="Idempotency-Key",required=false)String key,@PathVariable String id,@Valid @RequestBody RoomApplications.Review input){return Api.ok(applications.review(identity.require(auth,null),key,id,input));}
  @PostMapping("/api/v1/room-applications/{id}/withdraw") public Object withdraw(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="Idempotency-Key",required=false)String key,@PathVariable String id){return Api.ok(applications.withdraw(identity.require(auth,null),key,id));}
}

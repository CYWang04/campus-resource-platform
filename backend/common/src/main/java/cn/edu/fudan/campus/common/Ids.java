package cn.edu.fudan.campus.common;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

public final class Ids {
  private Ids() {}

  public static String uuid() {
    return UUID.randomUUID().toString();
  }

  public static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public static void key(String key) {
    if (key == null || !key.matches("[A-Za-z0-9-]{8,100}"))
      throw Api.fail(400, "IDEMPOTENCY_KEY_REQUIRED", "请提供 8～100 位幂等键");
  }

  public static String uuid(String id) {
    try {
      return UUID.fromString(id).toString();
    } catch (Exception e) {
      throw Api.fail(400, "INVALID_ID", "无效的资源编号");
    }
  }
}

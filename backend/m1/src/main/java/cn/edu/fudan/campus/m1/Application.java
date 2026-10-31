package cn.edu.fudan.campus.m1;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.context.annotation.Bean;
import java.time.Clock;

@SpringBootApplication(scanBasePackages = "cn.edu.fudan.campus")
@EnableScheduling
public class Application {
  @Bean
  Clock clock() { return Clock.systemUTC(); }
  public static void main(String[] args) {
    SpringApplication.run(Application.class, args);
  }
}

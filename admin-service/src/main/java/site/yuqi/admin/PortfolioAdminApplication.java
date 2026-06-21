package site.yuqi.admin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PortfolioAdminApplication {
    public static void main(String[] args) {
        SpringApplication.run(PortfolioAdminApplication.class, args);
    }
}

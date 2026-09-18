package mbdo.dt.uc2_dt;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.integration.annotation.IntegrationComponentScan;
import org.springframework.integration.config.EnableIntegration;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "mbdo.dt")
@EnableIntegration
@IntegrationComponentScan(basePackages = "mbdo.dt")
@EnableScheduling
public class Uc2DtApplication {

    public static void main(String[] args) {
        SpringApplication.run(Uc2DtApplication.class, args);
    }
}
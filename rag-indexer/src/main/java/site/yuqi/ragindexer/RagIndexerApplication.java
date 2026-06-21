package site.yuqi.ragindexer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;

@SpringBootApplication
@EnableKafka
public class RagIndexerApplication {
    public static void main(String[] args) {
        SpringApplication.run(RagIndexerApplication.class, args);
    }
}

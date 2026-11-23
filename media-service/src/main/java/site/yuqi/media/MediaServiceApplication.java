package site.yuqi.media;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Media Service: turns an uploaded original into what visitors actually load.
 *
 * <p>One deployable, three roles that share one job table:
 * <ol>
 *   <li>intake — hears "this upload is complete" and records the work durably;</li>
 *   <li>workers — lease one job at a time and run FFmpeg or libvips;</li>
 *   <li>publisher — validates the outputs and flips the publish pointer.</li>
 * </ol>
 * Running more instances only adds workers; the lease keeps two of them off the same job.
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class MediaServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(MediaServiceApplication.class, args);
    }
}

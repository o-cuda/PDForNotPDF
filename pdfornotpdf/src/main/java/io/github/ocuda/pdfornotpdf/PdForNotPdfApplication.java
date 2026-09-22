package io.github.ocuda.pdfornotpdf;

import org.springframework.boot.SpringApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@EnableScheduling
@SpringBootApplication
public class PdForNotPdfApplication {

    public static void main(String[] args) {
        SpringApplication.run(PdForNotPdfApplication.class, args);
    }
}

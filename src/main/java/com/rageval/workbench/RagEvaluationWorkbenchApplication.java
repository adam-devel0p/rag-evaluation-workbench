package com.rageval.workbench;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class RagEvaluationWorkbenchApplication {

	public static void main(String[] args) {
		SpringApplication.run(RagEvaluationWorkbenchApplication.class, args);
	}

}

package com.rageval.workbench;

import org.springframework.boot.SpringApplication;

public class TestRagEvaluationWorkbenchApplication {

	public static void main(String[] args) {
		SpringApplication.from(RagEvaluationWorkbenchApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}

package com.rageval.workbench.demo;

import com.rageval.workbench.demo.DemoTargetService.DemoOutcome;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo-only stand-in for an external chatbot (spec §27). Exists exclusively under the {@code
 * demo} Spring profile — outside it this endpoint is absent entirely (404), which the non-demo
 * integration tests assert. Scenario logic lives in {@link DemoTargetService}.
 */
@RestController
@RequestMapping("/demo-target")
@Profile("demo")
public class DemoTargetController {

    private final DemoTargetService demoTargetService;

    public DemoTargetController(DemoTargetService demoTargetService) {
        this.demoTargetService = demoTargetService;
    }

    @PostMapping("/chat")
    public ResponseEntity<Object> chat(@RequestBody DemoChatRequest request) {
        DemoOutcome outcome = demoTargetService.chat(request);
        return ResponseEntity.status(outcome.httpStatus()).body(outcome.body());
    }
}

package com.rageval.workbench.configuration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Development-only opt-in for verbose content logging (spec §23). Off by default: logs carry
 * identifiers and sizes, never question/answer/citation/chunk/prompt content. Setting
 * {@code rag-evaluation.logging.verbose-content=true} makes the target adapter and the LLM judge
 * client additionally log full request/response payloads and judge prompts at DEBUG level.
 *
 * <p>This is a mutable {@link Component} rather than a record so the opt-in can announce itself:
 * enabling it logs a prominent startup warning, because with it on, client content lands in the
 * application log — never enable it outside a local development environment.
 */
@Component
@ConfigurationProperties(prefix = "rag-evaluation.logging")
public class ContentLoggingProperties {

    private static final Logger log = LoggerFactory.getLogger(ContentLoggingProperties.class);

    private boolean verboseContent = false;

    @PostConstruct
    void warnIfEnabled() {
        if (verboseContent) {
            log.warn(
                    "rag-evaluation.logging.verbose-content is ENABLED: full target payloads and judge prompts "
                            + "will be written to the log. This is a development-only setting — client content "
                            + "belongs in the database, not in log files. Disable it outside local development.");
        }
    }

    public boolean isVerboseContent() {
        return verboseContent;
    }

    public void setVerboseContent(boolean verboseContent) {
        this.verboseContent = verboseContent;
    }
}

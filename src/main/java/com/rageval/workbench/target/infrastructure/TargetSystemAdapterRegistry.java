package com.rageval.workbench.target.infrastructure;

import com.rageval.workbench.target.domain.AdapterType;
import com.rageval.workbench.target.domain.TargetSystemAdapter;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Resolves the {@link TargetSystemAdapter} implementation for a given {@link AdapterType}. */
@Component
public class TargetSystemAdapterRegistry {

    private final Map<AdapterType, TargetSystemAdapter> adaptersByType;

    public TargetSystemAdapterRegistry(List<TargetSystemAdapter> adapters) {
        this.adaptersByType =
                adapters.stream().collect(Collectors.toUnmodifiableMap(TargetSystemAdapter::adapterType, Function.identity()));
    }

    public TargetSystemAdapter resolve(AdapterType adapterType) {
        TargetSystemAdapter adapter = adaptersByType.get(adapterType);
        if (adapter == null) {
            throw new IllegalStateException("No TargetSystemAdapter registered for adapter type: " + adapterType);
        }
        return adapter;
    }
}

package io.github.batnam.loyalty.earning.api;

import io.github.batnam.loyalty.earning.api.dto.EarningDtos.EarnSourceResponse;
import io.github.batnam.loyalty.earning.source.EarnSourceRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Earn Source registry read API (loyalty-earning.yaml, tag Earn Sources). Internal-only. The
 * catalogue is global in v1; {@code programId} is on the path for forward-compatibility.
 */
@RestController
public class EarnSourceController {

    private final EarnSourceRegistry registry;

    public EarnSourceController(EarnSourceRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/programs/{programId}/earn-sources")
    public List<EarnSourceResponse> list(@PathVariable long programId) {
        return registry.listSources().stream().map(EarnSourceResponse::from).toList();
    }
}

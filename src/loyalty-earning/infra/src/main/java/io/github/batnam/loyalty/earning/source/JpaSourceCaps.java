package io.github.batnam.loyalty.earning.source;

import io.github.batnam.loyalty.earning.rule.SourceCapConfig;
import io.github.batnam.loyalty.earning.rule.SourceCaps;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * JPA-backed adapter for the {@link SourceCaps} port. Joins {@code earn_source_cap} to
 * {@code earn_source} to derive the synthetic {@code cap_counter} key ({@code -earn_source_id}) under
 * which this source's aggregate counter lives. Mirrors {@link JpaRules}.
 */
@Component
public class JpaSourceCaps implements SourceCaps {

    private final EarnSourceCapRepository caps;
    private final EarnSourceRepository sources;

    public JpaSourceCaps(EarnSourceCapRepository caps, EarnSourceRepository sources) {
        this.caps = caps;
        this.sources = sources;
    }

    @Override
    public Optional<SourceCapConfig> findForSource(long programId, String earnSourceCode) {
        return caps.findByProgramIdAndEarnSourceCode(programId, earnSourceCode)
                .flatMap(cap -> sources.findByEarnSourceCode(earnSourceCode)
                        .map(src -> new SourceCapConfig(
                                -src.getEarnSourceId(),
                                cap.getDailyCap(), cap.getMonthlyCap(), cap.getLifetimeCap())));
    }
}

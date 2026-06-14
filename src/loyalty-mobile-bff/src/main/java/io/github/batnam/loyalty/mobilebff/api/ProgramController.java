package io.github.batnam.loyalty.mobilebff.api;

import io.github.batnam.loyalty.mobilebff.api.dto.MobileDtos.Balance;
import io.github.batnam.loyalty.mobilebff.api.dto.MobileDtos.EnrolledProgram;
import io.github.batnam.loyalty.mobilebff.api.dto.MobileDtos.ExpiringCohort;
import io.github.batnam.loyalty.mobilebff.api.dto.MobileDtos.TcsRequest;
import io.github.batnam.loyalty.mobilebff.api.dto.MobileDtos.TierState;
import io.github.batnam.loyalty.mobilebff.api.dto.MobileDtos.TransactionPage;
import io.github.batnam.loyalty.mobilebff.client.CoreClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Programs + Balance & History (loyalty-mobile-bff.yaml). Every endpoint is keyed by {@code customerId} (the
 * Host Bank customer identifier) — the gateway Authentication Service verifies the token and injects the
 * CIF, so the BFF does no token handling and the edge never sees Loyalty's internal {@code memberId}.
 * v1 maps {@code customerId} 1:1 to {@code memberId}; this BFF forwards it to {@link CoreClient} as that seam.
 */
@RestController
public class ProgramController {

    private final CoreClient core;

    public ProgramController(CoreClient core) {
        this.core = core;
    }

    @GetMapping("/me/programs")
    public Map<String, List<EnrolledProgram>> listEnrolledPrograms(@RequestParam long customerId) {
        return Map.of("enrolledPrograms", core.listEnrolledPrograms(customerId));
    }

    @PostMapping("/me/programs/{programId}/opt-in")
    public EnrolledProgram optIn(@PathVariable long programId, @RequestBody TcsRequest req) {
        return core.optIn(req.customerId(), programId, req.tcsVersion());
    }

    @PostMapping("/me/programs/{programId}/opt-out")
    public ResponseEntity<Void> optOut(@RequestParam long customerId, @PathVariable long programId) {
        core.optOut(customerId, programId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/me/programs/{programId}/tcs-acceptance")
    public EnrolledProgram acceptTcs(@PathVariable long programId, @RequestBody TcsRequest req) {
        return core.acceptTcs(req.customerId(), programId, req.tcsVersion());
    }

    @GetMapping("/me/programs/{programId}/balance")
    public Balance balance(@RequestParam long customerId, @PathVariable long programId) {
        return core.getBalance(customerId, programId);
    }

    @GetMapping("/me/programs/{programId}/transactions")
    public TransactionPage transactions(@RequestParam long customerId, @PathVariable long programId,
                                        @RequestParam(required = false) String cursor,
                                        @RequestParam(required = false) Integer limit) {
        return core.listTransactions(customerId, programId, cursor, limit);
    }

    @GetMapping("/me/programs/{programId}/tier")
    public TierState tier(@RequestParam long customerId, @PathVariable long programId) {
        return core.getTier(customerId, programId);
    }

    @GetMapping("/me/programs/{programId}/expiring-points")
    public List<ExpiringCohort> expiringPoints(@RequestParam long customerId, @PathVariable long programId) {
        return core.listExpiringPoints(customerId, programId);
    }
}

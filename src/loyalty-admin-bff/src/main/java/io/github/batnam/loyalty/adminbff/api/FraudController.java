package io.github.batnam.loyalty.adminbff.api;

import io.github.batnam.loyalty.adminbff.api.dto.AdminDtos.FraudAlertPage;
import io.github.batnam.loyalty.adminbff.client.FraudClient;
import io.github.batnam.loyalty.adminbff.security.EmployeeIdentity;
import io.github.batnam.loyalty.adminbff.security.Roles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Fraud (loyalty-admin-bff.yaml). Velocity-anomaly alerts for the Fraud-Ops UI, aggregated from the
 * loyalty-integration-bridge fraud consumer. Requires the {@code loyalty-fraud-ops} role.
 */
@RestController
public class FraudController {

    private final FraudClient fraud;

    public FraudController(FraudClient fraud) {
        this.fraud = fraud;
    }

    @GetMapping("/fraud/alerts")
    public FraudAlertPage listAlerts(EmployeeIdentity caller,
                                     @RequestParam(required = false) Long programId,
                                     @RequestParam(required = false) String cursor,
                                     @RequestParam(required = false) Integer limit) {
        caller.requireAnyRole(Roles.FRAUD_OPS);
        return fraud.listAlerts(programId, cursor, limit);
    }
}

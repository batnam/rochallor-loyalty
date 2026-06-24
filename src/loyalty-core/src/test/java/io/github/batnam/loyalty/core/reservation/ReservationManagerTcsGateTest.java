package io.github.batnam.loyalty.core.reservation;

import io.github.batnam.loyalty.core.config.CoreProperties;
import io.github.batnam.loyalty.core.domain.port.Reservations;
import io.github.batnam.loyalty.core.error.CoreException;
import io.github.batnam.loyalty.core.ledger.LedgerService;
import io.github.batnam.loyalty.core.member.Member;
import io.github.batnam.loyalty.core.member.MemberRepository;
import io.github.batnam.loyalty.core.outbox.OutboxRelay;
import io.github.batnam.loyalty.core.program.Program;
import io.github.batnam.loyalty.core.program.ProgramRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit test for the T&Cs re-acceptance gate in {@link ReservationManager#reserve}: a Member behind on
 * the Program's current T&Cs version cannot reserve points to redeem (409 TCS_REACCEPTANCE_REQUIRED),
 * while a caught-up Member with sufficient balance proceeds. Repositories are mocked.
 */
class ReservationManagerTcsGateTest {

    private final Reservations reservations = mock(Reservations.class);
    private final MemberRepository members = mock(MemberRepository.class);
    private final ProgramRepository programs = mock(ProgramRepository.class);
    private final LedgerService ledger = mock(LedgerService.class);
    private final OutboxRelay outbox = mock(OutboxRelay.class);

    private final CoreProperties props = new CoreProperties(
            new CoreProperties.Topics("a", "b", "ledger.v1", "member.v1"),
            new CoreProperties.Reservation(900),
            new CoreProperties.Expiry("0 30 2 * * *"),
            new CoreProperties.TierReeval("0 15 3 * * *"),
            new CoreProperties.Tcs(30, "0 45 2 * * *"),
            new CoreProperties.Outbox(100));

    private final ReservationManager manager =
            new ReservationManager(reservations, members, programs, ledger, outbox, props);

    private static Member member(Integer accepted) {
        Member m = mock(Member.class);
        when(m.getTcsVersionAccepted()).thenReturn(accepted);
        when(m.getRedeemableBalance()).thenReturn(10_000L);
        return m;
    }

    private static Program program(int current, Instant effectiveAt) {
        Program p = mock(Program.class);
        when(p.getCurrentTcsVersion()).thenReturn(current);
        when(p.getTcsVersionEffectiveAt()).thenReturn(effectiveAt);
        return p;
    }

    @Test
    void reserveBlockedWhenMemberBehindOnTcs() {
        Member m = member(1);
        Program p = program(2, Instant.now());
        when(members.findByIdForUpdate(1L)).thenReturn(Optional.of(m));
        when(programs.findById(7L)).thenReturn(Optional.of(p));

        assertThatThrownBy(() -> manager.reserve(1L, 7L, 500, null, null, null))
                .isInstanceOf(CoreException.class)
                .satisfies(e -> {
                    CoreException ce = (CoreException) e;
                    assertThat(ce.code()).isEqualTo("TCS_REACCEPTANCE_REQUIRED");
                    assertThat(ce.status()).isEqualTo(HttpStatus.CONFLICT);
                });
    }

    @Test
    void reserveBlockedAfterGraceExpired() {
        Member m = member(1);
        Instant longAgo = Instant.now().minus(60, ChronoUnit.DAYS);
        Program p = program(2, longAgo);
        when(members.findByIdForUpdate(1L)).thenReturn(Optional.of(m));
        when(programs.findById(7L)).thenReturn(Optional.of(p));

        assertThatThrownBy(() -> manager.reserve(1L, 7L, 500, null, null, null))
                .isInstanceOf(CoreException.class)
                .satisfies(e -> assertThat(((CoreException) e).code()).isEqualTo("TCS_REACCEPTANCE_REQUIRED"));
    }

    @Test
    void reserveProceedsWhenMemberCaughtUp() {
        Member m = member(2);
        Program p = program(2, Instant.now());
        when(members.findByIdForUpdate(1L)).thenReturn(Optional.of(m));
        when(programs.findById(7L)).thenReturn(Optional.of(p));
        when(reservations.sumActiveHeld(anyLong(), any())).thenReturn(0L);
        when(reservations.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // No T&Cs throw: passes the gate and reaches the balance check (sufficient balance → success).
        var r = manager.reserve(1L, 7L, 500, null, null, null);
        assertThat(r).isNotNull();
    }
}

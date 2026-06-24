package io.github.batnam.loyalty.core.job;

import io.github.batnam.loyalty.core.config.CoreProperties;
import io.github.batnam.loyalty.core.domain.member.TcsGracePolicy;
import io.github.batnam.loyalty.core.member.Member;
import io.github.batnam.loyalty.core.member.MemberRepository;
import io.github.batnam.loyalty.core.member.MembershipAggregate;
import io.github.batnam.loyalty.core.program.Program;
import io.github.batnam.loyalty.core.program.ProgramRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * T&amp;Cs Grace Job (CONTEXT.md "T&amp;Cs Version"). Nightly per-Program sweep that suspends ACTIVE
 * Members whose accepted T&amp;Cs version is behind the Program's current version <i>and</i> whose
 * 30-day grace window (from {@code program.tcs_version_effective_at}) has elapsed. ShedLock-guarded so
 * exactly one pod runs each tick; the status transition is the single-writer {@link MembershipAggregate}.
 */
@Component
public class TcsGraceJob {

    private static final Logger log = LoggerFactory.getLogger(TcsGraceJob.class);

    private final ProgramRepository programs;
    private final MemberRepository members;
    private final MembershipAggregate membership;
    private final int graceDays;

    public TcsGraceJob(ProgramRepository programs, MemberRepository members,
                       MembershipAggregate membership, CoreProperties props) {
        this.programs = programs;
        this.members = members;
        this.membership = membership;
        this.graceDays = props.tcs().graceDays();
    }

    @Scheduled(cron = "${core.tcs.cron}")
    @SchedulerLock(name = "tcsGrace", lockAtMostFor = "PT30M")
    public void run() {
        Instant now = Instant.now();
        int suspended = 0;
        for (Program p : programs.findAll()) {
            for (Member m : members.findActiveBehindTcs(p.getProgramId(), p.getCurrentTcsVersion())) {
                TcsGracePolicy.Status status = TcsGracePolicy.evaluate(
                        m.getTcsVersionAccepted(), p.getCurrentTcsVersion(),
                        p.getTcsVersionEffectiveAt(), now, graceDays);
                if (status == TcsGracePolicy.Status.GRACE_EXPIRED) {
                    membership.suspendForTcs(m.getMemberId());
                    suspended++;
                }
            }
        }
        log.info("tcs-grace job finished — {} member(s) suspended for elapsed T&Cs grace", suspended);
    }
}

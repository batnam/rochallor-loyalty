package io.github.batnam.loyalty.core.member;

import io.github.batnam.loyalty.core.config.CoreProperties;
import io.github.batnam.loyalty.core.error.CoreException;
import io.github.batnam.loyalty.core.event.MemberEvent;
import io.github.batnam.loyalty.core.outbox.OutboxRelay;
import io.github.batnam.loyalty.core.program.Program;
import io.github.batnam.loyalty.core.program.ProgramRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Membership Aggregate — single writer of the {@code member} lifecycle column ({@code status}).
 * Owns opt-in / opt-out / close (CONTEXT.md "Opt-in", "Member"). A previously opted-out Member who
 * opts in again is the <i>same</i> Member (lifecycle = Reactivated), never a new one.
 */
@Service
public class MembershipAggregate {

    private static final Logger log = LoggerFactory.getLogger(MembershipAggregate.class);

    private final MemberRepository members;
    private final ProgramRepository programs;
    private final OutboxRelay outbox;
    private final String memberTopic;

    public MembershipAggregate(MemberRepository members, ProgramRepository programs,
                               OutboxRelay outbox, CoreProperties props) {
        this.members = members;
        this.programs = programs;
        this.outbox = outbox;
        this.memberTopic = props.topics().memberEvents();
    }

    /** Opt a Customer into a Program (enroll new, or reactivate an opted-out/closed Member). */
    @Transactional
    public Member optIn(long programId, long customerId, Integer tcsVersionAccepted) {
        Member member = members.findByProgramIdAndCustomerId(programId, customerId).orElse(null);
        if (member == null) {
            Member created = members.save(Member.enroll(programId, customerId, tcsVersionAccepted));
            publish("MemberOptedIn", created, "opt-in");
            return created;
        }
        if (member.getStatus() != MemberStatus.ACTIVE) {
            member.setStatus(MemberStatus.ACTIVE);
            member.setTcsVersionAccepted(tcsVersionAccepted);
            members.save(member);
            publish("MemberReactivated", member, "reactivated");
        }
        return member;
    }

    @Transactional
    public Member optOut(long memberId) {
        Member member = require(memberId);
        member.setStatus(MemberStatus.OPTED_OUT);
        members.save(member);
        publish("MemberOptedOut", member, "opt-out");
        return member;
    }

    /** Close every Member a Customer holds (CUSTOMER_CLOSED lifecycle from the bridge). Idempotent. */
    @Transactional
    public void closeForCustomer(long customerId, String reason) {
        for (Member member : members.findByCustomerId(customerId)) {
            if (member.getStatus() == MemberStatus.CLOSED) continue;
            member.setStatus(MemberStatus.CLOSED);
            members.save(member);
            publish("MemberClosed", member, reason);
            log.debug("closed memberId={} customerId={} reason={}", member.getMemberId(), customerId, reason);
        }
    }

    /**
     * Suspend an ACTIVE Member whose T&Cs grace window has elapsed (CONTEXT.md "T&Cs Version").
     * Called by the {@code TcsGraceJob}. Idempotent — a Member already SUSPENDED_TCS (or no longer
     * ACTIVE) is left untouched.
     */
    @Transactional
    public void suspendForTcs(long memberId) {
        Member member = require(memberId);
        if (member.getStatus() != MemberStatus.ACTIVE) return;
        member.setStatus(MemberStatus.SUSPENDED_TCS);
        members.save(member);
        publish("MemberSuspendedTcs", member, "tcs-grace-expired");
        log.info("suspended memberId={} — T&Cs grace window elapsed", memberId);
    }

    /**
     * Record a Member's re-acceptance of the current T&Cs version (CONTEXT.md "T&Cs Version"). Sets
     * {@code tcsVersionAccepted}; if the Member was SUSPENDED_TCS and the accepted version now meets
     * the Program's current version, lifts the suspension back to ACTIVE.
     */
    @Transactional
    public Member acceptTcs(long memberId, int acceptedVersion) {
        Member member = require(memberId);
        member.setTcsVersionAccepted(acceptedVersion);
        Program program = programs.findById(member.getProgramId())
                .orElseThrow(() -> CoreException.notFound("PROGRAM_NOT_FOUND",
                        "programId=" + member.getProgramId()));
        boolean reactivated = member.getStatus() == MemberStatus.SUSPENDED_TCS
                && acceptedVersion >= program.getCurrentTcsVersion();
        if (reactivated) {
            member.setStatus(MemberStatus.ACTIVE);
        }
        members.save(member);
        publish(reactivated ? "MemberReactivated" : "MemberTcsAccepted", member, "tcs-accepted-v" + acceptedVersion);
        return member;
    }

    private Member require(long memberId) {
        return members.findById(memberId)
                .orElseThrow(() -> CoreException.notFound("MEMBER_NOT_FOUND", "memberId=" + memberId));
    }

    private void publish(String eventName, Member m, String detail) {
        outbox.enqueue("member", "loyalty.member." + eventName, memberTopic,
                String.valueOf(m.getMemberId()),
                MemberEvent.of(eventName, m.getMemberId(), m.getProgramId(), m.getCustomerId(),
                        m.getStatus().name(), detail, Instant.now()));
    }
}

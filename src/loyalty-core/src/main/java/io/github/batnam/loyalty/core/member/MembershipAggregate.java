package io.github.batnam.loyalty.core.member;

import io.github.batnam.loyalty.core.config.CoreProperties;
import io.github.batnam.loyalty.core.error.CoreException;
import io.github.batnam.loyalty.core.event.MemberEvent;
import io.github.batnam.loyalty.core.outbox.OutboxRelay;
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
    private final OutboxRelay outbox;
    private final String memberTopic;

    public MembershipAggregate(MemberRepository members, OutboxRelay outbox, CoreProperties props) {
        this.members = members;
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

package com.harucut.config;

import com.harucut.booth.enums.BoothDeviceStatus;
import com.harucut.booth.repository.BoothDeviceRepository;
import com.harucut.booth.security.BoothPrincipal;
import com.harucut.common.utils.OpaqueTokens;
import com.harucut.participant.entity.Participant;
import com.harucut.participant.entity.ParticipantSession;
import com.harucut.participant.repository.ParticipantRepository;
import com.harucut.participant.repository.ParticipantSessionRepository;
import com.harucut.participant.security.ParticipantPrincipal;
import com.harucut.result.entity.ResultAccess;
import com.harucut.result.repository.ResultAccessRepository;
import com.harucut.result.service.ResultPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 행사 체인의 자격 확인 — 경로마다 정해진 헤더 하나만 본다 (ADR-0007 §1·§2).
 * <ul>
 *   <li>/api/participant/** : X-Participant-Token → ROLE_PARTICIPANT</li>
 *   <li>/api/public/results/** : Authorization: Bearer → ROLE_RESULT_ACCESS</li>
 * </ul>
 * 쿠키는 보지 않는다 — 회원 쿠키가 같이 와도 무시하고, 회원 JWT 를 결과 토큰으로 착각하지도 않는다.
 * 헤더가 없거나 무효면 인증하지 않은 채 넘기고, 인가가 막으면 진입점이 경로에 맞는 코드(PART-001·RESULT-001)를 쓴다.
 *
 * <p>체인 안에서 직접 생성한다(빈이 아니다) — 서블릿 필터로 자동 등록돼 회원 경로까지 도는 일이 없다.
 */
public class EventCredentialFilter extends OncePerRequestFilter {

    public static final String PARTICIPANT_HEADER = "X-Participant-Token";
    public static final String BOOTH_HEADER = "X-Booth-Token";
    /** 틀린 결과 토큰이 같은 IP 에서 너무 잦을 때 표시 — 진입점이 401 대신 429 를 쓴다 */
    static final String RATE_LIMITED = EventCredentialFilter.class.getName() + ".RATE_LIMITED";
    private static final Duration RATE_WINDOW = Duration.ofMinutes(1);
    private static final String BEARER = "Bearer ";

    private final ParticipantSessionRepository sessionRepository;
    private final ParticipantRepository participantRepository;
    private final ResultAccessRepository resultAccessRepository;
    private final BoothDeviceRepository boothDeviceRepository;
    private final RateLimiter rateLimiter;
    private final int missesPerMinute;
    private final TransactionTemplate readOnly;
    private final Clock clock;

    public EventCredentialFilter(ParticipantSessionRepository sessionRepository,
                                 ParticipantRepository participantRepository,
                                 ResultAccessRepository resultAccessRepository,
                                 BoothDeviceRepository boothDeviceRepository,
                                 RateLimiter rateLimiter, int missesPerMinute,
                                 TransactionTemplate readOnly, Clock clock) {
        this.sessionRepository = sessionRepository;
        this.participantRepository = participantRepository;
        this.resultAccessRepository = resultAccessRepository;
        this.boothDeviceRepository = boothDeviceRepository;
        this.rateLimiter = rateLimiter;
        this.missesPerMinute = missesPerMinute;
        this.readOnly = readOnly;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        Optional<AbstractAuthenticationToken> authentication = Optional.empty();
        if (path.startsWith("/api/participant/")) {
            authentication = participant(request.getHeader(PARTICIPANT_HEADER));
        } else if (path.startsWith("/api/public/results")) {
            String header = request.getHeader(HttpHeaders.AUTHORIZATION);
            authentication = resultAccess(header);
            // 토큰을 냈는데 틀렸다 — 같은 IP 의 틀린 시도를 센다(ADR-0007 §10). 토큰이 없는 요청은 세지 않는다
            if (authentication.isEmpty() && header != null
                    && !rateLimiter.tryAcquire("result-token-miss", request.getRemoteAddr(), missesPerMinute, RATE_WINDOW)) {
                request.setAttribute(RATE_LIMITED, Boolean.TRUE);
            }
        } else if (path.startsWith("/api/booth/")) {
            authentication = booth(request.getHeader(BOOTH_HEADER));
        }
        authentication.ifPresent(token -> {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(token);
            SecurityContextHolder.setContext(context);
        });
        chain.doFilter(request, response);
    }

    private Optional<AbstractAuthenticationToken> participant(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        LocalDateTime now = LocalDateTime.now(clock);
        return readOnly.execute(status -> sessionRepository.findByTokenHash(OpaqueTokens.hash(token.strip()))
                .filter(session -> session.usable(now))
                .flatMap(session -> participantRepository.findById(session.getParticipantId())
                        .map(participant -> authenticated(principal(session, participant), "ROLE_PARTICIPANT"))));
    }

    private Optional<AbstractAuthenticationToken> resultAccess(String header) {
        if (header == null || !header.startsWith(BEARER)) {
            return Optional.empty();
        }
        // 만료는 여기서 막지 않는다 — 결과 API 가 EXPIRED 상태·410 으로 구분해 알려 준다. 회수만 막는다
        return readOnly.execute(status -> resultAccessRepository.findByTokenHash(OpaqueTokens.hash(header.substring(BEARER.length()).strip()))
                .filter(access -> !access.revoked())
                .map(access -> authenticated(new ResultPrincipal(access.getId(), access.getComposeJobId()),
                        "ROLE_RESULT_ACCESS")));
    }

    // 회수된 기기는 토큰 해시를 지우므로 여기서 걸리지 않지만, 상태도 한 번 더 본다
    private Optional<AbstractAuthenticationToken> booth(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return readOnly.execute(status -> boothDeviceRepository.findByTokenHash(OpaqueTokens.hash(token.strip()))
                .filter(device -> device.getStatus() == BoothDeviceStatus.ACTIVE)
                .map(device -> authenticated(new BoothPrincipal(device.getId(), device.getPublicId(), device.getEventId()),
                        "ROLE_BOOTH")));
    }

    private static ParticipantPrincipal principal(ParticipantSession session, Participant participant) {
        return new ParticipantPrincipal(session.getId(), participant.getId(), participant.getPublicId(),
                participant.getEventId(), participant.getChannel(), participant.getBoothDeviceId());
    }

    private static AbstractAuthenticationToken authenticated(Object principal, String role) {
        AbstractAuthenticationToken token = new AbstractAuthenticationToken(List.of(new SimpleGrantedAuthority(role))) {
            @Override
            public Object getCredentials() {
                return null;
            }

            @Override
            public Object getPrincipal() {
                return principal;
            }
        };
        token.setAuthenticated(true);
        return token;
    }
}

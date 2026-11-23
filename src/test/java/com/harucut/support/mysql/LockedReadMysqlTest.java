package com.harucut.support.mysql;

import com.harucut.admin.service.AdminUserService;
import com.harucut.auth.service.RefreshTokenRepository;
import com.harucut.auth.service.UserExitService;
import com.harucut.booth.service.BoothDeviceService;
import com.harucut.common.exception.BusinessException;
import com.harucut.event.dto.EventResponse;
import com.harucut.event.dto.EventSettings;
import com.harucut.event.service.EventService;
import com.harucut.frame.attributes.BackgroundAttributes;
import com.harucut.frame.dto.FrameCreateRequest;
import com.harucut.frame.dto.FrameVersionResponse;
import com.harucut.frame.enums.FrameType;
import com.harucut.frame.repository.FrameRepository;
import com.harucut.frame.service.FrameService;
import com.harucut.frame.service.FrameVersionService;
import com.harucut.storage.service.FileStorageService;
import com.harucut.storage.util.S3Keys;
import com.harucut.user.entity.User;
import com.harucut.user.enums.UserStatus;
import com.harucut.user.repository.UserRepository;
import com.harucut.workspace.dto.WorkspaceResponse;
import com.harucut.workspace.entity.WorkspaceMembership;
import com.harucut.workspace.enums.WorkspaceRole;
import com.harucut.workspace.exception.MemberErrorCode;
import com.harucut.workspace.repository.WorkspaceMembershipRepository;
import com.harucut.workspace.repository.WorkspaceRepository;
import com.harucut.workspace.service.MembershipService;
import com.harucut.workspace.service.WorkspaceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * 「잠금 뒤 세기·번호 매기기」가 REPEATABLE READ 스냅샷에 속지 않는지 — 실제 MySQL.
 * 잠글 행을 먼저 잡아 둔 채 두 요청을 출발시키면, 둘 다 일반 읽기로 스냅샷을 잡은 뒤 잠금에서 기다린다.
 * 풀면 앞쪽이 커밋하고, 뒤쪽은 그 커밋을 잠금 읽기로 봐야 한다.
 */
@Tag("mysql")
@Testcontainers
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
})
@ActiveProfiles("test")
@DisplayName("잠금 읽기 — 마지막 OWNER·발행 번호, 실제 MySQL 동시성")
class LockedReadMysqlTest {

    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer("mysql:8.0")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci");

    @Autowired private MembershipService membershipService;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private WorkspaceMembershipRepository membershipRepository;
    @Autowired private FrameService frameService;
    @Autowired private FrameVersionService frameVersionService;
    @Autowired private FrameRepository frameRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private BoothDeviceService boothDeviceService;
    @Autowired private EventService eventService;
    @Autowired private UserExitService userExitService;
    @Autowired private AdminUserService adminUserService;

    @MockitoBean private FileStorageService fileStorageService;
    @MockitoBean private RefreshTokenRepository refreshTokenRepository;

    @Test
    @DisplayName("두 OWNER 가 동시에 서로를 내려도 OWNER 가 0 명이 되지 않는다")
    void concurrentOwnerDemotionKeepsOneOwner() throws Exception {
        User first = userRepository.save(User.localUser("a@owner.test", "e", "가"));
        User second = userRepository.save(User.localUser("b@owner.test", "e", "나"));
        WorkspaceResponse org = workspaceService.createOrganization(first.getPublicId(), "공간");
        Long workspaceId = workspaceRepository.findByPublicId(org.workspaceId()).orElseThrow().getId();
        membershipRepository.save(WorkspaceMembership.member(workspaceId, second.getId(), WorkspaceRole.OWNER));

        List<Throwable> failures = raceBehind(() -> membershipRepository.lockActiveOwnerUserIds(workspaceId),
                () -> membershipService.changeRole(first.getPublicId(), org.workspaceId(), second.getPublicId(), WorkspaceRole.ADMIN),
                () -> membershipService.changeRole(second.getPublicId(), org.workspaceId(), first.getPublicId(), WorkspaceRole.ADMIN));

        assertThat(membershipRepository.lockActiveOwnerUserIds(workspaceId)).hasSize(1);
        assertThat(failures).hasSize(1);   // 뒤쪽은 MEMBER-004(마지막 OWNER)
    }

    @Test
    @DisplayName("두 OWNER 가 동시에 탈퇴를 신청해도 하나만 받는다 — 뒤쪽은 앞쪽의 탈퇴 신청을 보고 MEMBER-004, 교착 없음")
    void concurrentOwnerExitRequestsKeepOneOwner() throws Exception {
        User first = userRepository.save(User.localUser("exit-a@owner.test", "e", "가"));
        User second = userRepository.save(User.localUser("exit-b@owner.test", "e", "나"));
        WorkspaceResponse org = workspaceService.createOrganization(first.getPublicId(), "탈퇴 공간");
        Long workspaceId = workspaceRepository.findByPublicId(org.workspaceId()).orElseThrow().getId();
        membershipRepository.save(WorkspaceMembership.member(workspaceId, second.getId(), WorkspaceRole.OWNER));

        // 두 요청이 OWNER 멤버십 잠금 앞에서 줄을 선 뒤 풀린다. 앞쪽 커밋(DELETED_REQUESTED)을 뒤쪽이 잠금 읽기로 본다
        List<Throwable> failures = raceBehind(() -> membershipRepository.lockActiveOwnerUserIds(workspaceId),
                () -> userExitService.requestExit(first.getPublicId()),
                () -> userExitService.requestExit(second.getPublicId()));

        assertThat(failures).singleElement()
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.LAST_OWNER);
        List<UserStatus> statuses = List.of(userRepository.findById(first.getId()).orElseThrow().getUserStatus(),
                userRepository.findById(second.getId()).orElseThrow().getUserStatus());
        assertThat(statuses).containsExactlyInAnyOrder(UserStatus.DELETED_REQUESTED, UserStatus.ACTIVE);
    }

    @Test
    @DisplayName("여러 조직의 OWNER 가 동시에 탈퇴해도 교착이 없다 — 조직 멤버십을 모두 잠근 뒤에 회원을 잠근다(Codex #68)")
    void multiOrganizationOwnerExitsDoNotDeadlock() throws Exception {
        User a = userRepository.save(User.localUser("multi-a@owner.test", "e", "가"));
        User b = userRepository.save(User.localUser("multi-b@owner.test", "e", "나"));
        User c = userRepository.save(User.localUser("multi-c@owner.test", "e", "다"));
        WorkspaceResponse first = workspaceService.createOrganization(a.getPublicId(), "조직 1");    // OWNER a·c
        WorkspaceResponse second = workspaceService.createOrganization(a.getPublicId(), "조직 2");   // OWNER a·b
        membershipRepository.save(WorkspaceMembership.member(idOf(first), c.getId(), WorkspaceRole.OWNER));
        membershipRepository.save(WorkspaceMembership.member(idOf(second), b.getId(), WorkspaceRole.OWNER));

        // c 의 회원 행을 붙잡아 a 를 회원 잠금 앞에 세운다. 그사이 b 가 조직 2 를 잠근다.
        // 조직마다 멤버십·회원을 번갈아 잡으면 a(조직 1·회원 a 보유, 조직 2 대기)와 b(조직 2 보유, 회원 a 대기)가 맞물린다
        List<Throwable> failures = raceInOrder(() -> userRepository.lockStayingIds(List.of(c.getId())),
                () -> userExitService.requestExit(a.getPublicId()),
                () -> userExitService.requestExit(b.getPublicId()));

        assertThat(failures).singleElement()
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.LAST_OWNER);
        // 누가 먼저 잠금을 잡든(CI 속도) 한쪽만 탈퇴 신청이 된다
        assertThat(List.of(userRepository.findById(a.getId()).orElseThrow().getUserStatus(),
                userRepository.findById(b.getId()).orElseThrow().getUserStatus()))
                .containsExactlyInAnyOrder(UserStatus.DELETED_REQUESTED, UserStatus.ACTIVE);
    }

    @Test
    @DisplayName("관리자 정지와 탈퇴 신청이 동시에 와도 교착이 없다 — 회원 행은 어디서나 회원 id 순으로 잠근다(Codex #68)")
    void adminBlockAndOwnerExitDoNotDeadlock() throws Exception {
        User admin = userRepository.save(User.localUser("lock-admin@owner.test", "e", "관리"));
        User member = userRepository.save(User.localUser("lock-member@owner.test", "e", "회원"));
        // 회원 id 순(admin < member)과 publicId 순(member < admin)이 거꾸로 되게 둔다 — 두 경로의 순서가 다르면 맞물린다
        jdbcTemplate.update("update users set user_role = 'ROLE_ADMIN', public_id = 'fffffffffff1' where user_id = ?", admin.getId());
        jdbcTemplate.update("update users set public_id = '000000000001' where user_id = ?", member.getId());
        WorkspaceResponse org = workspaceService.createOrganization("fffffffffff1", "관리자 공간");   // OWNER admin·member
        membershipRepository.save(WorkspaceMembership.member(idOf(org), member.getId(), WorkspaceRole.OWNER));

        // admin 의 회원 행을 붙잡아 둘 다 그 앞에 세운다. 탈퇴 신청은 admin → member 순으로 잡는다.
        // 정지가 member 를 먼저 쥐었다면(publicId 순) 탈퇴 신청은 member 를, 정지는 admin 을 기다리며 맞물린다
        List<Throwable> failures = raceInOrder(() -> userRepository.lockStayingIds(List.of(admin.getId())),
                () -> userExitService.requestExit("000000000001"),
                () -> adminUserService.block("fffffffffff1", "000000000001", "경쟁"));

        // 먼저 선 탈퇴 신청이 되고 정지는 탈퇴 대기라 거절된다(AUTH) — 서버 오류(교착)는 없다
        assertThat(failures).allSatisfy(failure -> assertThat(failure).isInstanceOf(BusinessException.class));
        assertThat(userRepository.findById(member.getId()).orElseThrow().getUserStatus()).isEqualTo(UserStatus.DELETED_REQUESTED);
    }

    private Long idOf(WorkspaceResponse workspace) {
        return workspaceRepository.findByPublicId(workspace.workspaceId()).orElseThrow().getId();
    }

    @Test
    @DisplayName("같은 프레임을 동시에 두 번 발행해도 번호가 겹치지 않는다(1, 2)")
    void concurrentFramePublishGetsDistinctNumbers() throws Exception {
        User owner = userRepository.save(User.localUser("owner@frame.test", "e", "오너"));
        WorkspaceResponse org = workspaceService.createOrganization(owner.getPublicId(), "프레임 공간");
        Long frameId = frameService.createFrame(owner.getPublicId(), new FrameCreateRequest("프레임", null,
                S3Keys.userRoot(owner.getPublicId()) + "frames/p.png", FrameType.CLASSIC, null, null,
                new BackgroundAttributes.Color("#FFFFFF"), null, List.of(), null, org.workspaceId())).frameId();

        List<Throwable> failures = raceBehind(() -> frameRepository.findByIdForUpdate(frameId),
                () -> frameVersionService.publish(owner.getPublicId(), frameId),
                () -> frameVersionService.publish(owner.getPublicId(), frameId));

        assertThat(failures).isEmpty();   // 예전에는 뒤쪽이 같은 번호로 uk_frame_version_frame_no 위반
        assertThat(frameVersionService.list(owner.getPublicId(), frameId)).extracting(FrameVersionResponse::versionNo)
                .containsExactlyInAnyOrder(1, 2);
    }

    @Test
    @DisplayName("서로 다른 두 프레임의 첫 발행이 동시에 와도 교착 없이 둘 다 1번이 된다")
    void concurrentFirstPublishOfDifferentFrames() throws Exception {
        User owner = userRepository.save(User.localUser("owner@frames.test", "e", "오너"));
        WorkspaceResponse org = workspaceService.createOrganization(owner.getPublicId(), "두 프레임 공간");
        Long first = newFrame(owner, org);
        Long second = newFrame(owner, org);

        // 발행본 유니크 인덱스의 맨 끝 빈 구간을 먼저 잡아 두 발행의 insert 를 멈춰 세운다 — 예전 방식(번호 범위 잠금 읽기)이면
        // 두 발행이 그 구간에 gap lock 을 함께 잡은 채 서로의 insert 를 기다려, 풀자마자 교착된다
        List<Throwable> failures = raceBehind(() -> jdbcTemplate.queryForList(
                        "select frame_version_id from frame_version where frame_id = ? for update", Long.MAX_VALUE),
                () -> frameVersionService.publish(owner.getPublicId(), first),
                () -> frameVersionService.publish(owner.getPublicId(), second));

        assertThat(failures).isEmpty();
        assertThat(frameVersionService.list(owner.getPublicId(), first)).extracting(FrameVersionResponse::versionNo).containsExactly(1);
        assertThat(frameVersionService.list(owner.getPublicId(), second)).extracting(FrameVersionResponse::versionNo).containsExactly(1);
    }

    @Test
    @DisplayName("다른 공간의 두 행사가 동시에 첫 부스 기기를 만들어도 둘 다 만들어진다(교착이면 다시 한다)")
    void concurrentDeviceCreateAcrossWorkspaces() throws Exception {
        User first = userRepository.save(User.localUser("a@booth.test", "e", "가"));
        User second = userRepository.save(User.localUser("b@booth.test", "e", "나"));
        EventResponse firstEvent = draftEvent(first, "booth-gap-a");
        EventResponse secondEvent = draftEvent(second, "booth-gap-b");

        List<Throwable> failures = raceBehind(() -> jdbcTemplate.queryForList(
                        "select booth_device_id from booth_device where event_id = ? for update", Long.MAX_VALUE),
                () -> boothDeviceService.create(first.getPublicId(), firstEvent.eventId(), "기기"),
                () -> boothDeviceService.create(second.getPublicId(), secondEvent.eventId(), "기기"));

        assertThat(failures).isEmpty();
        assertThat(boothDeviceService.list(first.getPublicId(), firstEvent.eventId())).hasSize(1);
        assertThat(boothDeviceService.list(second.getPublicId(), secondEvent.eventId())).hasSize(1);
    }

    private Long newFrame(User owner, WorkspaceResponse org) {
        return frameService.createFrame(owner.getPublicId(), new FrameCreateRequest("프레임", null,
                S3Keys.userRoot(owner.getPublicId()) + "frames/p.png", FrameType.CLASSIC, null, null,
                new BackgroundAttributes.Color("#FFFFFF"), null, List.of(), null, org.workspaceId())).frameId();
    }

    private EventResponse draftEvent(User owner, String slug) {
        WorkspaceResponse org = workspaceService.createOrganization(owner.getPublicId(), slug);
        return eventService.create(owner.getPublicId(), org.workspaceId(),
                new EventSettings(slug, slug, null, null, null, null, null, null, null, null, null, null, null, null));
    }

    // lock 으로 행을 잡아 둔 채 두 요청을 출발시키고, 둘 다 기다리는 중에 푼다. 실패한 요청의 예외 목록을 돌려준다
    private List<Throwable> raceBehind(Runnable lock, Runnable a, Runnable b) throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> transaction.executeWithoutResult(status -> {
            lock.run();
            locked.countDown();
            await(release);
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Throwable> first = CompletableFuture.supplyAsync(() -> attempt(a));
        CompletableFuture<Throwable> second = CompletableFuture.supplyAsync(() -> attempt(b));
        Thread.sleep(1000);   // 둘 다 스냅샷을 잡고 잠금에서 기다리는 중
        release.countDown();
        holder.get(20, TimeUnit.SECONDS);
        List<Throwable> failures = new ArrayList<>();
        for (CompletableFuture<Throwable> f : List.of(first, second)) {
            Throwable failure = f.get(30, TimeUnit.SECONDS);
            if (failure != null) {
                failures.add(failure);
            }
        }
        return failures;
    }

    // raceBehind 와 같되 a 가 먼저 잠금 앞에 서도록 b 를 늦게 출발시킨다
    private List<Throwable> raceInOrder(Runnable lock, Runnable a, Runnable b) throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> transaction.executeWithoutResult(status -> {
            lock.run();
            locked.countDown();
            await(release);
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Throwable> first = CompletableFuture.supplyAsync(() -> attempt(a));
        Thread.sleep(700);
        CompletableFuture<Throwable> second = CompletableFuture.supplyAsync(() -> attempt(b));
        Thread.sleep(1000);
        release.countDown();
        holder.get(20, TimeUnit.SECONDS);
        List<Throwable> failures = new ArrayList<>();
        for (CompletableFuture<Throwable> f : List.of(first, second)) {
            Throwable failure = f.get(30, TimeUnit.SECONDS);
            if (failure != null) {
                failures.add(failure);
            }
        }
        return failures;
    }

    private static Throwable attempt(Runnable action) {
        try {
            action.run();
            return null;
        } catch (RuntimeException e) {
            return e;
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

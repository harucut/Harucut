package com.harucut.support;

import com.harucut.entitlement.entity.Contract;
import com.harucut.entitlement.entity.Entitlement;
import com.harucut.entitlement.enums.Feature;
import com.harucut.entitlement.repository.EntitlementRepository;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

/** 행사 게시·합성에 이용권이 필요해진 뒤(P2B-5) 흐름 테스트가 쓰는 넉넉한 수동 이용권 */
public final class TestEntitlements {

    private TestEntitlements() {
    }

    /** 공간 범위, 합성 1,000건, 모든 기능, 어제부터 1년 */
    public static Entitlement grant(EntitlementRepository repository, Long workspaceId) {
        return grant(repository, workspaceId, 1000);
    }

    public static Entitlement grant(EntitlementRepository repository, Long workspaceId, int composeQuota) {
        return grant(repository, workspaceId, composeQuota, EnumSet.allOf(Feature.class));
    }

    public static Entitlement grant(EntitlementRepository repository, Long workspaceId, int composeQuota, Set<Feature> features) {
        LocalDateTime now = LocalDateTime.now();
        return repository.save(Entitlement.manual(workspaceId, null,
                new Contract.Terms(composeQuota, null, null, null, features),
                now.minusDays(1), now.plusYears(1), null, "테스트"));
    }
}

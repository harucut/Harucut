package com.harucut.config.openapi;

import com.harucut.asset.exception.AssetErrorCode;
import com.harucut.auth.exception.AuthErrorCode;
import com.harucut.common.exception.ErrorCode;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.coupon.exception.CouponErrorCode;
import com.harucut.draft.exception.DraftErrorCode;
import com.harucut.event.exception.EventErrorCode;
import com.harucut.frame.exception.FrameErrorCode;
import com.harucut.media.exception.MediaErrorCode;
import com.harucut.notice.exception.NoticeErrorCode;
import com.harucut.participant.exception.ParticipantErrorCode;
import com.harucut.payment.exception.PaymentErrorCode;
import com.harucut.result.exception.ResultErrorCode;
import com.harucut.subscription.exception.SubscriptionErrorCode;
import com.harucut.terms.exception.TermsErrorCode;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 코드 문자열("AUTH-011") → {@link ErrorCode} 를 찾는 표.
 *
 * <p>{@link ApiErrors} 가 문자열을 받기 때문에 필요하다. 문서에 적히는 상태 코드와 메시지가
 * <b>실제 응답을 만드는 enum 과 같은 값</b>임을 이 표가 보장한다. 손으로 옮겨 적는 순간
 * 문서는 코드와 어긋나기 시작한다.
 *
 * <p>enum 목록은 일부러 손으로 적었다. 클래스패스 스캔으로 자동화할 수 있지만,
 * 도메인이 8개뿐이고 새 도메인은 몇 달에 하나 생긴다. 빠뜨리면 기동이 실패하며 이 파일을 가리킨다.
 */
public final class ErrorCodeCatalog {

    private static final Map<String, ErrorCode> BY_CODE = Stream.of(
                    GlobalErrorCode.values(),
                    AuthErrorCode.values(),
                    NoticeErrorCode.values(),
                    TermsErrorCode.values(),
                    SubscriptionErrorCode.values(),
                    FrameErrorCode.values(),
                    PaymentErrorCode.values(),
                    CouponErrorCode.values(),
                    MediaErrorCode.values(),
                    AssetErrorCode.values(),
                    DraftErrorCode.values(),
                    EventErrorCode.values(),
                    ParticipantErrorCode.values(),
                    ResultErrorCode.values())
            .flatMap(Arrays::stream)
            .collect(Collectors.toMap(
                    ErrorCode::getCode,
                    Function.identity(),
                    (a, b) -> {
                        throw new IllegalStateException("에러 코드가 두 enum 에서 중복 선언됐다: " + a.getCode());
                    },
                    LinkedHashMap::new));

    private ErrorCodeCatalog() {
    }

    /** 등록된 모든 에러 코드(선언 순서). 프론트 계약 산출물(docs/api/error-codes.generated.json)이 이걸 쓴다. */
    public static Collection<ErrorCode> all() {
        return Collections.unmodifiableCollection(BY_CODE.values());
    }

    public static ErrorCode require(String code) {
        ErrorCode found = BY_CODE.get(code);
        if (found == null) {
            throw new IllegalArgumentException(
                    "@ApiErrors 에 존재하지 않는 에러 코드가 있다: '" + code + "'. "
                            + "ErrorCode enum 에 없거나, ErrorCodeCatalog 에 그 enum 이 등록되지 않았다.");
        }
        return found;
    }
}

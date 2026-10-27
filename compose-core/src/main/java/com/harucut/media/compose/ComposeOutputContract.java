package com.harucut.media.compose;

/**
 * 합성 출력 계약 버전(R5 D09). 작업 접수 때 정해 작업·payload 에 함께 싣는다.
 *
 * <ul>
 *   <li>{@link #LEGACY}: 결과·썸네일을 무조건 PUT 한다. 성공 응답에 체크섬이 없다.</li>
 *   <li>{@link #VERIFIED_PAIR}: 두 출력 모두 {@code If-None-Match: *} 로 조건부 생성하고, 실제 바이트의 SHA-256 을 S3 가
 *   업로드 때 검증·저장하게 한다. 412(이미 있음)면 저장된 체크섬을 다시 읽어 이 실행의 예상값과 같을 때만 성공이다.
 *   성공 응답({@link ComposeLambdaResult})에 두 체크섬을 싣고, 서버는 저장된 한 쌍을 다시 대조한 뒤에만 완료로 확정한다.</li>
 * </ul>
 *
 * 배포 순서: 소비자(서버)·Lambda 가 두 계약을 다 받게 먼저 내보낸 뒤, 서버 설정 compose.output-contract 를 2 로 올린다.
 */
public final class ComposeOutputContract {

    public static final int LEGACY = 1;
    public static final int VERIFIED_PAIR = 2;

    private ComposeOutputContract() {
    }
}

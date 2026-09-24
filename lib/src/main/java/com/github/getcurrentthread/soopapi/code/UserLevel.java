package com.github.getcurrentthread.soopapi.code;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

import com.github.getcurrentthread.soopapi.util.SOOPChatUtils;

/**
 * 파싱된 사용자 레벨 — 주/보조 두 비트 플래그 그룹의 컨테이너.
 *
 * <p>SOOP 입장 플래그는 {@code "primary|secondary"} 형식의 문자열로 전달됩니다(예: {@code "81952|32768"}). 두 정수는 서로 다른
 * 비트 의미를 가지므로 각각 {@link UserFlag}, {@link UserFlag2}로 분해합니다.
 *
 * <p>두 집합은 생성 시 복사되어 수정 불가능한 집합으로 보관됩니다(선언 순서 유지). {@link #primary()}/{@link #secondary()}가 반환한 집합을
 * 수정하려 하면 {@link UnsupportedOperationException}이 발생합니다.
 *
 * @param primary 주 그룹 플래그 집합
 * @param secondary 보조 그룹 플래그 집합
 */
public record UserLevel(Set<UserFlag> primary, Set<UserFlag2> secondary) {

    /** 빈/해제 상태. {@code null}·빈 문자열·파싱 불가 입력에 대해 반환됩니다. */
    public static final UserLevel EMPTY =
            new UserLevel(EnumSet.noneOf(UserFlag.class), EnumSet.noneOf(UserFlag2.class));

    /**
     * 두 집합을 수정 불가능한 복사본으로 고정합니다.
     *
     * @throws NullPointerException {@code primary} 또는 {@code secondary}가 {@code null}인 경우
     */
    public UserLevel {
        primary = freeze(primary, UserFlag.class);
        secondary = freeze(secondary, UserFlag2.class);
    }

    /**
     * {@code "primary|secondary"} 플래그 문자열을 파싱합니다.
     *
     * <p>방어적으로 동작하며 절대 예외를 던지지 않습니다.
     *
     * <ul>
     *   <li>{@code null}/빈 문자열 → {@link #EMPTY}
     *   <li>{@code |} 없음 → 전체를 주 그룹으로, 보조는 빈 집합
     *   <li>숫자가 아닌 쪽 또는 32비트 범위를 벗어난 쪽 → 해당 그룹은 빈 집합
     *   <li>각 그룹은 부호 있는 표기({@code "-2147483648"})와 부호 없는 표기({@code "2147483648"})를 모두 받아 같은 32비트
     *       마스크로 해석합니다
     * </ul>
     *
     * @param flag 입장 플래그 문자열
     * @return 파싱된 {@link UserLevel}
     */
    public static UserLevel parse(String flag) {
        if (flag == null || flag.isEmpty()) {
            return EMPTY;
        }
        int pipe = flag.indexOf('|');
        if (pipe < 0) {
            int p = parseMask(flag);
            return new UserLevel(UserFlag.fromMask(p), EnumSet.noneOf(UserFlag2.class));
        }
        int p = parseMask(flag.substring(0, pipe));
        int s = parseMask(flag.substring(pipe + 1));
        return new UserLevel(UserFlag.fromMask(p), UserFlag2.fromMask(s));
    }

    /**
     * 10진 마스크 문자열을 32비트 정수로 읽습니다. 부호 있는 값({@code Integer.MIN_VALUE}~)과 부호 없는 값(~{@code
     * 0xFFFFFFFF})을 모두 받으며, 숫자가 아니거나 범위를 벗어나면 {@code 0}을 반환합니다. 예외를 던지지 않습니다.
     */
    private static int parseMask(String text) {
        long v = SOOPChatUtils.safeParseLong(text.trim(), 0L);
        return (v >= Integer.MIN_VALUE && v <= 0xFFFFFFFFL) ? (int) v : 0;
    }

    private static <E extends Enum<E>> Set<E> freeze(Set<E> source, Class<E> type) {
        EnumSet<E> copy = EnumSet.noneOf(type);
        copy.addAll(Objects.requireNonNull(source));
        return Collections.unmodifiableSet(copy);
    }

    /**
     * 주 그룹에 해당 플래그가 있는지 확인합니다.
     *
     * @param flag 확인할 주 그룹 플래그
     * @return 포함 여부
     */
    public boolean has(UserFlag flag) {
        return primary.contains(flag);
    }

    /**
     * 보조 그룹에 해당 플래그가 있는지 확인합니다.
     *
     * @param flag 확인할 보조 그룹 플래그
     * @return 포함 여부
     */
    public boolean has(UserFlag2 flag) {
        return secondary.contains(flag);
    }
}

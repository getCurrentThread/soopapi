package com.github.getcurrentthread.soopapi.code;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class UserLevelTest {

    @Test
    void parse_bothGroups() {
        UserLevel level = UserLevel.parse("81952|32768");
        assertEquals(Set.of(UserFlag.FANCLUB, UserFlag.MOBILE, UserFlag.REALNAME), level.primary());
        assertEquals(Set.of(UserFlag2.SPECIFY), level.secondary());
    }

    @Test
    void parse_simplePair() {
        UserLevel level = UserLevel.parse("16|16384");
        assertEquals(Set.of(UserFlag.GUEST), level.primary());
        assertEquals(Set.of(UserFlag2.PC), level.secondary());
    }

    @Test
    void has_convenienceChecks() {
        UserLevel level = UserLevel.parse("16|16384");
        assertTrue(level.has(UserFlag.GUEST));
        assertTrue(level.has(UserFlag2.PC));
    }

    @Test
    void parse_nullAndBlankReturnEmpty() {
        assertSame(UserLevel.EMPTY, UserLevel.parse(null));
        assertSame(UserLevel.EMPTY, UserLevel.parse(""));
    }

    @Test
    void parse_nonNumericReturnsEmptySets() {
        UserLevel level = UserLevel.parse("abc|def");
        assertTrue(level.primary().isEmpty());
        assertTrue(level.secondary().isEmpty());
    }

    @Test
    void parse_noPipeTreatsWholeAsPrimary() {
        UserLevel level = UserLevel.parse("16");
        assertEquals(Set.of(UserFlag.GUEST), level.primary());
        assertTrue(level.secondary().isEmpty());
    }

    @Test
    void parse_trailingAndLeadingPipe() {
        UserLevel trailing = UserLevel.parse("16|");
        assertEquals(Set.of(UserFlag.GUEST), trailing.primary());
        assertTrue(trailing.secondary().isEmpty());

        UserLevel leading = UserLevel.parse("|16384");
        assertTrue(leading.primary().isEmpty());
        assertEquals(Set.of(UserFlag2.PC), leading.secondary());
    }

    @Test
    void sets_areImmutable() {
        assertThrows(
                UnsupportedOperationException.class,
                () -> UserLevel.parse(null).primary().add(UserFlag.ADMIN));
        assertThrows(
                UnsupportedOperationException.class,
                () -> UserLevel.EMPTY.secondary().add(UserFlag2.PC));
        assertThrows(
                UnsupportedOperationException.class,
                () -> UserLevel.parse("16|16384").primary().clear());
        assertThrows(
                UnsupportedOperationException.class,
                () -> UserLevel.parse("16|16384").secondary().remove(UserFlag2.PC));
        // the shared EMPTY instance stays empty
        assertFalse(UserLevel.parse("").has(UserFlag.ADMIN));
        assertTrue(UserLevel.EMPTY.primary().isEmpty());
    }

    @Test
    void constructor_copiesInputAndKeepsDeclarationOrder() {
        EnumSet<UserFlag> source = EnumSet.of(UserFlag.MANAGER, UserFlag.GUEST);
        UserLevel level = new UserLevel(source, Set.of());
        source.add(UserFlag.ADMIN);
        assertFalse(level.has(UserFlag.ADMIN));
        assertEquals(List.of(UserFlag.GUEST, UserFlag.MANAGER), List.copyOf(level.primary()));
        // empty non-EnumSet input is accepted
        assertEquals(UserLevel.EMPTY, new UserLevel(Set.of(), Set.of()));
        assertThrows(NullPointerException.class, () -> new UserLevel(null, Set.of()));
    }

    @Test
    void parse_signBitMask_signedAndUnsigned() {
        assertEquals(Set.of(UserFlag.NOTITOPFAN), UserLevel.parse("2147483648|0").primary());
        assertEquals(Set.of(UserFlag.NOTITOPFAN), UserLevel.parse("-2147483648|0").primary());
        assertEquals(
                Set.of(UserFlag.FANCLUB, UserFlag.NOTIVODBALLOON, UserFlag.NOTITOPFAN),
                UserLevel.parse("3221225504|0").primary());
        // no-pipe path is unsigned-aware too
        assertTrue(UserLevel.parse("2147483652").has(UserFlag.BJ));
    }

    @Test
    void parse_outOfRangeMaskReturnsEmptyGroup() {
        assertTrue(UserLevel.parse("4294967296|0").primary().isEmpty());
        assertTrue(UserLevel.parse("-2147483649|0").primary().isEmpty());
    }
}

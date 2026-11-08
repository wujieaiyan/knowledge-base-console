package com.kbconsole.common;

public enum Role {
    READER(1), EDITOR(2), ADMIN(3), OWNER(4);

    private final int rank;

    Role(int rank) {
        this.rank = rank;
    }

    public boolean atLeast(Role other) {
        return this.rank >= other.rank;
    }
}

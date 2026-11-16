package cn.mythicland.memberlib;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class MemberOwnershipSwap {
    private final String statement;
    private final List<String> parameters;

    public MemberOwnershipSwap(Set<MemberScope> scopes, Map<MemberScope, List<MemberKey>> keysByScope) {
        List<String> clauses = new ArrayList<>();
        List<String> values = new ArrayList<>();
        if (!scopes.isEmpty()) {
            clauses.add("scope IN (" + placeholders(scopes.size()) + ")");
            for (MemberScope scope : scopes) values.add(scope.value());
        }
        for (var entry : keysByScope.entrySet()) {
            List<MemberKey> keys = entry.getValue();
            if (keys.isEmpty()) continue;
            clauses.add("(scope = ? AND membership_id IN (" + placeholders(keys.size()) + "))");
            values.add(entry.getKey().value());
            for (MemberKey key : keys) values.add(key.value());
        }
        if (clauses.isEmpty()) throw new IllegalArgumentException("Membership ownership selection is empty");
        this.statement = "UPDATE mythic_membership SET player_uuid = ? WHERE player_uuid = ? AND ("
                + String.join(" OR ", clauses) + ")";
        this.parameters = List.copyOf(values);
    }

    /**
     * Exchanges selected rows using the caller's transaction. The caller owns commit,
     * rollback and refreshing any loaded MemberService views after commit.
     */
    public void swap(Connection connection, DatabaseMode mode, UUID first, UUID second) throws SQLException {
        if (connection.getAutoCommit()) {
            throw new IllegalArgumentException("Membership ownership swap requires an active transaction");
        }
        if (first.equals(second)) throw new IllegalArgumentException("Membership owners must be distinct");
        if (mode == DatabaseMode.POSTGRESQL) {
            try (var lock = connection.createStatement()) {
                lock.execute("LOCK TABLE mythic_membership IN SHARE ROW EXCLUSIVE MODE");
            }
        }
        try (PreparedStatement update = connection.prepareStatement(this.statement)) {
            for (int index = 0; index < this.parameters.size(); index++) {
                update.setString(index + 3, this.parameters.get(index));
            }
            UUID temporary = UUID.randomUUID();
            move(update, first, temporary);
            move(update, second, first);
            move(update, temporary, second);
        }
    }

    private static void move(PreparedStatement update, UUID from, UUID to) throws SQLException {
        update.setString(1, to.toString());
        update.setString(2, from.toString());
        update.executeUpdate();
    }

    private static String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }
}

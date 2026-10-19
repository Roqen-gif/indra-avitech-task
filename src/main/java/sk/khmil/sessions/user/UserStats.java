package sk.khmil.sessions.user;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Snapshot of the statistics printed by {@code STATS()}.
 *
 * @param loggedInUsers number of active sessions
 * @param modificationsPerUser modification count per user id, sorted by user id
 */
public record UserStats(long loggedInUsers, SortedMap<String, Long> modificationsPerUser) {

  public UserStats {
    if (loggedInUsers < 0) {
      throw new IllegalArgumentException("loggedInUsers must not be negative");
    }
    Objects.requireNonNull(modificationsPerUser, "modificationsPerUser");
    modificationsPerUser = Collections.unmodifiableSortedMap(new TreeMap<>(modificationsPerUser));
  }

  public static UserStats of(long loggedInUsers, Map<String, Long> modificationsPerUser) {
    return new UserStats(loggedInUsers, new TreeMap<>(modificationsPerUser));
  }
}

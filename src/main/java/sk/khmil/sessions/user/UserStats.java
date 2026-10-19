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
 * @param usersModifyingData logged-in users with at least one modification in their current session
 * @param modificationsPerUser modification count per user id, sorted by user id
 */
public record UserStats(
    long loggedInUsers, long usersModifyingData, SortedMap<String, Long> modificationsPerUser) {

  public UserStats {
    if (loggedInUsers < 0 || usersModifyingData < 0) {
      throw new IllegalArgumentException("counts must not be negative");
    }
    Objects.requireNonNull(modificationsPerUser, "modificationsPerUser");
    modificationsPerUser = Collections.unmodifiableSortedMap(new TreeMap<>(modificationsPerUser));
  }

  public static UserStats of(
      long loggedInUsers, long usersModifyingData, Map<String, Long> modificationsPerUser) {
    return new UserStats(loggedInUsers, usersModifyingData, new TreeMap<>(modificationsPerUser));
  }
}

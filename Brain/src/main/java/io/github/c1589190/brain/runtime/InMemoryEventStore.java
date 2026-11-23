// Modified for AgentMosire from the ProjectMosire source.

package io.github.c1589190.brain.runtime;

import io.github.c1589190.agentlib.event.Event;
import io.github.c1589190.agentlib.event.EventQuery;
import io.github.c1589190.agentlib.event.EventStore;
import io.github.c1589190.agentlib.event.EventWrite;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** 单个内存运行时的事件库。所有读写在同一把锁内完成，查询返回独立快照。 */
final class InMemoryEventStore implements EventStore {

  private final List<Event> events = new ArrayList<>();
  private boolean closed;

  @Override
  public synchronized Event append(EventWrite write) {
    requireOpen();
    Event event =
        new Event(
            events.size() + 1L,
            Instant.now(),
            write.type(),
            write.agent(),
            write.payload(),
            write.correlationId());
    events.add(event);
    return event;
  }

  @Override
  public synchronized List<Event> query(EventQuery query) {
    requireOpen();
    return events.reversed().stream()
        .filter(event -> query.beforeSeq() < 0 || event.seq() < query.beforeSeq())
        .filter(event -> matches(query.agent(), event.agent()))
        .filter(event -> matches(query.type(), event.type()))
        .filter(event -> matches(query.correlationId(), event.correlationId()))
        .limit(query.limit())
        .toList();
  }

  @Override
  public synchronized List<Event> queryLatestByCorrelation(String type, int limit, int offset) {
    Objects.requireNonNull(type, "type");
    if (limit <= 0) {
      throw new IllegalArgumentException("limit 必须为正: " + limit);
    }
    if (offset < 0) {
      throw new IllegalArgumentException("offset 不能为负: " + offset);
    }
    requireOpen();
    Set<String> seen = new HashSet<>();
    List<Event> page = new ArrayList<>();
    for (Event event : events.reversed()) {
      if (event.type().equals(type) && seen.add(event.correlationId()) && seen.size() > offset) {
        page.add(event);
        if (page.size() == limit) {
          break;
        }
      }
    }
    return List.copyOf(page);
  }

  @Override
  public synchronized Optional<Event> bySeq(long seq) {
    requireOpen();
    return seq <= 0 || seq > events.size()
        ? Optional.empty()
        : Optional.of(events.get((int) seq - 1));
  }

  @Override
  public synchronized long count() {
    requireOpen();
    return events.size();
  }

  @Override
  public synchronized void close() {
    closed = true;
    events.clear();
  }

  private static boolean matches(String filter, String value) {
    return filter.isEmpty() || filter.equals(value);
  }

  private void requireOpen() {
    if (closed) {
      throw new IllegalStateException("内存事件存储已关闭");
    }
  }
}

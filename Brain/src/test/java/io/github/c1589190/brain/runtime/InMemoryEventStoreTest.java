// Modified for AgentMosire from the ProjectMosire source.

package io.github.c1589190.brain.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.c1589190.agentlib.event.EventQuery;
import io.github.c1589190.agentlib.event.EventWrite;
import org.junit.jupiter.api.Test;

class InMemoryEventStoreTest {

  @Test
  void queriesLatestMatchesWithExclusiveCursorsAndGroupedPagination() {
    var store = new InMemoryEventStore();
    try (store) {
      var first = store.append(EventWrite.of("task", "one", "初版", "a"));
      var second = store.append(EventWrite.of("task", "two", "第二项", "b"));
      var latest = store.append(EventWrite.of("task", "one", "更新", "a"));
      var other = store.append(EventWrite.of("other", "one", "另一类型", "b"));

      assertThat(first.seq()).isEqualTo(1);
      assertThat(other.seq()).isEqualTo(4);
      assertThat(store.count()).isEqualTo(4);
      assertThat(store.bySeq(second.seq())).contains(second);
      assertThat(store.bySeq(other.seq() + 1)).isEmpty();
      assertThat(store.query(EventQuery.recent(2))).containsExactly(other, latest);
      assertThat(store.query(new EventQuery("one", "task", "a", latest.seq(), 2)))
          .containsExactly(first);
      assertThat(store.queryLatestByCorrelation("task", 2, 0)).containsExactly(latest, second);
      assertThat(store.queryLatestByCorrelation("task", 1, 1)).containsExactly(second);
    }

    assertThatThrownBy(() -> store.query(EventQuery.recent(1)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("已关闭");
  }
}

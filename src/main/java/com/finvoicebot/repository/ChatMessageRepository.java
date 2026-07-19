package com.finvoicebot.repository;

import com.finvoicebot.model.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    List<ChatMessage> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    List<ChatMessage> findByUserIdOrderByCreatedAtDesc(String userId);

    /** Distinct session ids for a user, most recent first — powers the sidebar. */
    @Query("select m.sessionId from ChatMessage m where m.userId = :userId group by m.sessionId order by max(m.createdAt) desc")
    List<String> findDistinctSessionIdsByUserId(String userId);
}

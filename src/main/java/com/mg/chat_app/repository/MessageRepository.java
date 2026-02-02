package com.mg.chat_app.repository;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.mg.chat_app.entity.Message;
import com.mg.chat_app.model.MessageStatus;

public interface MessageRepository extends JpaRepository<Message, Long> {

    List<Message> findByReceiverIdAndStatus(Long receiverId, MessageStatus status);

    List<Message> findBySenderId(Long senderId);
    //paginated queries: findByReceiverIdAndStatus, findConversation, findByGroupIdOrderByCreatedAtDesc
    Page<Message> findByReceiverIdAndStatus(Long receiverId, MessageStatus status, Pageable pageable);

    @Query("SELECT m FROM Message m WHERE " +
           "(m.senderId = :user1 AND m.receiverId = :user2) OR " +
           "(m.senderId = :user2 AND m.receiverId = :user1) " +
           "ORDER BY m.createdAt DESC")
    
    //paginated queries: findByReceiverIdAndStatus, findConversation, findByGroupIdOrderByCreatedAtDesc
    Page<Message> findConversation(@Param("user1") Long user1, @Param("user2") Long user2, Pageable pageable);

    //paginated queries: findByReceiverIdAndStatus, findConversation, findByGroupIdOrderByCreatedAtDesc
    Page<Message> findByGroupIdOrderByCreatedAtDesc(Long groupId, Pageable pageable);

    @Query("SELECT m.senderId, COUNT(m) FROM Message m " +
           "WHERE m.receiverId = :receiverId AND m.status IN :statuses " +
           "GROUP BY m.senderId")
    List<Object[]> countUnreadBySender(@Param("receiverId") Long receiverId,
                                       @Param("statuses") List<MessageStatus> statuses);
}

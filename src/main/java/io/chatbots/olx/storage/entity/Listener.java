package io.chatbots.olx.storage.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Temporal;
import jakarta.persistence.TemporalType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "listeners", indexes = {
        @Index(name = "idx_listeners_chat_active", columnList = "chat_id, active")
})
public class Listener {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private long userId;
    private long chatId;
    private String userFirstName;
    private String userLastName;
    private String userName;
    private String userLanguageCode;

    @Column(length = 2048)
    private String url;

    @Temporal(TemporalType.TIMESTAMP)
    private Date updated;

    private boolean active;
}

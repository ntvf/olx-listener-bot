package io.chatbots.olx.storage.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jongo.marshall.jackson.oid.MongoId;
import org.jongo.marshall.jackson.oid.MongoObjectId;

import java.util.Date;
import java.util.Set;


@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Listener {
    @MongoId
    @MongoObjectId
    private String id;
    private long userId;
    private long chatId;
    private String userFirstName;
    private String userLastName;
    private String userName;
    private String userLanguageCode;
    private String url;
    private Set<String> lastOffersHashes;
    private Date updated;
    private boolean active;

}

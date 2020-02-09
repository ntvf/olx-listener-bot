package io.chatbots.olx;

import lombok.Builder;
import lombok.Data;
import lombok.Singular;
import org.telegram.telegrambots.meta.api.methods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;

import java.util.List;

/**
 * Created by tymur on 10/23/18.
 */
@Builder
@Data
public class HandleResult {
    public static final HandleResult EMPTY = HandleResult.builder().build();
    @Singular
    private List<BotApiMethod> botApiMethods;
    @Singular
    private List<SendDocument> sendDocuments;
    @Builder.Default private PostHandleCallBack callBack = () -> {};
}

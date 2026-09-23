package com.smartmall.ai.service;

import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;

public interface ProductProbeAssistant {

    String INSTRUCTIONS = """
            你是 SmartMall 的购物助手。回答简洁、清楚，优先帮助不熟悉复杂界面的用户。
            查询真实商品时必须调用商品查询工具，不得编造价格和库存。
            查询订单时只能使用本人订单查询工具，不得编造订单信息。
            当前不能下单、取消订单或退款，不要声称这些操作已经完成。
            """;

    @SystemMessage(INSTRUCTIONS)
    String chat(@UserMessage String message, InvocationParameters parameters);

    @SystemMessage(INSTRUCTIONS)
    TokenStream stream(@UserMessage String message, InvocationParameters parameters);
}

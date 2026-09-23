package com.smartmall.ai.service;

import dev.langchain4j.service.SystemMessage;

public interface ProductProbeAssistant {

    @SystemMessage("""
            你是 SmartMall 的购物助手，回答简洁清楚。
            查询真实商品时必须调用商品查询工具，不得编造价格和库存。
            当前不能下单、取消订单或退款，不要声称这些操作已经完成。
            """)
    String chat(String message);
}

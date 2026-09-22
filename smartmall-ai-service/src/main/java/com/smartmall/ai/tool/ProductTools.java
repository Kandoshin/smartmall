package com.smartmall.ai.tool;

import com.smartmall.ai.service.CommerceToolService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

@Component
public class ProductTools {

    private final CommerceToolService commerceToolService;

    public ProductTools(CommerceToolService commerceToolService) {
        this.commerceToolService = commerceToolService;
    }

    @Tool("查询 SmartMall 中当前在售的商品，可按商品名称模糊搜索")
    public String searchProducts(
            @P(
                    value = "商品名称关键词，例如机械键盘；不指定时查询全部在售商品",
                    required = false
            )
    String name) {
        return commerceToolService.searchProducts(name);
    }
}

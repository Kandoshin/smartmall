package com.smartmall.ai.tool;

import com.smartmall.ai.service.CommerceToolService;
import dev.langchain4j.invocation.InvocationParameters;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderToolsTest {

    private final CommerceToolService commerceToolService =
            mock(CommerceToolService.class);
    private final OrderTools orderTools = new OrderTools(commerceToolService);

    @Test
    void listMyOrdersForwardsTrustedAccessToken() {
        InvocationParameters parameters =
                InvocationParameters.from("accessToken", "access-token");
        when(commerceToolService.listMyOrders("access-token"))
                .thenReturn("order-response");

        String result = orderTools.listMyOrders(parameters);

        assertEquals("order-response", result);
        verify(commerceToolService).listMyOrders("access-token");
    }

    @Test
    void listMyOrdersRejectsMissingAccessToken() {
        InvocationParameters parameters = new InvocationParameters();

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> orderTools.listMyOrders(parameters));

        assertEquals("缺少当前用户的登录凭证", exception.getMessage());
        verify(commerceToolService, never()).listMyOrders(null);
    }
}

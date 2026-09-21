package com.smartmall.ai.service;

import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.core.http.StreamResponse;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseStreamEvent;
import com.smartmall.ai.exception.AiUpstreamException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class AiService {

    private static final int MAX_TOOL_ROUNDS = 3;
    private static final String INSTRUCTIONS = """
            你是 SmartMall 的购物助手。回答简洁、清楚，优先帮助不熟悉复杂界面的用户。
            涉及真实商品和订单时必须调用工具，不得编造数据。
            目前只能查询在售商品和当前登录用户自己的订单，不能承诺已经下单、取消或退款。
            """;

    private final OpenAIClient openAIClient;
    private final CommerceToolService commerceToolService;
    private final String model;
    private final FunctionTool searchProductsTool = createSearchProductsTool();
    private final FunctionTool listMyOrdersTool = createListMyOrdersTool();

    public AiService(
            OpenAIClient openAIClient,
            CommerceToolService commerceToolService,
            @Value("${smartmall.ai.model}") String model) {
        this.openAIClient = openAIClient;
        this.commerceToolService = commerceToolService;
        this.model = model;
    }

    public void chatStream(String message, String accessToken, ChatStreamSink sink) {
        List<ResponseInputItem> conversation = new ArrayList<>();
        conversation.add(ResponseInputItem.ofEasyInputMessage(
                EasyInputMessage.builder()
                        .role(EasyInputMessage.Role.USER)
                        .content(message)
                        .build()));

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            Response response = createStreamingResponse(conversation, sink);
            List<ResponseFunctionToolCall> toolCalls = response.output().stream()
                    .filter(ResponseOutputItem::isFunctionCall)
                    .map(ResponseOutputItem::asFunctionCall)
                    .toList();

            if (toolCalls.isEmpty()) {
                return;
            }

            appendResponseOutput(conversation, response.output());
            for (ResponseFunctionToolCall toolCall : toolCalls) {
                sink.status(toolStatus(toolCall.name()));
                String output = executeTool(toolCall, accessToken);
                conversation.add(ResponseInputItem.ofFunctionCallOutput(
                        ResponseInputItem.FunctionCallOutput.builder()
                                .callId(toolCall.callId())
                                .output(output)
                                .status(ResponseInputItem.FunctionCallOutput.Status.COMPLETED)
                                .build()));
            }
        }

        throw new AiUpstreamException("AI 工具调用次数过多，请换一种方式描述需求");
    }

    private Response createStreamingResponse(
            List<ResponseInputItem> conversation,
            ChatStreamSink sink) {
        ResponseCreateParams params = ResponseCreateParams.builder()
                .model(model)
                .instructions(INSTRUCTIONS)
                .inputOfResponse(conversation)
                .store(false)
                .parallelToolCalls(true)
                .addTool(searchProductsTool)
                .addTool(listMyOrdersTool)
                .build();
        AtomicReference<Response> completedResponse = new AtomicReference<>();
        try (StreamResponse<ResponseStreamEvent> stream =
                     openAIClient.responses().createStreaming(params)) {
            stream.stream().forEach(event -> handleStreamEvent(
                    event, sink, completedResponse));
        } catch (RuntimeException exception) {
            if (exception instanceof AiUpstreamException) {
                throw exception;
            }
            throw new AiUpstreamException("AI 服务暂时不可用，请稍后重试", exception);
        }

        Response response = completedResponse.get();
        if (response == null) {
            throw new AiUpstreamException("AI 流式响应未正常完成");
        }
        return response;
    }

    private void handleStreamEvent(
            ResponseStreamEvent event,
            ChatStreamSink sink,
            AtomicReference<Response> completedResponse) {
        if (!sink.isOpen()) {
            throw new AiUpstreamException("客户端已断开连接");
        }
        if (event.isOutputTextDelta()) {
            String delta = event.asOutputTextDelta().delta();
            if (!delta.isEmpty()) {
                sink.delta(delta);
            }
        } else if (event.isCompleted()) {
            completedResponse.set(event.asCompleted().response());
        } else if (event.isError() || event.isFailed() || event.isIncomplete()) {
            throw new AiUpstreamException("AI 流式响应异常结束");
        }
    }

    private String toolStatus(String toolName) {
        return switch (toolName) {
            case "search_products" -> "正在查询商品…";
            case "list_my_orders" -> "正在查询你的订单…";
            default -> "正在处理请求…";
        };
    }

    private String executeTool(ResponseFunctionToolCall toolCall, String accessToken) {
        try {
            return switch (toolCall.name()) {
                case "search_products" -> {
                    SearchProductsArguments arguments =
                            toolCall.arguments(SearchProductsArguments.class);
                    yield commerceToolService.searchProducts(arguments.name());
                }
                case "list_my_orders" -> commerceToolService.listMyOrders(accessToken);
                default -> "{\"error\":\"不支持的工具\"}";
            };
        } catch (AiUpstreamException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AiUpstreamException("AI 返回了无法执行的工具参数", exception);
        }
    }

    private void appendResponseOutput(
            List<ResponseInputItem> conversation,
            List<ResponseOutputItem> output) {
        for (ResponseOutputItem item : output) {
            if (item.isFunctionCall()) {
                conversation.add(ResponseInputItem.ofFunctionCall(item.asFunctionCall()));
            } else if (item.isReasoning()) {
                conversation.add(ResponseInputItem.ofReasoning(item.asReasoning()));
            } else if (item.isMessage()) {
                conversation.add(ResponseInputItem.ofResponseOutputMessage(item.asMessage()));
            }
        }
    }

    private FunctionTool createSearchProductsTool() {
        return FunctionTool.builder()
                .name("search_products")
                .description("查询 SmartMall 中当前在售的商品，可按商品名称进行模糊搜索")
                .parameters(objectSchema(Map.of(
                        "name", Map.of(
                                "type", "string",
                                "description", "可选的商品名称关键词"
                        ))))
                .strict(false)
                .build();
    }

    private FunctionTool createListMyOrdersTool() {
        return FunctionTool.builder()
                .name("list_my_orders")
                .description("查询当前登录用户自己的订单列表")
                .parameters(objectSchema(Map.of()))
                .strict(false)
                .build();
    }

    private FunctionTool.Parameters objectSchema(Map<String, ?> properties) {
        return FunctionTool.Parameters.builder()
                .putAdditionalProperty("type", JsonValue.from("object"))
                .putAdditionalProperty("properties", JsonValue.from(properties))
                .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                .build();
    }

    private record SearchProductsArguments(String name) {
    }
}

package com.agentmesh.deterministic.mock;

import java.util.concurrent.CountDownLatch;

public final class MockLiteLlmGatewayMain {
    private static final int DEFAULT_PORT = 8080;

    private MockLiteLlmGatewayMain() {
    }

    public static void main(String[] args) throws InterruptedException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        MockLiteLlmGateway gateway = new MockLiteLlmGateway(port);
        CountDownLatch stop = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            gateway.stop();
            stop.countDown();
        }, "mock-litellm-gateway-shutdown"));
        gateway.start();
        System.out.println("[Mock LiteLLM] listening at " + gateway.baseUrl() + "/v1");
        stop.await();
    }
}
